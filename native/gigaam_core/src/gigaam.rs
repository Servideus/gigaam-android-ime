use anyhow::{anyhow, bail, Context, Result};
use ndarray::{s, Array1, Array2, Array3, ArrayView3};
use ort::inputs;
use ort::session::builder::GraphOptimizationLevel;
use ort::session::{Session, SessionOutputs};
use ort::value::TensorRef;
use regex::Regex;
use rustfft::{num_complex::Complex32, Fft, FftPlanner};
use std::f32::consts::PI;
use std::fs;
use std::path::Path;
use std::sync::{Arc, LazyLock};
use std::time::Instant;

const ENCODER_FILENAME: &str = "v3_e2e_rnnt_encoder.int8.onnx";
const DECODER_FILENAME: &str = "v3_e2e_rnnt_decoder.int8.onnx";
const JOINT_FILENAME: &str = "v3_e2e_rnnt_joint.int8.onnx";
const VOCAB_FILENAME: &str = "v3_e2e_rnnt_vocab.txt";
const CONFIG_FILENAME: &str = "v3_e2e_rnnt.yaml";

const SAMPLE_RATE: usize = 16_000;
/// Width of the RNN-T prediction network state (`pred_hidden` in the model config).
const PRED_HIDDEN: usize = 320;
/// Greedy RNN-T emits at most this many tokens per encoder frame (the onnx-asr default).
const MAX_TOKENS_PER_STEP: usize = 3;

/// One encoder pass over a long recording drops text (182 s came back empty), so longer audio
/// is cut at its quietest moments into pieces of SEGMENT_MIN..=SEGMENT_MAX seconds.
const SEGMENT_MAX_SECONDS: usize = 20;
const SEGMENT_MIN_SECONDS: usize = 10;
/// Silence is judged by the mean energy over this many 10 ms frames.
const PAUSE_WINDOW_FRAMES: usize = 30;

const MEL_MIN_CLAMP: f32 = 1e-9;
const MEL_MAX_CLAMP: f32 = 1e9;

/// ONNX Runtime's plain CPU provider with 6 threads was the fastest setup measured on a Helio G99
/// phone (2 fast + 6 slow cores). XNNPACK + NNAPI was about twice as slow, and 2, 4 or 8 threads
/// were no faster; see docs/verification.md.
const ENCODER_THREADS: usize = 6;

static DECODE_SPACE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\A\s|\s\B|(\s)\b").expect("valid decode spacing regex"));

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
}

