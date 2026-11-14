use anyhow::{anyhow, bail, Context, Result};
use ndarray::{s, Array1, Array3, ArrayView3, Ix3};
use ort::execution_providers::cpu::CPUExecutionProvider;
use ort::execution_providers::nnapi::NNAPIExecutionProvider;
use ort::execution_providers::xnnpack::XNNPACKExecutionProvider;
use ort::execution_providers::{ExecutionProvider, ExecutionProviderDispatch};
use ort::inputs;
use ort::session::builder::GraphOptimizationLevel;
use ort::session::Session;
use ort::value::TensorRef;
use regex::Regex;
use rustfft::{num_complex::Complex32, Fft, FftPlanner};
use std::cmp::Ordering;
use std::f32::consts::PI;
use std::fs;
use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::{Arc, LazyLock};
use std::time::Instant;

const MODEL_FILENAMES: [&str; 2] = ["v3_e2e_ctc.int8.onnx", "v3_e2e_ctc.onnx"];
const VOCAB_FILENAME: &str = "v3_e2e_ctc_vocab.txt";
const CONFIG_FILENAME: &str = "v3_e2e_ctc.yaml";

const MEL_MIN_CLAMP: f32 = 1e-9;
const MEL_MAX_CLAMP: f32 = 1e9;
const XNNPACK_THREAD_COUNT: usize = 4;

static DECODE_SPACE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\A\s|\s\B|(\s)\b").expect("valid decode spacing regex"));

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeSpeedProfile {
    Balanced,
    Fast,
    Quality,
}

impl RuntimeSpeedProfile {
    pub fn from_id(value: &str) -> Self {
        match value {
            "fast" => Self::Fast,
            "quality" => Self::Quality,
            _ => Self::Balanced,
        }
    }

