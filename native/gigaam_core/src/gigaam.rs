use anyhow::{Context, Result};
use ndarray::{s, Array1, Array3, ArrayView3, Ix3};
use once_cell::sync::Lazy;
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
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

const MODEL_FILENAMES: [&str; 2] = ["v3_e2e_ctc.int8.onnx", "v3_e2e_ctc.onnx"];
const VOCAB_FILENAME: &str = "v3_e2e_ctc_vocab.txt";
const CONFIG_FILENAME: &str = "v3_e2e_ctc.yaml";

const MEL_MIN_CLAMP: f32 = 1e-9;
const MEL_MAX_CLAMP: f32 = 1e9;
const XNNPACK_THREAD_COUNT: usize = 4;

static DECODE_SPACE_RE: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"\A\s|\s\B|(\s)\b").expect("valid decode spacing regex"));

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

    pub fn as_id(&self) -> &'static str {
        match self {
            Self::Balanced => "balanced",
            Self::Fast => "fast",
            Self::Quality => "quality",
        }
    }

    fn nnapi_use_fp16(self) -> bool {
        !matches!(self, Self::Quality)
    }

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

    pub fn as_id(&self) -> &'static str {
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

impl Default for RuntimeOptions {
    fn default() -> Self {
        Self {
            speed_profile: RuntimeSpeedProfile::Balanced,
            accelerator_mode: RuntimeAcceleratorMode::Auto,
        }
    }
}

impl RuntimeOptions {
    pub fn from_ids(speed_profile: &str, accelerator_mode: &str) -> Self {
        Self {
            speed_profile: RuntimeSpeedProfile::from_id(speed_profile),
            accelerator_mode: RuntimeAcceleratorMode::from_id(accelerator_mode),
        }
    }

