mod gigaam;

use crate::gigaam::{GigaamModel, RuntimeOptions};
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;

const MODEL_IDS: [&str; 1] = ["gigaam-v3-e2e-rnnt-int8"];
const TARGET_SAMPLE_RATE: usize = 16_000;

struct EngineCache {
    runtime_options: RuntimeOptions,
    /// The loaded model and its directory; always built with `runtime_options`.
    loaded: Option<(PathBuf, GigaamModel)>,
    last_profile_summary: String,
}

static ENGINE_CACHE: Mutex<EngineCache> = Mutex::new(EngineCache {
    runtime_options: RuntimeOptions::DEFAULT,
    loaded: None,
    last_profile_summary: String::new(),
});

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeTranscribe(
    mut env: JNIEnv,
    _class: JClass,
    models_root_dir: JString,
    model_id: JString,
    pcm16: JShortArray,
    sample_rate: jint,
) -> jstring {
    let result = transcribe_from_jni_inputs(&mut env, models_root_dir, model_id, pcm16, sample_rate);
    match result {
        Ok(text) => new_java_string(&mut env, text),
        Err(error) => new_java_string(&mut env, format!("GigaAM error: {error}")),
    }
}

/// Length, in input samples, of the first piece of a still-running recording that can already be
/// transcribed on its own; 0 while none is settled or on error. Needs no model and no engine lock.
#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeFindSettledPiece(
    mut env: JNIEnv,
    _class: JClass,
    pcm16: JShortArray,
    sample_rate: jint,
) -> jint {
    let piece = read_pcm_from_jni(&mut env, &pcm16, sample_rate).ok().and_then(|(samples, rate)| {
        let resampled = resample_linear(samples, rate, TARGET_SAMPLE_RATE);
        gigaam::settled_first_piece(&resampled).map(|end| end * rate / TARGET_SAMPLE_RATE)
    });
    piece.and_then(|end| jint::try_from(end).ok()).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeWarmup(
    mut env: JNIEnv,
    _class: JClass,
    models_root_dir: JString,
    model_id: JString,
) -> jstring {
    let result = warmup_from_jni_inputs(&mut env, models_root_dir, model_id);
    match result {
        Ok(message) => new_java_string(&mut env, message),
        Err(error) => new_java_string(&mut env, format!("error: {error}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeSetRuntimeOptions(
    mut env: JNIEnv,
    _class: JClass,
    speed_profile: JString,
    accelerator_mode: JString,
) -> jstring {
    let result = set_runtime_options_from_jni_inputs(&mut env, speed_profile, accelerator_mode);
    match result {
        Ok(message) => new_java_string(&mut env, message),
        Err(error) => new_java_string(&mut env, format!("error: {error}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeGetLastProfilingSummary(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let summary = match lock_cache() {
        Ok(cache) if cache.last_profile_summary.is_empty() => "{}".to_string(),
        Ok(cache) => cache.last_profile_summary.clone(),
        Err(_) => "{\"error\":\"cache_lock_failed\"}".to_string(),
    };
    new_java_string(&mut env, summary)
}

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeUnload(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut cache) = lock_cache() {
        cache.loaded = None;
        cache.last_profile_summary.clear();
    }
}

fn set_runtime_options_from_jni_inputs(
    env: &mut JNIEnv,
    speed_profile: JString,
    accelerator_mode: JString,
) -> Result<String, String> {
    let speed_profile = jstring_to_rust(env, speed_profile)?;
    let accelerator_mode = jstring_to_rust(env, accelerator_mode)?;
    let options = RuntimeOptions::from_ids(&speed_profile, &accelerator_mode);

    let mut cache = lock_cache()?;
    if cache.runtime_options != options {
        cache.runtime_options = options;
        // The session was built with the old options; free it now and rebuild on next use.
        cache.loaded = None;
    }

    Ok(format!(
        "ok: speed_profile={}, accelerator_mode={}",
        options.speed_profile.as_id(),
        options.accelerator_mode.as_id()
    ))
}

fn warmup_from_jni_inputs(
    env: &mut JNIEnv,
    models_root_dir: JString,
    model_id: JString,
) -> Result<String, String> {
    let model_dir = model_directory_from_jni_inputs(env, models_root_dir, model_id)?;
    let mut cache = lock_cache()?;
    let (model, newly_loaded) = ensure_model_loaded(&mut cache, model_dir)?;
    if !newly_loaded {
        // The session already ran once since it was built; another dummy pass adds nothing.
        return Ok("ok: already loaded".to_string());
    }

    let report = model
        .transcribe_samples(&vec![0.0_f32; TARGET_SAMPLE_RATE / 2])
        .map_err(|e| format!("Warmup failed: {e}"))?;
    cache.last_profile_summary = format!(
        "{{\"warmup\":true,\"pcm_to_f32_ms\":0,\"resample_ms\":0,{}}}",
        report.json_fields()
    );
    Ok("ok".to_string())
}

fn transcribe_from_jni_inputs(
    env: &mut JNIEnv,
    models_root_dir: JString,
    model_id: JString,
    pcm16: JShortArray,
    sample_rate: jint,
) -> Result<String, String> {
    let model_dir = model_directory_from_jni_inputs(env, models_root_dir, model_id)?;

    let pcm_to_f32_start = Instant::now();
    let (samples, source_rate) = read_pcm_from_jni(env, &pcm16, sample_rate)?;
    let pcm_to_f32_ms = pcm_to_f32_start.elapsed().as_millis();

    let resample_start = Instant::now();
    let samples = resample_linear(samples, source_rate, TARGET_SAMPLE_RATE);
    let resample_ms = resample_start.elapsed().as_millis();

    let mut cache = lock_cache()?;
    let (model, _) = ensure_model_loaded(&mut cache, model_dir)?;
    let report = model
        .transcribe_samples(&samples)
        .map_err(|e| format!("Transcription failed: {e}"))?;

    cache.last_profile_summary = format!(
        "{{\"warmup\":false,\"pcm_to_f32_ms\":{pcm_to_f32_ms},\"resample_ms\":{resample_ms},{}}}",
        report.json_fields()
    );
    Ok(report.text)
}

/// Returns the model for `model_dir`, loading it if needed, and whether it was loaded just now.
fn ensure_model_loaded(
    cache: &mut EngineCache,
    model_dir: PathBuf,
) -> Result<(&mut GigaamModel, bool), String> {
    let newly_loaded = !matches!(&cache.loaded, Some((loaded_dir, _)) if *loaded_dir == model_dir);
    if newly_loaded {
        // Free the previous session first so two models never sit in memory together.
        cache.loaded = None;
        let model = GigaamModel::load(&model_dir, cache.runtime_options)
            .map_err(|e| format!("Failed to load model: {e}"))?;
        cache.loaded = Some((model_dir, model));
    }
    let (_, model) = cache.loaded.as_mut().expect("model loaded above");
    Ok((model, newly_loaded))
}

fn lock_cache() -> Result<MutexGuard<'static, EngineCache>, String> {
    ENGINE_CACHE
        .lock()
        .map_err(|_| "Engine cache lock poisoned".to_string())
}

fn model_directory_from_jni_inputs(
    env: &mut JNIEnv,
    models_root_dir: JString,
    model_id: JString,
) -> Result<PathBuf, String> {
    let models_root = jstring_to_rust(env, models_root_dir)?;
    let model_id = jstring_to_rust(env, model_id)?;
    // Only known ids become path components, so the id cannot point outside the models root.
    if !MODEL_IDS.contains(&model_id.as_str()) {
        return Err(format!("Unsupported model id: {model_id}"));
    }
    Ok(Path::new(&models_root).join(model_id))
}

/// Copies Java 16-bit PCM into f32 samples in [-1, 1] and returns them with the sample rate.
fn read_pcm_from_jni(
    env: &mut JNIEnv,
    pcm16: &JShortArray,
    sample_rate: jint,
) -> Result<(Vec<f32>, usize), String> {
    let pcm_len = env
        .get_array_length(pcm16)
        .map_err(|e| format!("Failed to get PCM array length: {e}"))?;
    let mut pcm = vec![0_i16; pcm_len as usize];
    env.get_short_array_region(pcm16, 0, &mut pcm)
        .map_err(|e| format!("Failed to read PCM samples: {e}"))?;
    let source_rate = usize::try_from(sample_rate).map_err(|_| "Invalid sample rate".to_string())?;
    let samples = pcm.iter().map(|&sample| sample as f32 / i16::MAX as f32).collect();
    Ok((samples, source_rate))
}

fn jstring_to_rust(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|e| format!("Failed to decode Java string: {e}"))
}

fn resample_linear(input: Vec<f32>, source_rate: usize, target_rate: usize) -> Vec<f32> {
    if input.is_empty() || source_rate == 0 || target_rate == 0 || source_rate == target_rate {
        return input;
    }

    let ratio = target_rate as f64 / source_rate as f64;
    let output_len = ((input.len() as f64) * ratio).round().max(1.0) as usize;
    let last = input.len() - 1;

    (0..output_len)
        .map(|index| {
            let source_pos = index as f64 / ratio;
            let left = (source_pos.floor() as usize).min(last);
            let right = (left + 1).min(last);
            let fraction = source_pos - left as f64;
            (input[left] as f64 * (1.0 - fraction) + input[right] as f64 * fraction) as f32
        })
        .collect()
}

fn new_java_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(jstring) => jstring.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}