    pub fn as_id(self) -> &'static str {
        match self {
            Self::Balanced => "balanced",
            Self::Fast => "fast",
            Self::Quality => "quality",
        }
    }

    fn nnapi_use_fp16(self) -> bool {
        !matches!(self, Self::Quality)
    }

    /// (intra_threads, inter_threads, parallel_execution) for the CPU-only mode.
    fn cpu_threads(self) -> (usize, usize, bool) {
        match self {
            Self::Balanced => (4, 1, false),
            Self::Fast => (6, 1, false),
            Self::Quality => (4, 1, true),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeAcceleratorMode {
    Auto,
    Cpu,
}

impl RuntimeAcceleratorMode {
    pub fn from_id(value: &str) -> Self {
        match value {
            "cpu" => Self::Cpu,
            _ => Self::Auto,
        }
    }

    pub fn as_id(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Cpu => "cpu",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeOptions {
    pub speed_profile: RuntimeSpeedProfile,
    pub accelerator_mode: RuntimeAcceleratorMode,
}

impl RuntimeOptions {
    pub const DEFAULT: Self = Self {
        speed_profile: RuntimeSpeedProfile::Balanced,
        accelerator_mode: RuntimeAcceleratorMode::Auto,
    };

    pub fn from_ids(speed_profile: &str, accelerator_mode: &str) -> Self {
        Self {
            speed_profile: RuntimeSpeedProfile::from_id(speed_profile),
            accelerator_mode: RuntimeAcceleratorMode::from_id(accelerator_mode),
        }
    }
}

impl Default for RuntimeOptions {
    fn default() -> Self {
        Self::DEFAULT
    }
}

#[derive(Debug, Clone, Default)]
pub struct NativeTranscriptionTimings {
    pub feature_extraction_ms: u128,
    pub ort_run_ms: u128,
    pub decode_ms: u128,
    pub total_ms: u128,
}

#[derive(Debug, Clone)]
pub struct NativeTranscriptionReport {
    pub text: String,
    pub timings: NativeTranscriptionTimings,
    pub provider_summary: String,
}

impl NativeTranscriptionReport {
    /// JSON object members without the surrounding braces, so callers can add their own fields.
    pub fn json_fields(&self) -> String {
        let safe_provider = escape_json_string(&self.provider_summary);
        format!(
            "\"provider\":\"{safe_provider}\",\"feature_extraction_ms\":{},\"ort_run_ms\":{},\"decode_ms\":{},\"total_ms\":{}",
            self.timings.feature_extraction_ms,
            self.timings.ort_run_ms,
            self.timings.decode_ms,
            self.timings.total_ms
        )
    }
}

struct SessionRuntimePlan {
    providers: Vec<ExecutionProviderDispatch>,
    provider_summary: String,
    intra_threads: usize,
    inter_threads: usize,
    parallel_execution: bool,
}

impl SessionRuntimePlan {
    fn from_runtime_options(options: RuntimeOptions) -> Self {
        let profile = options.speed_profile;
        let cpu_available = CPUExecutionProvider::default().is_available().unwrap_or(true);
        match options.accelerator_mode {
            RuntimeAcceleratorMode::Auto => {
                let xnnpack = XNNPACKExecutionProvider::default().with_intra_op_num_threads(
                    NonZeroUsize::new(XNNPACK_THREAD_COUNT).expect("non-zero constant"),
                );
                let nnapi = NNAPIExecutionProvider::default()
                    .with_disable_cpu(true)
                    .with_fp16(profile.nnapi_use_fp16());
                let provider_summary = format!(
                    "mode=auto, profile={}, requested=[XNNPACK(available={},threads={}), NNAPI(available={},disable_cpu=true,fp16={}), CPU(available={})]",
                    profile.as_id(),
                    xnnpack.is_available().unwrap_or(false),
                    XNNPACK_THREAD_COUNT,
                    nnapi.is_available().unwrap_or(false),
                    profile.nnapi_use_fp16(),
                    cpu_available
                );

                // XNNPACK runs its own thread pool, so ORT's own pools stay at one thread.
                Self {
                    providers: vec![
                        xnnpack.build(),
                        nnapi.build(),
                        CPUExecutionProvider::default().build(),
                    ],
                    provider_summary,
                    intra_threads: 1,
                    inter_threads: 1,
                    parallel_execution: false,
                }
            }
            RuntimeAcceleratorMode::Cpu => {
                let (intra_threads, inter_threads, parallel_execution) = profile.cpu_threads();
                Self {
                    providers: vec![CPUExecutionProvider::default().build()],
                    provider_summary: format!(
                        "mode=cpu, profile={}, requested=[CPU(available={})]",
                        profile.as_id(),
                        cpu_available
                    ),
                    intra_threads,
                    inter_threads,
                    parallel_execution,
                }
            }
        }
    }
}

#[derive(Debug, Clone)]
struct GigaamConfig {
    sample_rate: usize,
    n_mels: usize,
    win_length: usize,
    hop_length: usize,
    n_fft: usize,
    center: bool,
    mel_scale: String,
    subsampling_factor: usize,
    model_name: String,
}

impl Default for GigaamConfig {
    fn default() -> Self {
        Self {
            sample_rate: 16_000,
            n_mels: 64,
            win_length: 320,
            hop_length: 160,
            n_fft: 320,
            center: false,
            mel_scale: "htk".to_string(),
            subsampling_factor: 4,
            model_name: "v3_e2e_ctc".to_string(),
        }
    }
}

impl GigaamConfig {
    /// Reads the few scalar keys the frontend needs; nesting and unknown keys are ignored.
    fn from_yaml(content: &str) -> Self {
        fn parse_into<T: std::str::FromStr>(target: &mut T, value: &str) {
            if let Ok(parsed) = value.parse() {
                *target = parsed;
            }
        }

        let mut config = Self::default();
        for raw_line in content.lines() {
            let line = raw_line.split('#').next().unwrap_or("").trim();
            let Some((key, value)) = line.split_once(':') else {
                continue;
            };
            let value = value.trim();
            match key {
                "sample_rate" => parse_into(&mut config.sample_rate, value),
                "features" => parse_into(&mut config.n_mels, value),
                "win_length" => parse_into(&mut config.win_length, value),
                "hop_length" => parse_into(&mut config.hop_length, value),
                "n_fft" => parse_into(&mut config.n_fft, value),
                "center" => parse_into(&mut config.center, value),
                "mel_scale" => config.mel_scale = value.to_string(),
                "subsampling_factor" => parse_into(&mut config.subsampling_factor, value),
                "model_name" => config.model_name = value.to_string(),
                _ => {}
            }
        }
        config
    }
}

/// One triangular mel filter, stored only over the FFT bins where its weight is non-zero.
struct MelFilter {
    first_bin: usize,
    weights: Vec<f32>,
}

struct GigaamFrontend {
    n_mels: usize,
    win_length: usize,
    hop_length: usize,
    n_fft: usize,
    hann_window: Vec<f32>,
    mel_filters: Vec<MelFilter>,
    fft: Arc<dyn Fft<f32>>,
}

impl GigaamFrontend {
    fn from_config(config: &GigaamConfig) -> Result<Self> {
        if config.hop_length == 0 {
            bail!("Invalid GigaAM config: hop_length must be > 0");
        }
        if config.win_length == 0 {
            bail!("Invalid GigaAM config: win_length must be > 0");
        }
        if config.n_fft == 0 {
            bail!("Invalid GigaAM config: n_fft must be > 0");
        }
        if config.n_fft < config.win_length {
            bail!(
                "Invalid GigaAM config: n_fft ({}) < win_length ({})",
                config.n_fft,
                config.win_length
            );
        }
        if !config.mel_scale.eq_ignore_ascii_case("htk") {
            bail!(
                "Unsupported GigaAM mel_scale '{}'; expected 'htk'",
                config.mel_scale
            );
        }
        if config.center {
            bail!("Unsupported GigaAM config: center=true is not supported for this model");
        }

        let quantize_bf16 = config.model_name.contains("v3") || config.n_fft == 320;
        let hann_window = build_hann_window(config.win_length, quantize_bf16);
        let mel_filters = build_mel_filters(
            config.sample_rate,
            config.n_fft,
            config.n_mels,
            quantize_bf16,
        )?;
        let fft = FftPlanner::<f32>::new().plan_fft_forward(config.n_fft);

        Ok(Self {
            n_mels: config.n_mels,
            win_length: config.win_length,
            hop_length: config.hop_length,
            n_fft: config.n_fft,
            hann_window,
            mel_filters,
            fft,
        })
    }

    fn extract_features(&self, samples: &[f32]) -> Result<(Array3<f32>, i64)> {
        if samples.len() < self.win_length {
            return Ok((Array3::zeros((1, self.n_mels, 0)), 0));
        }

        let frame_count = ((samples.len() - self.win_length) / self.hop_length) + 1;
        let n_freq_bins = (self.n_fft / 2) + 1;

        let mut features = vec![0.0_f32; self.n_mels * frame_count];
        let mut fft_buffer = vec![Complex32::new(0.0, 0.0); self.n_fft];
        let mut fft_scratch = vec![Complex32::new(0.0, 0.0); self.fft.get_inplace_scratch_len()];
        let mut power_spectrum = vec![0.0_f32; n_freq_bins];

        for frame_idx in 0..frame_count {
            let frame = &samples[frame_idx * self.hop_length..][..self.win_length];
            for ((slot, &sample), &window) in fft_buffer.iter_mut().zip(frame).zip(&self.hann_window) {
                *slot = Complex32::new(sample * window, 0.0);
            }
            fft_buffer[self.win_length..].fill(Complex32::new(0.0, 0.0));

            self.fft.process_with_scratch(&mut fft_buffer, &mut fft_scratch);

            for (power, complex) in power_spectrum.iter_mut().zip(&fft_buffer) {
                *power = complex.re.mul_add(complex.re, complex.im * complex.im);
            }

            for (mel_idx, filter) in self.mel_filters.iter().enumerate() {
                let bins = &power_spectrum[filter.first_bin..][..filter.weights.len()];
                let mut mel_energy = 0.0_f32;
                for (&power, &weight) in bins.iter().zip(&filter.weights) {
                    mel_energy += power * weight;
                }
                let clamped = mel_energy.clamp(MEL_MIN_CLAMP, MEL_MAX_CLAMP);
                features[mel_idx * frame_count + frame_idx] = clamped.ln();
            }
        }

        let features = Array3::from_shape_vec((1, self.n_mels, frame_count), features)?;
        Ok((features, frame_count as i64))
    }
}

pub struct GigaamModel {
    session: Session,
    frontend: GigaamFrontend,
    vocab: Vec<String>,
    blank_idx: usize,
    subsampling_factor: usize,
    features_input_name: String,
    feature_lengths_input_name: String,
    logits_output_name: String,
    provider_summary: String,
}

impl GigaamModel {
    pub fn load(model_dir: &Path, runtime_options: RuntimeOptions) -> Result<Self> {
        let model_path = MODEL_FILENAMES
            .iter()
            .map(|filename| model_dir.join(filename))
            .find(|path| path.exists())
            .ok_or_else(|| {
                anyhow!(
                    "Missing GigaAM model file in {}. Expected one of: {}",
                    model_dir.display(),
                    MODEL_FILENAMES.join(", ")
                )
            })?;
        let vocab_content = read_model_file(&model_dir.join(VOCAB_FILENAME), "vocab")?;
        let (vocab, blank_idx) = parse_vocab_content(&vocab_content)?;
        let config = GigaamConfig::from_yaml(&read_model_file(&model_dir.join(CONFIG_FILENAME), "config")?);
        if config.sample_rate != 16_000 {
            bail!(
                "Unsupported GigaAM sample rate {} Hz; the IME provides 16000 Hz PCM input",
                config.sample_rate
            );
        }
        let frontend = GigaamFrontend::from_config(&config)?;

        let runtime_plan = SessionRuntimePlan::from_runtime_options(runtime_options);
        let session = Session::builder()?
            .with_optimization_level(GraphOptimizationLevel::Level3)?
            .with_intra_threads(runtime_plan.intra_threads)?
            .with_inter_threads(runtime_plan.inter_threads)?
            .with_execution_providers(runtime_plan.providers)?
            .with_parallel_execution(runtime_plan.parallel_execution)?
            .commit_from_file(&model_path)
            .with_context(|| {
                format!("Failed to initialize ONNX Runtime session: {}", model_path.display())
            })?;

        // Prefer the known tensor names; fall back to the first input of the expected rank.
        let find_input = |name: &str, rank: usize| {
            session
                .inputs
                .iter()
                .find(|input| input.name == name)
                .or_else(|| {
                    session.inputs.iter().find(|input| {
                        input.input_type.tensor_shape().is_some_and(|shape| shape.len() == rank)
                    })
                })
                .map(|input| input.name.clone())
        };
        let features_input_name = find_input("features", 3)
            .ok_or_else(|| anyhow!("Failed to determine GigaAM features input"))?;
        let feature_lengths_input_name = find_input("feature_lengths", 1)
            .ok_or_else(|| anyhow!("Failed to determine GigaAM feature lengths input"))?;
        let logits_output_name = session
            .outputs
            .iter()
            .find(|output| output.name == "log_probs" || output.name == "logits")
            .or_else(|| session.outputs.first())
            .map(|output| output.name.clone())
            .ok_or_else(|| anyhow!("Failed to determine GigaAM logits output"))?;

        Ok(Self {
            session,
            frontend,
            vocab,
            blank_idx,
            subsampling_factor: config.subsampling_factor.max(1),
            features_input_name,
            feature_lengths_input_name,
            logits_output_name,
            provider_summary: runtime_plan.provider_summary,
        })
    }

    pub fn transcribe_samples(&mut self, samples: &[f32]) -> Result<NativeTranscriptionReport> {
        let total_start = Instant::now();
        let mut timings = NativeTranscriptionTimings::default();

        let feature_start = Instant::now();
        let (features, feature_length) = self.frontend.extract_features(samples)?;
        timings.feature_extraction_ms = feature_start.elapsed().as_millis();

        let text = if feature_length == 0 {
            String::new()
        } else {
            let feature_lengths = Array1::from_vec(vec![feature_length]);
            let inputs = inputs![
                self.features_input_name.as_str() => TensorRef::from_array_view(features.view())?,
                self.feature_lengths_input_name.as_str() => TensorRef::from_array_view(feature_lengths.view())?,
            ];

            let ort_start = Instant::now();
            let outputs = self.session.run(inputs)?;
            timings.ort_run_ms = ort_start.elapsed().as_millis();

            let decode_start = Instant::now();
            let logits = outputs
                .get(self.logits_output_name.as_str())
                .ok_or_else(|| {
                    anyhow!(
                        "GigaAM output '{}' not found in inference outputs",
                        self.logits_output_name
                    )
                })?
                .try_extract_array::<f32>()?
                .into_dimensionality::<Ix3>()?;

            let encoded_len = ((feature_length - 1) / self.subsampling_factor as i64 + 1).max(0) as usize;
            let token_ids = ctc_greedy_decode_ids(logits, encoded_len, self.blank_idx);
            let text = decode_token_ids_to_text(&token_ids, &self.vocab);
            timings.decode_ms = decode_start.elapsed().as_millis();
            text
        };

        timings.total_ms = total_start.elapsed().as_millis();
        Ok(NativeTranscriptionReport {
            text,
            timings,
            provider_summary: self.provider_summary.clone(),
        })
    }
}

fn read_model_file(path: &Path, kind: &str) -> Result<String> {
    if !path.exists() {
        bail!("Missing GigaAM {kind} file: {}", path.display());
    }
    fs::read_to_string(path)
        .with_context(|| format!("Failed to read GigaAM {kind} file: {}", path.display()))
}

fn parse_vocab_content(content: &str) -> Result<(Vec<String>, usize)> {
    let mut entries = Vec::<(usize, String)>::new();
    let mut max_id = 0_usize;
    let mut blank_idx = None;

    for raw_line in content.lines() {
        let line = raw_line.trim_end();
        if line.is_empty() {
            continue;
        }
        let (token, id_str) = line
            .rsplit_once(' ')
            .ok_or_else(|| anyhow!("Invalid vocab line: '{}'", line))?;
        let id = id_str
            .trim()
            .parse::<usize>()
            .with_context(|| format!("Invalid vocab token id in line '{}'", line))?;

        if token == "<blk>" {
            blank_idx = Some(id);
        }

        max_id = max_id.max(id);
        entries.push((id, token.replace('\u{2581}', " ")));
    }

    let blank_idx = blank_idx.ok_or_else(|| anyhow!("Missing <blk> token in vocabulary file"))?;

    let mut vocab = vec![String::new(); max_id + 1];
    for (id, token) in entries {
        vocab[id] = token;
    }

    Ok((vocab, blank_idx))
}

fn ctc_greedy_decode_ids(
    logits: ArrayView3<'_, f32>,
    encoded_len: usize,
    blank_idx: usize,
) -> Vec<usize> {
    let usable_steps = encoded_len.min(logits.shape()[1]);

    let mut token_ids = Vec::with_capacity(usable_steps);
    let mut prev_token = blank_idx;

    for frame_idx in 0..usable_steps {
        let best_idx = logits
            .slice(s![0, frame_idx, ..])
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(Ordering::Equal))
            .map_or(blank_idx, |(idx, _)| idx);

        if best_idx != blank_idx && best_idx != prev_token {
            token_ids.push(best_idx);
        }
        prev_token = best_idx;
    }

    token_ids
}

fn decode_token_ids_to_text(token_ids: &[usize], vocab: &[String]) -> String {
    let concatenated: String = token_ids
        .iter()
        .filter_map(|&id| vocab.get(id).map(String::as_str))
        .collect();

    // Drop leading spaces and spaces before non-word characters; keep one space before words.
    DECODE_SPACE_RE
        .replace_all(&concatenated, |captures: &regex::Captures<'_>| {
            if captures.get(1).is_some() {
                " "
            } else {
                ""
            }
        })
        .into_owned()
}

fn build_hann_window(win_length: usize, quantize_bf16: bool) -> Vec<f32> {
    if win_length == 1 {
        return vec![1.0];
    }

    (0..win_length)
        .map(|n| {
            let value = 0.5 - 0.5 * (2.0 * PI * n as f32 / (win_length as f32 - 1.0)).cos();
            if quantize_bf16 {
                quantize_to_bf16(value)
            } else {
                value
            }
        })
        .collect()
}

fn build_mel_filters(
    sample_rate: usize,
    n_fft: usize,
    n_mels: usize,
    quantize_bf16: bool,
) -> Result<Vec<MelFilter>> {
    let n_freq_bins = n_fft / 2 + 1;
    let mel_min = hz_to_mel_htk(0.0);
    let mel_max = hz_to_mel_htk(sample_rate as f32 / 2.0);

    let hz_points: Vec<f32> = (0..(n_mels + 2))
        .map(|i| mel_to_hz_htk(mel_min + (mel_max - mel_min) * (i as f32 / (n_mels + 1) as f32)))
        .collect();
    let fft_freqs: Vec<f32> = (0..n_freq_bins)
        .map(|bin| bin as f32 * sample_rate as f32 / n_fft as f32)
        .collect();

    (0..n_mels)
        .map(|mel_idx| {
            let left = hz_points[mel_idx];
            let center = hz_points[mel_idx + 1];
            let right = hz_points[mel_idx + 2];
            if center <= left || right <= center {
                bail!("Invalid mel filter points for index {}", mel_idx);
            }

            let row: Vec<f32> = fft_freqs
                .iter()
                .map(|&freq| {
                    let weight = if freq >= left && freq <= center {
                        (freq - left) / (center - left)
                    } else if freq > center && freq <= right {
                        (right - freq) / (right - center)
                    } else {
                        0.0
                    };
                    if weight <= 0.0 {
                        0.0
                    } else if quantize_bf16 {
                        quantize_to_bf16(weight)
                    } else {
                        weight
                    }
                })
                .collect();

            // Zero weights add nothing to the sum, so only the non-zero span is kept.
            let first_bin = row.iter().position(|&w| w != 0.0).unwrap_or(0);
            let end_bin = row.iter().rposition(|&w| w != 0.0).map_or(first_bin, |last| last + 1);
            Ok(MelFilter {
                first_bin,
                weights: row[first_bin..end_bin].to_vec(),
            })
        })
        .collect()
}

#[inline]
fn hz_to_mel_htk(hz: f32) -> f32 {
    2595.0 * (1.0 + hz / 700.0).log10()
}

#[inline]
fn mel_to_hz_htk(mel: f32) -> f32 {
    700.0 * (10_f32.powf(mel / 2595.0) - 1.0)
}

#[inline]
fn quantize_to_bf16(value: f32) -> f32 {
    f32::from_bits(value.to_bits() & 0xFFFF_0000)
}

fn escape_json_string(value: &str) -> String {
    value.replace('\\', "\\\\").replace('\"', "\\\"")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vocab_loader_parses_blank_and_word_boundary_tokens() -> Result<()> {
        let vocab_content = "<unk> 0\n\u{2581}hello 1\nworld 2\n<blk> 3\n";
        let (vocab, blank_idx) = parse_vocab_content(vocab_content)?;

        assert_eq!(blank_idx, 3);
        assert_eq!(vocab[1], " hello");
        assert_eq!(vocab[2], "world");
        Ok(())
    }

    #[test]
    fn ctc_decoder_collapses_repeats_and_removes_blank() -> Result<()> {
        let logits = Array3::from_shape_vec(
            (1, 6, 4),
            vec![
                0.0, 5.0, 1.0, -1.0, // token 1
                0.0, 4.0, 1.0, -1.0, // repeated token 1 (collapsed)
                0.0, 1.0, 0.0, 3.0, // blank
                0.0, 6.0, 0.0, -1.0, // token 1 again (kept because blank separated)
                0.0, 1.0, 5.0, -1.0, // token 2
                0.0, 1.0, 4.0, -1.0, // repeated token 2 (collapsed)
            ],
        )?;

        let token_ids = ctc_greedy_decode_ids(logits.view(), 6, 3);
        assert_eq!(token_ids, vec![1, 1, 2]);
        Ok(())
    }

    #[test]
    fn decoder_normalizes_spaces_around_punctuation() {
        let vocab: Vec<String> = [" при", "вет", ",", " мир", " ."]
            .iter()
            .map(|token| token.to_string())
            .collect();
        assert_eq!(decode_token_ids_to_text(&[0, 1, 2, 3, 4], &vocab), "привет, мир.");
    }

    #[test]
    fn config_parser_reads_nested_keys_and_ignores_comments() {
        let config = GigaamConfig::from_yaml(
            "model_name: v3_e2e_ctc\npreprocessor:\n  sample_rate: 16000 # Hz\n  features: 64\n  \
             win_length: 320\n  hop_length: 160\n  n_fft: 320\n  center: false\n  mel_scale: htk\n\
             encoder:\n  subsampling_factor: 4\n  n_fft_typo: 999\n",
        );
        assert_eq!(config.sample_rate, 16_000);
        assert_eq!(config.n_mels, 64);
        assert_eq!(config.n_fft, 320);
        assert!(!config.center);
        assert_eq!(config.mel_scale, "htk");
        assert_eq!(config.subsampling_factor, 4);
    }

    #[test]
    fn sparse_mel_filters_match_dense_triangles() -> Result<()> {
        let filters = build_mel_filters(16_000, 320, 64, true)?;
        assert_eq!(filters.len(), 64);
        for filter in &filters {
            assert!(!filter.weights.is_empty());
            assert!(filter.first_bin + filter.weights.len() <= 161);
            assert!(filter.weights.iter().all(|&w| w > 0.0));
        }
        Ok(())
    }

    /// The pre-refactor dense frontend (full filterbank matrix, allocating FFT), kept verbatim
    /// so the sparse frontend can be checked bit for bit.
    fn dense_reference_features(config: &GigaamConfig, samples: &[f32]) -> Vec<f32> {
        let n_freq_bins = config.n_fft / 2 + 1;
        let n_mels = config.n_mels;
        let hann_window = build_hann_window(config.win_length, true);
        let mel_min = hz_to_mel_htk(0.0);
        let mel_max = hz_to_mel_htk(config.sample_rate as f32 / 2.0);
        let hz_points: Vec<f32> = (0..(n_mels + 2))
            .map(|i| mel_to_hz_htk(mel_min + (mel_max - mel_min) * (i as f32 / (n_mels + 1) as f32)))
            .collect();
        let mut filterbank = vec![0.0_f32; n_freq_bins * n_mels];
        for mel_idx in 0..n_mels {
            let (left, center, right) = (hz_points[mel_idx], hz_points[mel_idx + 1], hz_points[mel_idx + 2]);
            for bin_idx in 0..n_freq_bins {
                let freq = bin_idx as f32 * config.sample_rate as f32 / config.n_fft as f32;
                let weight = if freq >= left && freq <= center {
                    (freq - left) / (center - left)
                } else if freq > center && freq <= right {
                    (right - freq) / (right - center)
                } else {
                    0.0
                };
                if weight > 0.0 {
                    filterbank[bin_idx * n_mels + mel_idx] = quantize_to_bf16(weight);
                }
            }
        }

        let fft = FftPlanner::<f32>::new().plan_fft_forward(config.n_fft);
        let frame_count = (samples.len() - config.win_length) / config.hop_length + 1;
        let mut features = vec![0.0_f32; n_mels * frame_count];
        let mut buffer = vec![Complex32::new(0.0, 0.0); config.n_fft];
        let mut power_spectrum = vec![0.0_f32; n_freq_bins];
        for frame_idx in 0..frame_count {
            let start = frame_idx * config.hop_length;
            for i in 0..config.n_fft {
                let sample = if i < config.win_length { samples[start + i] * hann_window[i] } else { 0.0 };
                buffer[i] = Complex32::new(sample, 0.0);
            }
            fft.process(&mut buffer);
            for (bin_idx, power) in power_spectrum.iter_mut().enumerate() {
                let c = buffer[bin_idx];
                *power = c.re.mul_add(c.re, c.im * c.im);
            }
            for mel_idx in 0..n_mels {
                let mut mel_energy = 0.0_f32;
                for (bin_idx, &power) in power_spectrum.iter().enumerate() {
                    mel_energy += power * filterbank[bin_idx * n_mels + mel_idx];
                }
                features[mel_idx * frame_count + frame_idx] =
                    mel_energy.clamp(MEL_MIN_CLAMP, MEL_MAX_CLAMP).ln();
            }
        }
        features
    }

    #[test]
    fn sparse_frontend_matches_dense_reference_bit_for_bit() -> Result<()> {
        let config = GigaamConfig::default();
        let frontend = GigaamFrontend::from_config(&config)?;
        // 1.3 s of a tone plus deterministic noise, quantized like 16-bit PCM input.
        let mut state = 0x2545_f491_u32;
        let samples: Vec<f32> = (0..20_800)
            .map(|n| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                let noise = (state as f32 / u32::MAX as f32) * 2.0 - 1.0;
                let value = 0.4 * (2.0 * PI * 440.0 * n as f32 / 16_000.0).sin() + 0.1 * noise;
                (value * i16::MAX as f32).round() / i16::MAX as f32
            })
            .collect();

        let (features, frame_count) = frontend.extract_features(&samples)?;
        let reference = dense_reference_features(&config, &samples);
        assert_eq!(frame_count as usize * config.n_mels, reference.len());
        let mismatches = features
            .iter()
            .zip(&reference)
            .filter(|(a, b)| a.to_bits() != b.to_bits())
            .count();
        assert_eq!(mismatches, 0, "sparse and dense features differ in {mismatches} values");
        Ok(())
    }

    #[test]
    #[ignore = "Requires local model files and a WAV fixture; set GIGAAM_TEST_MODEL_DIR and GIGAAM_TEST_WAV_PATH"]
    fn integration_transcribes_wav_fixture() -> Result<()> {
        let model_dir = std::env::var("GIGAAM_TEST_MODEL_DIR")
            .context("GIGAAM_TEST_MODEL_DIR is required for integration test")?;
        let wav_path = std::env::var("GIGAAM_TEST_WAV_PATH")
            .context("GIGAAM_TEST_WAV_PATH is required for integration test")?;
        let expected_substring = std::env::var("GIGAAM_TEST_EXPECT_CONTAINS").unwrap_or_default();

        let mut model = GigaamModel::load(Path::new(&model_dir), RuntimeOptions::default())?;
        let samples = read_wav_mono_f32(Path::new(&wav_path))?;
        for run in 1..=3 {
            let report = model.transcribe_samples(&samples)?;
            eprintln!("run {run}: {{{}}} text={:?}", report.json_fields(), report.text);
        }
        let text = model.transcribe_samples(&samples)?.text;
        assert!(
            !text.trim().is_empty(),
            "Expected non-empty transcription result"
        );
        if !expected_substring.is_empty() {
            assert!(
                text.contains(&expected_substring),
                "Expected transcription to contain '{}', got '{}'",
                expected_substring,
                text
            );
        }
        Ok(())
    }

    fn read_wav_mono_f32(path: &Path) -> Result<Vec<f32>> {
        let mut reader = hound::WavReader::open(path)
            .with_context(|| format!("Failed to open WAV fixture: {}", path.display()))?;
        let spec = reader.spec();
        let channels = usize::from(spec.channels.max(1));

        let interleaved: Vec<f32> = match spec.sample_format {
            hound::SampleFormat::Float => reader.samples::<f32>().collect::<Result<_, _>>()?,
            hound::SampleFormat::Int => {
                let max_amplitude = (1_i64 << (spec.bits_per_sample.saturating_sub(1))) as f32;
                reader
                    .samples::<i32>()
                    .map(|sample| sample.map(|value| value as f32 / max_amplitude))
                    .collect::<Result<_, _>>()?
            }
        };

        Ok(interleaved
            .chunks(channels)
            .map(|frame| frame.iter().sum::<f32>() / channels as f32)
            .collect())
    }
}