    pub fn cache_fragment(self) -> String {
        format!(
            "profile={};accelerator={}",
            self.speed_profile.as_id(),
            self.accelerator_mode.as_id()
        )
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
    pub fn to_json(&self) -> String {
        let safe_provider = escape_json_string(&self.provider_summary);
        format!(
            "{{\"provider\":\"{safe_provider}\",\"feature_extraction_ms\":{},\"ort_run_ms\":{},\"decode_ms\":{},\"total_ms\":{}}}",
            self.timings.feature_extraction_ms,
            self.timings.ort_run_ms,
            self.timings.decode_ms,
            self.timings.total_ms
        )
    }
}

#[derive(Debug, Clone)]
struct SessionRuntimePlan {
    providers: Vec<ExecutionProviderDispatch>,
    provider_summary: String,
    intra_threads: usize,
    inter_threads: usize,
    parallel_execution: bool,
}

impl SessionRuntimePlan {
    fn from_runtime_options(options: RuntimeOptions) -> Self {
        match options.accelerator_mode {
            RuntimeAcceleratorMode::Auto => {
                let xnn_available = XNNPACKExecutionProvider::default()
                    .is_available()
                    .unwrap_or(false);
                let nnapi_available = NNAPIExecutionProvider::default().is_available().unwrap_or(false);
                let cpu_available = CPUExecutionProvider::default().is_available().unwrap_or(true);

                let providers = vec![
                    XNNPACKExecutionProvider::default()
                        .with_intra_op_num_threads(
                            NonZeroUsize::new(XNNPACK_THREAD_COUNT).expect("non-zero constant"),
                        )
                        .build(),
                    NNAPIExecutionProvider::default()
                        .with_disable_cpu(true)
                        .with_fp16(options.speed_profile.nnapi_use_fp16())
                        .build(),
                    CPUExecutionProvider::default().build(),
                ];

                let provider_summary = format!(
                    "mode=auto, profile={}, requested=[XNNPACK(available={},threads={}), NNAPI(available={},disable_cpu=true,fp16={}), CPU(available={})]",
                    options.speed_profile.as_id(),
                    xnn_available,
                    XNNPACK_THREAD_COUNT,
                    nnapi_available,
                    options.speed_profile.nnapi_use_fp16(),
                    cpu_available
                );

                Self {
                    providers,
                    provider_summary,
                    intra_threads: 1,
                    inter_threads: 1,
                    parallel_execution: false,
                }
            }
            RuntimeAcceleratorMode::Cpu => {
                let (intra_threads, inter_threads, parallel_execution) =
                    options.speed_profile.cpu_threads();
                let cpu_available = CPUExecutionProvider::default().is_available().unwrap_or(true);
                let provider_summary = format!(
                    "mode=cpu, profile={}, requested=[CPU(available={})]",
                    options.speed_profile.as_id(),
                    cpu_available
                );

                Self {
                    providers: vec![CPUExecutionProvider::default().build()],
                    provider_summary,
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
    fn from_yaml(content: &str) -> Self {
        let mut config = Self::default();

        for raw_line in content.lines() {
            let line = raw_line.split('#').next().unwrap_or("").trim();
            if line.is_empty() {
                continue;
            }

            if let Some(value) = line.strip_prefix("sample_rate:") {
                if let Ok(parsed) = value.trim().parse::<usize>() {
                    config.sample_rate = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("features:") {
                if let Ok(parsed) = value.trim().parse::<usize>() {
                    config.n_mels = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("win_length:") {
                if let Ok(parsed) = value.trim().parse::<usize>() {
                    config.win_length = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("hop_length:") {
                if let Ok(parsed) = value.trim().parse::<usize>() {
                    config.hop_length = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("n_fft:") {
                if let Ok(parsed) = value.trim().parse::<usize>() {
                    config.n_fft = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("center:") {
                if let Ok(parsed) = value.trim().parse::<bool>() {
                    config.center = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("mel_scale:") {
                config.mel_scale = value.trim().to_string();
                continue;
            }

            if let Some(value) = line.strip_prefix("subsampling_factor:") {
                if let Ok(parsed) = value.trim().parse::<usize>() {
                    config.subsampling_factor = parsed;
                }
                continue;
            }

            if let Some(value) = line.strip_prefix("model_name:") {
                config.model_name = value.trim().to_string();
            }
        }

        config
    }
}

struct GigaamFrontend {
    n_mels: usize,
    win_length: usize,
    hop_length: usize,
    n_fft: usize,
    center: bool,
    hann_window: Vec<f32>,
    mel_filterbank: Vec<f32>, // [n_freq_bins, n_mels]
    fft: Arc<dyn Fft<f32>>,
}

impl GigaamFrontend {
    fn from_config(config: &GigaamConfig) -> Result<Self> {
        if config.hop_length == 0 {
            return Err(anyhow::anyhow!("Invalid GigaAM config: hop_length must be > 0"));
        }
        if config.win_length == 0 {
            return Err(anyhow::anyhow!("Invalid GigaAM config: win_length must be > 0"));
        }
        if config.n_fft == 0 {
            return Err(anyhow::anyhow!("Invalid GigaAM config: n_fft must be > 0"));
        }
        if config.n_fft < config.win_length {
            return Err(anyhow::anyhow!(
                "Invalid GigaAM config: n_fft ({}) < win_length ({})",
                config.n_fft,
                config.win_length
            ));
        }
        if !config.mel_scale.eq_ignore_ascii_case("htk") {
            return Err(anyhow::anyhow!(
                "Unsupported GigaAM mel_scale '{}'; expected 'htk'",
                config.mel_scale
            ));
        }
        if config.center {
            return Err(anyhow::anyhow!(
                "Unsupported GigaAM config: center=true is not supported for this model"
            ));
        }

        let quantize_bf16 = config.model_name.contains("v3") || (!config.center && config.n_fft == 320);
        let hann_window = build_hann_window(config.win_length, quantize_bf16);
        let mel_filterbank = build_mel_filterbank(
            config.sample_rate,
            config.n_fft,
            config.n_mels,
            quantize_bf16,
        )?;

        let mut planner = FftPlanner::<f32>::new();
        let fft = planner.plan_fft_forward(config.n_fft);

        Ok(Self {
            n_mels: config.n_mels,
            win_length: config.win_length,
            hop_length: config.hop_length,
            n_fft: config.n_fft,
            center: config.center,
            hann_window,
            mel_filterbank,
            fft,
        })
    }

    fn extract_features(&self, samples: &[f32]) -> Result<(Array3<f32>, i64)> {
        if samples.is_empty() {
            return Ok((Array3::zeros((1, self.n_mels, 0)), 0));
        }

        if self.center {
            return Err(anyhow::anyhow!(
                "Unsupported GigaAM preprocessor configuration: center=true"
            ));
        }

        if samples.len() < self.win_length {
            return Ok((Array3::zeros((1, self.n_mels, 0)), 0));
        }

        let frame_count = ((samples.len() - self.win_length) / self.hop_length) + 1;
        let n_freq_bins = (self.n_fft / 2) + 1;

        let mut features = vec![0.0_f32; self.n_mels * frame_count];
        let mut fft_buffer = vec![Complex32::new(0.0, 0.0); self.n_fft];
        let mut power_spectrum = vec![0.0_f32; n_freq_bins];

        for frame_idx in 0..frame_count {
            let start = frame_idx * self.hop_length;

            for i in 0..self.n_fft {
                let sample = if i < self.win_length {
                    samples[start + i] * self.hann_window[i]
                } else {
                    0.0
                };
                fft_buffer[i] = Complex32::new(sample, 0.0);
            }

            self.fft.process(&mut fft_buffer);

            for (bin_idx, power) in power_spectrum.iter_mut().enumerate() {
                let complex = fft_buffer[bin_idx];
                *power = complex.re.mul_add(complex.re, complex.im * complex.im);
            }

            for mel_idx in 0..self.n_mels {
                let mut mel_energy = 0.0_f32;
                for (bin_idx, &power) in power_spectrum.iter().enumerate() {
                    mel_energy += power * self.mel_filterbank[bin_idx * self.n_mels + mel_idx];
                }
                let clamped = mel_energy.clamp(MEL_MIN_CLAMP, MEL_MAX_CLAMP);
                features[mel_idx * frame_count + frame_idx] = clamped.ln();
            }
        }

        let features = Array3::from_shape_vec((1, self.n_mels, frame_count), features)?;
        Ok((features, frame_count as i64))
    }
}

struct GigaamModel {
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
    fn new(model_dir: &Path, runtime_options: RuntimeOptions) -> Result<Self> {
        let model_path = MODEL_FILENAMES
            .iter()
            .map(|filename| model_dir.join(filename))
            .find(|path| path.exists())
            .ok_or_else(|| {
                anyhow::anyhow!(
                    "Missing GigaAM model file in {}. Expected one of: {}",
                    model_dir.display(),
                    MODEL_FILENAMES.join(", ")
                )
            })?;
        let vocab_path = model_dir.join(VOCAB_FILENAME);
        let config_path = model_dir.join(CONFIG_FILENAME);

        if !vocab_path.exists() {
            return Err(anyhow::anyhow!(
                "Missing GigaAM vocab file: {}",
                vocab_path.display()
            ));
        }
        if !config_path.exists() {
            return Err(anyhow::anyhow!(
                "Missing GigaAM config file: {}",
                config_path.display()
            ));
        }

        let vocab_content = fs::read_to_string(&vocab_path).with_context(|| {
            format!("Failed to read GigaAM vocab file: {}", vocab_path.display())
        })?;
        let (vocab, blank_idx) = parse_vocab_content(&vocab_content)?;

        let config_content = fs::read_to_string(&config_path).with_context(|| {
            format!("Failed to read GigaAM config file: {}", config_path.display())
        })?;
        let config = GigaamConfig::from_yaml(&config_content);
        if config.sample_rate != 16_000 {
            return Err(anyhow::anyhow!(
                "Unsupported GigaAM sample rate {} Hz; Handy currently provides 16000 Hz PCM input",
                config.sample_rate
            ));
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

        log::info!(
            "GigaAM runtime plan: {}, intra_threads={}, inter_threads={}, parallel_execution={}",
            runtime_plan.provider_summary,
            runtime_plan.intra_threads,
            runtime_plan.inter_threads,
            runtime_plan.parallel_execution
        );

        for input in &session.inputs {
            log::info!(
                "GigaAM input: name={}, type={:?}",
                input.name,
                input.input_type
            );
        }
        for output in &session.outputs {
            log::info!("GigaAM output: name={}", output.name);
        }

        let features_input_name = session
            .inputs
            .iter()
            .find(|input| input.name == "features")
            .or_else(|| {
                session.inputs.iter().find(|input| {
                    input
                        .input_type
                        .tensor_shape()
                        .map(|shape| shape.len() == 3)
                        .unwrap_or(false)
                })
            })
            .map(|input| input.name.clone())
            .ok_or_else(|| anyhow::anyhow!("Failed to determine GigaAM features input"))?;

        let feature_lengths_input_name = session
            .inputs
            .iter()
            .find(|input| input.name == "feature_lengths")
            .or_else(|| {
                session.inputs.iter().find(|input| {
                    input
                        .input_type
                        .tensor_shape()
                        .map(|shape| shape.len() == 1)
                        .unwrap_or(false)
                })
            })
            .map(|input| input.name.clone())
            .ok_or_else(|| anyhow::anyhow!("Failed to determine GigaAM feature lengths input"))?;

        let logits_output_name = session
            .outputs
            .iter()
            .find(|output| output.name == "log_probs" || output.name == "logits")
            .or_else(|| session.outputs.first())
            .map(|output| output.name.clone())
            .ok_or_else(|| anyhow::anyhow!("Failed to determine GigaAM logits output"))?;

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

    fn transcribe_samples(&mut self, samples: &[f32]) -> Result<NativeTranscriptionReport> {
        let total_start = Instant::now();

        let feature_start = Instant::now();
        let (features, feature_length) = self.frontend.extract_features(samples)?;
        let feature_extraction_ms = feature_start.elapsed().as_millis();
        if feature_length == 0 {
            return Ok(NativeTranscriptionReport {
                text: String::new(),
                timings: NativeTranscriptionTimings {
                    feature_extraction_ms,
                    ort_run_ms: 0,
                    decode_ms: 0,
                    total_ms: total_start.elapsed().as_millis(),
                },
                provider_summary: self.provider_summary.clone(),
            });
        }

        let feature_lengths = Array1::from_vec(vec![feature_length]);
        let inputs = inputs![
            self.features_input_name.as_str() => TensorRef::from_array_view(features.view())?,
            self.feature_lengths_input_name.as_str() => TensorRef::from_array_view(feature_lengths.view())?,
        ];

        let ort_start = Instant::now();
        let outputs = self.session.run(inputs)?;
        let ort_run_ms = ort_start.elapsed().as_millis();

        let decode_start = Instant::now();
        let logits = outputs
            .get(self.logits_output_name.as_str())
            .ok_or_else(|| {
                anyhow::anyhow!(
                    "GigaAM output '{}' not found in inference outputs",
                    self.logits_output_name
                )
            })?
            .try_extract_array::<f32>()?
            .to_owned()
            .into_dimensionality::<Ix3>()?;

        let encoded_len = ((feature_length - 1) / self.subsampling_factor as i64 + 1).max(0) as usize;
        let token_ids = ctc_greedy_decode_ids(logits.view(), encoded_len, self.blank_idx);
        let text = decode_token_ids_to_text(&token_ids, &self.vocab);
        let decode_ms = decode_start.elapsed().as_millis();

        Ok(NativeTranscriptionReport {
            text,
            timings: NativeTranscriptionTimings {
                feature_extraction_ms,
                ort_run_ms,
                decode_ms,
                total_ms: total_start.elapsed().as_millis(),
            },
            provider_summary: self.provider_summary.clone(),
        })
    }
}

#[derive(Default)]
pub struct GigaamEngine {
    loaded_model_path: Option<PathBuf>,
    model: Option<GigaamModel>,
}

impl GigaamEngine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn load_model(&mut self, model_path: &Path, runtime_options: RuntimeOptions) -> Result<()> {
        let model = GigaamModel::new(model_path, runtime_options)?;
        self.model = Some(model);
        self.loaded_model_path = Some(model_path.to_path_buf());
        Ok(())
    }

    pub fn unload_model(&mut self) {
        self.loaded_model_path = None;
        self.model = None;
    }

    pub fn transcribe_samples(&mut self, samples: &[f32]) -> Result<NativeTranscriptionReport> {
        let model = self
            .model
            .as_mut()
            .ok_or_else(|| anyhow::anyhow!("GigaAM model is not loaded"))?;
        model.transcribe_samples(samples)
    }
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
            .ok_or_else(|| anyhow::anyhow!("Invalid vocab line: '{}'", line))?;
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

    let blank_idx =
        blank_idx.ok_or_else(|| anyhow::anyhow!("Missing <blk> token in vocabulary file"))?;

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
    let time_steps = logits.shape()[1];
    let usable_steps = encoded_len.min(time_steps);

    let mut token_ids = Vec::with_capacity(usable_steps);
    let mut prev_token = blank_idx;

    for frame_idx in 0..usable_steps {
        let frame = logits.slice(s![0, frame_idx, ..]);
        let best_idx = frame
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(Ordering::Equal))
            .map(|(idx, _)| idx)
            .unwrap_or(blank_idx);

        if best_idx != blank_idx && best_idx != prev_token {
            token_ids.push(best_idx);
        }
        prev_token = best_idx;
    }

    token_ids
}

fn decode_token_ids_to_text(token_ids: &[usize], vocab: &[String]) -> String {
    let concatenated = token_ids
        .iter()
        .filter_map(|&id| vocab.get(id))
        .fold(String::new(), |mut text, token| {
            text.push_str(token);
            text
        });

    DECODE_SPACE_RE
        .replace_all(&concatenated, |captures: &regex::Captures<'_>| {
            if captures.get(1).is_some() {
                " "
            } else {
                ""
            }
        })
        .to_string()
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

fn build_mel_filterbank(
    sample_rate: usize,
    n_fft: usize,
    n_mels: usize,
    quantize_bf16: bool,
) -> Result<Vec<f32>> {
    let n_freq_bins = n_fft / 2 + 1;
    let f_min = 0.0_f32;
    let f_max = (sample_rate as f32) / 2.0;

    let mel_min = hz_to_mel_htk(f_min);
    let mel_max = hz_to_mel_htk(f_max);

    let mel_points: Vec<f32> = (0..(n_mels + 2))
        .map(|i| mel_min + (mel_max - mel_min) * (i as f32 / (n_mels + 1) as f32))
        .collect();
    let hz_points: Vec<f32> = mel_points.into_iter().map(mel_to_hz_htk).collect();
    let fft_freqs: Vec<f32> = (0..n_freq_bins)
        .map(|bin| bin as f32 * sample_rate as f32 / n_fft as f32)
        .collect();

    let mut filterbank = vec![0.0_f32; n_freq_bins * n_mels];

    for mel_idx in 0..n_mels {
        let left = hz_points[mel_idx];
        let center = hz_points[mel_idx + 1];
        let right = hz_points[mel_idx + 2];

        if center <= left || right <= center {
            return Err(anyhow::anyhow!(
                "Invalid mel filter points for index {}",
                mel_idx
            ));
        }

        for (bin_idx, &freq) in fft_freqs.iter().enumerate() {
            let weight = if freq >= left && freq <= center {
                (freq - left) / (center - left)
            } else if freq > center && freq <= right {
                (right - freq) / (right - center)
            } else {
                0.0
            };

            if weight > 0.0 {
                filterbank[bin_idx * n_mels + mel_idx] = if quantize_bf16 {
                    quantize_to_bf16(weight)
                } else {
                    weight
                };
            }
        }
    }

    Ok(filterbank)
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
    #[ignore = "Requires local model files and a WAV fixture; set GIGAAM_TEST_MODEL_DIR and GIGAAM_TEST_WAV_PATH"]
    fn integration_transcribes_wav_fixture() -> Result<()> {
        let model_dir = std::env::var("GIGAAM_TEST_MODEL_DIR")
            .context("GIGAAM_TEST_MODEL_DIR is required for integration test")?;
        let wav_path = std::env::var("GIGAAM_TEST_WAV_PATH")
            .context("GIGAAM_TEST_WAV_PATH is required for integration test")?;
        let expected_substring = std::env::var("GIGAAM_TEST_EXPECT_CONTAINS").unwrap_or_default();

        let mut engine = GigaamEngine::new();
        engine.load_model(Path::new(&model_dir), RuntimeOptions::default())?;

        let samples = read_wav_mono_f32(Path::new(&wav_path))?;
        let report = engine.transcribe_samples(&samples)?;
        let text = report.text;
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

        let mut interleaved = Vec::<f32>::new();
        match spec.sample_format {
            hound::SampleFormat::Float => {
                for sample in reader.samples::<f32>() {
                    interleaved.push(sample?);
                }
            }
            hound::SampleFormat::Int => {
                let max_amplitude = (1_i64 << (spec.bits_per_sample.saturating_sub(1))) as f32;
                for sample in reader.samples::<i32>() {
                    interleaved.push(sample? as f32 / max_amplitude);
                }
            }
        }

        if channels == 1 {
            return Ok(interleaved);
        }

        let mut mono = Vec::with_capacity(interleaved.len() / channels);
        for frame in interleaved.chunks(channels) {
            let sum: f32 = frame.iter().copied().sum();
            mono.push(sum / channels as f32);
        }
        Ok(mono)
    }
}