impl NativeTranscriptionReport {
    /// JSON object members without the surrounding braces, so callers can add their own fields.
    pub fn json_fields(&self) -> String {
        format!(
            "\"feature_extraction_ms\":{},\"ort_run_ms\":{},\"decode_ms\":{},\"total_ms\":{}",
            self.timings.feature_extraction_ms,
            self.timings.ort_run_ms,
            self.timings.decode_ms,
            self.timings.total_ms
        )
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
    model_name: String,
}

impl Default for GigaamConfig {
    fn default() -> Self {
        Self {
            sample_rate: SAMPLE_RATE,
            n_mels: 64,
            win_length: 320,
            hop_length: 160,
            n_fft: 320,
            center: false,
            mel_scale: "htk".to_string(),
            model_name: "v3_e2e_rnnt".to_string(),
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

/// LSTM state (h, c) of the RNN-T prediction network.
type PredictorState = [Vec<f32>; 2];

pub struct GigaamModel {
    encoder: Session,
    decoder: Session,
    joint: Session,
    frontend: GigaamFrontend,
    vocab: Vec<String>,
    blank_idx: usize,
}

impl GigaamModel {
    pub fn load(model_dir: &Path) -> Result<Self> {
        let vocab_content = read_model_file(&model_dir.join(VOCAB_FILENAME), "vocab")?;
        let (vocab, blank_idx) = parse_vocab_content(&vocab_content)?;
        let config = GigaamConfig::from_yaml(&read_model_file(&model_dir.join(CONFIG_FILENAME), "config")?);
        if config.sample_rate != SAMPLE_RATE {
            bail!(
                "Unsupported GigaAM sample rate {} Hz; the IME provides {SAMPLE_RATE} Hz PCM input",
                config.sample_rate
            );
        }
        let frontend = GigaamFrontend::from_config(&config)?;

        Ok(Self {
            encoder: load_cpu_session(model_dir, ENCODER_FILENAME, ENCODER_THREADS)?,
            decoder: load_cpu_session(model_dir, DECODER_FILENAME, 1)?,
            joint: load_cpu_session(model_dir, JOINT_FILENAME, 1)?,
            frontend,
            vocab,
            blank_idx,
        })
    }

    pub fn transcribe_samples(&mut self, samples: &[f32]) -> Result<NativeTranscriptionReport> {
        let total_start = Instant::now();
        let mut timings = NativeTranscriptionTimings::default();

        let mut pieces = Vec::new();
        for bounds in segment_boundaries(samples).windows(2) {
            let text = self.transcribe_segment(&samples[bounds[0]..bounds[1]], &mut timings)?;
            if !text.is_empty() {
                pieces.push(text);
            }
        }

        timings.total_ms = total_start.elapsed().as_millis();
        Ok(NativeTranscriptionReport {
            text: join_pieces(&pieces),
            timings,
        })
    }

    /// Encodes one piece of at most SEGMENT_MAX_SECONDS and decodes it greedily.
    fn transcribe_segment(
        &mut self,
        samples: &[f32],
        timings: &mut NativeTranscriptionTimings,
    ) -> Result<String> {
        let feature_start = Instant::now();
        let (features, feature_length) = self.frontend.extract_features(samples)?;
        timings.feature_extraction_ms += feature_start.elapsed().as_millis();
        if feature_length == 0 {
            return Ok(String::new());
        }

        let ort_start = Instant::now();
        let feature_lengths = Array1::from_vec(vec![feature_length]);
        let outputs = self.encoder.run(inputs![
            "audio_signal" => TensorRef::from_array_view(features.view())?,
            "length" => TensorRef::from_array_view(feature_lengths.view())?,
        ])?;
        let (shape, encoded) = output_tensor::<f32>(&outputs, "encoded")?;
        let (_, encoded_len) = output_tensor::<i32>(&outputs, "encoded_len")?;
        let (dim, frames) = match shape[..] {
            [1, dim, frames] => (dim as usize, frames as usize),
            _ => bail!("Unexpected GigaAM encoder output shape {shape:?}"),
        };
        let used_frames = encoded_len.first().map_or(0, |&len| len.max(0) as usize).min(frames);
        // [1, dim, frames] -> frame-major, so each frame is one contiguous slice for the joint.
        let frame_major: Vec<f32> = ArrayView3::from_shape((1, dim, frames), encoded)?
            .slice(s![0, .., ..used_frames])
            .t()
            .iter()
            .copied()
            .collect();
        drop(outputs);
        timings.ort_run_ms += ort_start.elapsed().as_millis();

        let decode_start = Instant::now();
        let token_ids = self.greedy_decode(&frame_major, dim)?;
        let text = decode_token_ids_to_text(&token_ids, &self.vocab);
        timings.decode_ms += decode_start.elapsed().as_millis();
        Ok(text)
    }

    /// Greedy RNN-T decoding, step for step as onnx-asr `_AsrWithTransducerDecoding`.
    fn greedy_decode(&mut self, frame_major: &[f32], dim: usize) -> Result<Vec<usize>> {
        let frame_count = frame_major.len() / dim.max(1);
        let mut token_ids = Vec::new();
        // `state` is the predictor state before the last emitted token; the decoder output and
        // the state after that token are computed once and reused until the next emission.
        let mut state: PredictorState = [vec![0.0; PRED_HIDDEN], vec![0.0; PRED_HIDDEN]];
        let (mut dec, mut next_state) = run_decoder(&mut self.decoder, self.blank_idx, &state)?;

        let (mut frame, mut emitted) = (0, 0);
        while frame < frame_count {
            let token = run_joint(&mut self.joint, &frame_major[frame * dim..][..dim], &dec)?;
            if token != self.blank_idx {
                token_ids.push(token);
                emitted += 1;
                state = next_state;
                (dec, next_state) = run_decoder(&mut self.decoder, token, &state)?;
            }
            if token == self.blank_idx || emitted == MAX_TOKENS_PER_STEP {
                frame += 1;
                emitted = 0;
            }
        }
        Ok(token_ids)
    }
}

/// A session on ONNX Runtime's default CPU provider. The decoder and joint run once per token
/// or frame on tiny inputs, where a thread pool costs more than the math, so they get 1 thread.
fn load_cpu_session(model_dir: &Path, filename: &str, threads: usize) -> Result<Session> {
    let path = existing_model_file(model_dir, filename)?;
    Session::builder()?
        .with_optimization_level(GraphOptimizationLevel::Level3)?
        .with_intra_threads(threads)?
        .with_inter_threads(1)?
        .commit_from_file(&path)
        .with_context(|| format!("Failed to initialize ONNX Runtime session: {}", path.display()))
}

fn existing_model_file(model_dir: &Path, filename: &str) -> Result<std::path::PathBuf> {
    let path = model_dir.join(filename);
    if !path.exists() {
        bail!("Missing GigaAM model file: {}", path.display());
    }
    Ok(path)
}

fn output_tensor<'o, T: ort::tensor::PrimitiveTensorElementType>(
    outputs: &'o SessionOutputs<'_>,
    name: &str,
) -> Result<(&'o ort::tensor::Shape, &'o [T])> {
    Ok(outputs
        .get(name)
        .ok_or_else(|| anyhow!("GigaAM output '{name}' not found in inference outputs"))?
        .try_extract_tensor::<T>()?)
}

/// Feeds `token` to the prediction network; returns its output and the state after the token.
fn run_decoder(
    session: &mut Session,
    token: usize,
    state: &PredictorState,
) -> Result<(Vec<f32>, PredictorState)> {
    let token = Array2::from_elem((1, 1), token as i64);
    let shape = (1, 1, PRED_HIDDEN);
    let outputs = session.run(inputs![
        "x" => TensorRef::from_array_view(token.view())?,
        "h.1" => TensorRef::from_array_view(ArrayView3::from_shape(shape, &state[0][..])?)?,
        "c.1" => TensorRef::from_array_view(ArrayView3::from_shape(shape, &state[1][..])?)?,
    ])?;
    let take = |name: &str| output_tensor::<f32>(&outputs, name).map(|(_, values)| values.to_vec());
    Ok((take("dec")?, [take("h")?, take("c")?]))
}

/// Scores every token for one encoder frame and returns the best one.
fn run_joint(session: &mut Session, frame: &[f32], dec: &[f32]) -> Result<usize> {
    let outputs = session.run(inputs![
        "enc" => TensorRef::from_array_view(ArrayView3::from_shape((1, frame.len(), 1), frame)?)?,
        "dec" => TensorRef::from_array_view(ArrayView3::from_shape((1, dec.len(), 1), dec)?)?,
    ])?;
    let (_, logits) = output_tensor::<f32>(&outputs, "joint")?;
    logits
        .iter()
        .enumerate()
        .max_by(|(_, a), (_, b)| a.total_cmp(b))
        .map(|(index, _)| index)
        .ok_or_else(|| anyhow!("GigaAM joint returned no scores"))
}

/// Joins piece texts with spaces. The model starts every piece with a capital letter, also where
/// the cut fell inside a sentence; when the previous piece ends without . ! ? or …, that capital
/// becomes lowercase. A word whose second letter is also uppercase (an acronym) stays as it is.
/// GigaamImeService.joinPieces applies the same rule to pieces transcribed in separate calls.
fn join_pieces(pieces: &[String]) -> String {
    let mut joined = String::new();
    for piece in pieces.iter().map(|piece| piece.trim()).filter(|piece| !piece.is_empty()) {
        if joined.is_empty() {
            joined.push_str(piece);
            continue;
        }
        let continues_sentence = !joined.ends_with(['.', '!', '?', '…']);
        joined.push(' ');
        let mut chars = piece.chars();
        match (chars.next(), chars.next()) {
            (Some(first), second)
                if continues_sentence && first.is_uppercase() && !second.is_some_and(char::is_uppercase) =>
            {
                joined.extend(first.to_lowercase());
                joined.push_str(&piece[first.len_utf8()..]);
            }
            _ => joined.push_str(piece),
        }
    }
    joined
}

/// End of the first piece that [`segment_boundaries`] cuts from every recording that starts with
/// `samples`, so it can be transcribed while recording goes on. None while that cut still depends
/// on audio not yet recorded (less than SEGMENT_MAX + SEGMENT_MIN seconds).
pub fn settled_first_piece(samples: &[f32]) -> Option<usize> {
    if samples.len() < (SEGMENT_MAX_SECONDS + SEGMENT_MIN_SECONDS) * SAMPLE_RATE {
        return None;
    }
    segment_boundaries(samples).get(1).copied()
}

/// Sample positions `[0, cuts.., len]`. Audio up to SEGMENT_MAX_SECONDS stays whole; longer
/// audio is cut where the mean energy over PAUSE_WINDOW_FRAMES is lowest, so every piece lasts
/// SEGMENT_MIN..=SEGMENT_MAX seconds.
fn segment_boundaries(samples: &[f32]) -> Vec<usize> {
    const HOP: usize = SAMPLE_RATE / 100;
    let (max_len, min_len) = (SEGMENT_MAX_SECONDS * SAMPLE_RATE, SEGMENT_MIN_SECONDS * SAMPLE_RATE);
    let mut bounds = vec![0];
    if samples.len() > max_len {
        // Prefix sums of 10 ms frame energies give any window's mean in O(1).
        let mut prefix = vec![0.0_f64];
        for frame in samples.chunks_exact(HOP) {
            let energy: f64 = frame.iter().map(|&x| f64::from(x) * f64::from(x)).sum();
            prefix.push(prefix[prefix.len() - 1] + energy / HOP as f64);
        }
        let frame_count = prefix.len() - 1;
        let window_mean = |center: usize| {
            let from = center.saturating_sub(PAUSE_WINDOW_FRAMES / 2);
            let to = (center + PAUSE_WINDOW_FRAMES / 2).min(frame_count);
            (prefix[to] - prefix[from]) / (to - from).max(1) as f64
        };

        let mut start = 0;
        while samples.len() - start > max_len {
            // The rest after a cut must also last SEGMENT_MIN, so no piece is a sliver.
            let first = (start + min_len) / HOP;
            let last = (start + max_len).min(samples.len() - min_len) / HOP;
            let cut = (first..last)
                .min_by(|&a, &b| window_mean(a).total_cmp(&window_mean(b)))
                .unwrap_or(first);
            start = cut * HOP;
            bounds.push(start);
        }
    }
    bounds.push(samples.len());
    bounds
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
    fn pieces_cut_inside_a_sentence_join_in_lowercase() {
        let join = |pieces: &[&str]| join_pieces(&pieces.iter().map(|p| p.to_string()).collect::<Vec<_>>());
        // Cases from a real 182 s recording, cut inside sentences.
        assert_eq!(join(&["Я сначала подумал на рис", "Он начал есть."]), "Я сначала подумал на рис он начал есть.");
        assert_eq!(join(&["Интересно, что", "Утром показатели выросли."]), "Интересно, что утром показатели выросли.");
        assert_eq!(join(&["И я три раза", "Я промываю"]), "И я три раза я промываю");
        // Sentence ends, acronyms, empty pieces and spaces.
        assert_eq!(join(&["Готово.", "Новая фраза"]), "Готово. Новая фраза");
        assert_eq!(join(&["Правда?", "Да"]), "Правда? Да");
        assert_eq!(join(&["данные от", "ВОЗ пришли"]), "данные от ВОЗ пришли");
        assert_eq!(join(&[" Первый ", "", "  ", "Второй"]), "Первый второй");
        assert_eq!(join(&["Цена", "15 рублей"]), "Цена 15 рублей");
        assert_eq!(join(&[]), "");
    }

    #[test]
    fn short_audio_stays_whole() {
        let samples = vec![0.1_f32; SEGMENT_MAX_SECONDS * SAMPLE_RATE];
        assert_eq!(segment_boundaries(&samples), vec![0, samples.len()]);
        assert_eq!(segment_boundaries(&[]), vec![0, 0]);
    }

    #[test]
    fn long_audio_is_cut_in_pauses_within_length_limits() {
        // 65 s of "speech" with 0.5 s pauses at 14 s, 31 s and 47 s, and a fainter dip at 25 s.
        let mut samples = vec![0.3_f32; 65 * SAMPLE_RATE];
        let mut quiet = |at: f32, level: f32| {
            let from = (at * SAMPLE_RATE as f32) as usize;
            samples[from..from + SAMPLE_RATE / 2].fill(level);
        };
        quiet(14.0, 0.0);
        quiet(25.0, 0.1);
        quiet(31.0, 0.0);
        quiet(47.0, 0.0);

        let bounds = segment_boundaries(&samples);
        let seconds: Vec<f32> = bounds.iter().map(|&b| b as f32 / SAMPLE_RATE as f32).collect();
        assert_eq!(bounds.len(), 5, "cuts at {seconds:?}");
        for (cut, pause) in seconds[1..4].iter().zip([14.0, 31.0, 47.0]) {
            assert!((cut - pause).abs() < 0.5, "cut {cut} s misses the pause at {pause} s");
        }
        for piece in bounds.windows(2) {
            let length = piece[1] - piece[0];
            assert!(length >= SEGMENT_MIN_SECONDS * SAMPLE_RATE - SAMPLE_RATE / 100);
            assert!(length <= SEGMENT_MAX_SECONDS * SAMPLE_RATE);
        }
    }

    #[test]
    fn pieces_taken_while_recording_match_cuts_of_whole_recording() {
        // 3 minutes of speech-like energy with a pause every 7.3 s and a dip every 4.1 s.
        let samples: Vec<f32> = (0..180 * SAMPLE_RATE)
            .map(|n| {
                let t = n as f32 / SAMPLE_RATE as f32;
                let pause = t % 7.3 < 0.4;
                let dip = t % 4.1 < 0.3;
                let level = if pause { 0.0 } else if dip { 0.1 } else { 0.3 };
                level * (1.0 + 0.5 * (t * 997.0).sin())
            })
            .collect();
        let whole = segment_boundaries(&samples);

        // Recording grows by 0.25 s; each settled piece is taken as soon as it is settled.
        let mut streamed = vec![0];
        let mut recorded = 0;
        while recorded < samples.len() {
            recorded = (recorded + SAMPLE_RATE / 4).min(samples.len());
            let start = streamed[streamed.len() - 1];
            if let Some(end) = settled_first_piece(&samples[start..recorded]) {
                streamed.push(start + end);
            }
        }
        // After stop, the rest is cut as one recording.
        let start = streamed[streamed.len() - 1];
        streamed.extend(segment_boundaries(&samples[start..]).iter().skip(1).map(|b| start + b));

        assert_eq!(streamed, whole);
        assert!(settled_first_piece(&samples[..29 * SAMPLE_RATE]).is_none());
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
            "model_name: v3_e2e_rnnt\npreprocessor:\n  sample_rate: 16000 # Hz\n  features: 64\n  \
             win_length: 320\n  hop_length: 160\n  n_fft: 320\n  center: false\n  mel_scale: htk\n\
             encoder:\n  subsampling_factor: 4\n  n_fft_typo: 999\n",
        );
        assert_eq!(config.sample_rate, 16_000);
        assert_eq!(config.n_mels, 64);
        assert_eq!(config.n_fft, 320);
        assert!(!config.center);
        assert_eq!(config.mel_scale, "htk");
        assert_eq!(config.model_name, "v3_e2e_rnnt");
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

        let mut model = GigaamModel::load(Path::new(&model_dir))?;
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
