mod gigaam;

use crate::gigaam::{GigaamEngine, RuntimeOptions};
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::Mutex;
use std::time::Instant;

const MODEL_INT8_ID: &str = "gigaam-v3-e2e-ctc-int8";
const MODEL_FULL_ID: &str = "gigaam-v3-e2e-ctc";
const TARGET_SAMPLE_RATE: usize = 16_000;
const VOCAB_FILE: &str = "v3_e2e_ctc_vocab.txt";
const CONFIG_FILE: &str = "v3_e2e_ctc.yaml";

#[derive(Default)]
struct EngineCache {
    model_key: Option<String>,
    engine: Option<GigaamEngine>,
    runtime_options: RuntimeOptions,
    last_profile_summary: String,
}

static ENGINE_CACHE: Lazy<Mutex<EngineCache>> = Lazy::new(|| Mutex::new(EngineCache::default()));

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeIsModelValid(
    mut env: JNIEnv,
    _class: JClass,
    models_root_dir: JString,
    model_id: JString,
) -> jboolean {
    let result = validate_model_from_jni_inputs(&mut env, models_root_dir, model_id).is_ok();
    if result { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeTranscribe(
    mut env: JNIEnv,
    _class: JClass,
    models_root_dir: JString,
    model_id: JString,
    pcm16: JShortArray,
    sample_rate: jint,
) -> jstring {
    let result = transcribe_from_jni_inputs(
        &mut env,
        models_root_dir,
        model_id,
        pcm16,
        sample_rate,
    );
    match result {
        Ok(text) => new_java_string(&mut env, text),
        Err(error) => new_java_string(&mut env, format!("GigaAM error: {error}")),
    }
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
    _model_id: JString,
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
    let summary = ENGINE_CACHE
        .lock()
        .ok()
        .map(|cache| {
            if cache.last_profile_summary.is_empty() {
                "{}".to_string()
            } else {
                cache.last_profile_summary.clone()
            }
        })
        .unwrap_or_else(|| "{\"error\":\"cache_lock_failed\"}".to_string());
    new_java_string(&mut env, summary)
}

#[no_mangle]
pub extern "system" fn Java_com_servideus_gigaamime_nativebridge_GigaamNativeBridge_nativeUnload(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut cache) = ENGINE_CACHE.lock() {
        cache.engine = None;
        cache.model_key = None;
        cache.last_profile_summary.clear();
    }
}

fn validate_model_from_jni_inputs(
    env: &mut JNIEnv,
    models_root_dir: JString,
    model_id: JString,
) -> Result<(), String> {
    let models_root = jstring_to_rust(env, models_root_dir)?;
    let model_id = jstring_to_rust(env, model_id)?;
    let model_path = resolve_model_directory(&models_root, &model_id)?;
    validate_model_directory(&model_path, &model_id)
}

fn set_runtime_options_from_jni_inputs(
    env: &mut JNIEnv,
    speed_profile: JString,
    accelerator_mode: JString,
) -> Result<String, String> {
    let speed_profile = jstring_to_rust(env, speed_profile)?;
    let accelerator_mode = jstring_to_rust(env, accelerator_mode)?;
    let options = RuntimeOptions::from_ids(&speed_profile, &accelerator_mode);

    let mut cache = ENGINE_CACHE
        .lock()
        .map_err(|_| "Engine cache lock poisoned".to_string())?;
    if cache.runtime_options != options {
        cache.runtime_options = options;
        cache.engine = None;
        cache.model_key = None;
    }

    Ok(format!(
        "ok: speed_profile={}, accelerator_mode={}",
        cache.runtime_options.speed_profile.as_id(),
        cache.runtime_options.accelerator_mode.as_id()
    ))
}

fn warmup_from_jni_inputs(
    env: &mut JNIEnv,
    models_root_dir: JString,
    model_id: JString,
) -> Result<String, String> {
    let models_root = jstring_to_rust(env, models_root_dir)?;
    let model_id = jstring_to_rust(env, model_id)?;
    let model_path = resolve_model_directory(&models_root, &model_id)?;
    validate_model_directory(&model_path, &model_id)?;

    let mut cache = ENGINE_CACHE
        .lock()
        .map_err(|_| "Engine cache lock poisoned".to_string())?;
    ensure_engine_loaded(&mut cache, &models_root, &model_id, &model_path)?;

    let warmup_input = vec![0.0_f32; TARGET_SAMPLE_RATE / 2];
    let report_json = {
        let engine = cache
            .engine
            .as_mut()
            .ok_or_else(|| "Model engine is not loaded".to_string())?;
        let report = engine
            .transcribe_samples(&warmup_input)
            .map_err(|e| format!("Warmup failed: {e}"))?;
        report.to_json()
    };
    cache.last_profile_summary = format!(
        "{{\"warmup\":true,\"pcm_to_f32_ms\":0,\"resample_ms\":0,{}}}",
        report_json.trim_start_matches('{').trim_end_matches('}')
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
    let models_root = jstring_to_rust(env, models_root_dir)?;
    let model_id = jstring_to_rust(env, model_id)?;
    let model_path = resolve_model_directory(&models_root, &model_id)?;
    validate_model_directory(&model_path, &model_id)?;

    let mut pcm = vec![
        0_i16;
        env.get_array_length(&pcm16)
            .map_err(|e| format!("Failed to get PCM array length: {e}"))? as usize
    ];
    env.get_short_array_region(&pcm16, 0, &mut pcm)
        .map_err(|e| format!("Failed to read PCM samples: {e}"))?;

    let source_rate = usize::try_from(sample_rate).map_err(|_| "Invalid sample rate".to_string())?;

    let pcm_to_f32_start = Instant::now();
    let mut samples = pcm
        .iter()
        .map(|sample| *sample as f32 / i16::MAX as f32)
        .collect::<Vec<f32>>();
    let pcm_to_f32_ms = pcm_to_f32_start.elapsed().as_millis();

    let resample_start = Instant::now();
    if source_rate != TARGET_SAMPLE_RATE {
        samples = resample_linear(&samples, source_rate, TARGET_SAMPLE_RATE);
    }
    let resample_ms = resample_start.elapsed().as_millis();

    let mut cache = ENGINE_CACHE
        .lock()
        .map_err(|_| "Engine cache lock poisoned".to_string())?;
    ensure_engine_loaded(&mut cache, &models_root, &model_id, &model_path)?;

    let report = {
        let engine = cache
            .engine
            .as_mut()
            .ok_or_else(|| "Model engine is not loaded".to_string())?;
        engine
            .transcribe_samples(&samples)
            .map_err(|e| format!("Transcription failed: {e}"))?
    };

    cache.last_profile_summary = format!(
        "{{\"warmup\":false,\"pcm_to_f32_ms\":{pcm_to_f32_ms},\"resample_ms\":{resample_ms},{}}}",
        report.to_json().trim_start_matches('{').trim_end_matches('}')
    );
    Ok(report.text)
}

fn ensure_engine_loaded(
    cache: &mut EngineCache,
    models_root: &str,
    model_id: &str,
    model_path: &Path,
) -> Result<(), String> {
    let cache_key = compose_cache_key(models_root, model_id, cache.runtime_options)?;
    if cache.model_key.as_deref() != Some(cache_key.as_str()) {
        let mut engine = GigaamEngine::new();
        engine
            .load_model(model_path, cache.runtime_options)
            .map_err(|e| format!("Failed to load model: {e}"))?;
        cache.model_key = Some(cache_key);
        cache.engine = Some(engine);
    }
    Ok(())
}

fn compose_cache_key(
    models_root: &str,
    model_id: &str,
    runtime_options: RuntimeOptions,
) -> Result<String, String> {
    Ok(format!(
        "{models_root}/{}?{}",
        model_subdirectory_name(model_id)?,
        runtime_options.cache_fragment()
    ))
}

fn jstring_to_rust(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|e| format!("Failed to decode Java string: {e}"))
}

fn resolve_model_directory(models_root: &str, model_id: &str) -> Result<PathBuf, String> {
    let subdir = model_subdirectory_name(model_id)?;
    Ok(Path::new(models_root).join(subdir))
}

fn validate_model_directory(model_dir: &Path, model_id: &str) -> Result<(), String> {
    if !model_dir.exists() {
        return Err(format!("Model directory does not exist: {}", model_dir.display()));
    }
    let onnx_file = model_onnx_filename(model_id)?;
    let required_files = [onnx_file, VOCAB_FILE, CONFIG_FILE];
    for required_file in required_files {
        let path = model_dir.join(required_file);
        if !path.exists() {
            return Err(format!("Required file not found: {}", path.display()));
        }
    }
    Ok(())
}

fn model_subdirectory_name(model_id: &str) -> Result<&'static str, String> {
    match model_id {
        MODEL_INT8_ID => Ok("gigaam-v3-e2e-ctc-int8"),
        MODEL_FULL_ID => Ok("gigaam-v3-e2e-ctc"),
        _ => Err(format!("Unsupported model id: {model_id}")),
    }
}

fn model_onnx_filename(model_id: &str) -> Result<&'static str, String> {
    match model_id {
        MODEL_INT8_ID => Ok("v3_e2e_ctc.int8.onnx"),
        MODEL_FULL_ID => Ok("v3_e2e_ctc.onnx"),
        _ => Err(format!("Unsupported model id: {model_id}")),
    }
}

fn resample_linear(input: &[f32], source_rate: usize, target_rate: usize) -> Vec<f32> {
    if input.is_empty() || source_rate == 0 || target_rate == 0 || source_rate == target_rate {
        return input.to_vec();
    }

    let ratio = target_rate as f64 / source_rate as f64;
    let output_len = ((input.len() as f64) * ratio).round().max(1.0) as usize;
    let mut output = vec![0.0_f32; output_len];

    for (index, value) in output.iter_mut().enumerate() {
        let source_pos = index as f64 / ratio;
        let left = source_pos.floor() as usize;
        let right = (left + 1).min(input.len().saturating_sub(1));
        let fraction = source_pos - left as f64;
        let left_sample = input[left];
        let right_sample = input[right];
        *value = (left_sample as f64 * (1.0 - fraction) + right_sample as f64 * fraction) as f32;
    }

    output
}

fn new_java_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(jstring) => jstring.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}
