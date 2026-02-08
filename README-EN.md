# GigaAM Android IME (MVP)

Android keyboard (IME) for offline Russian dictation powered by GigaAM v3 e2e-CTC.

## Implemented

- Native Android IME app (`InputMethodService`).
- Keyboard with RU/EN layouts, language switch, `Shift`, `Backspace`, `Enter`, digits, and symbols.
- Voice input via microphone button:
  - start recording,
  - stop recording,
  - transcribe and insert text into the active input field.
- Settings screen:
  - model selection (`int8` / `full`),
  - model download/delete,
  - set active model,
  - performance profile selection,
  - hardware acceleration toggle (experimental),
  - model warmup toggle.
- Model downloads with SHA-256 integrity verification.
- Native Rust core (`native/gigaam_core`) with JNI bridge for inference.

## Models

- `gigaam-v3-e2e-ctc-int8`
  - `v3_e2e_ctc.int8.onnx`
  - `v3_e2e_ctc_vocab.txt`
  - `v3_e2e_ctc.yaml`
- `gigaam-v3-e2e-ctc`
  - `v3_e2e_ctc.onnx`
  - `v3_e2e_ctc_vocab.txt`
  - `v3_e2e_ctc.yaml`

Model catalog (URL, SHA-256, file size) is defined in:

- `app/src/main/java/com/servideus/gigaamime/data/ModelSpec.kt`

## Build Requirements

- Android Studio / Android SDK
- JDK 17
- Rust (stable)
- `cargo-ndk`

Install `cargo-ndk`:

```bash
cargo install cargo-ndk
```

## Build

### Option 1 (recommended): Gradle-driven

```powershell
.\gradlew.bat assembleDebug
```

Gradle triggers Rust build automatically during `preBuild`.

### Option 2: Build Rust `.so` manually, then run Gradle

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-rust-android.ps1 -Abi arm64-v8a -Profile release
.\gradlew.bat assembleDebug -PskipRustBuild=true
```

Generated APK:

- `app/build/outputs/apk/debug/app-debug.apk`

## Install and Run on Device

1. Install the APK on your phone.
2. Open GigaAM IME settings app.
3. Grant microphone permission.
4. Download a model and set it as active.
5. Enable `GigaAM Keyboard` in Android input method settings.
6. Switch to `GigaAM Keyboard` and start typing/dictating.

## Build Parameters

- Skip Rust build:
  - `-PskipRustBuild=true`
- Choose ABI:
  - `-PrustAbi=arm64-v8a`
- Choose Rust profile:
  - `-PrustProfile=release`
  - `-PrustProfile=debug`

Configured in:

- `app/build.gradle.kts`

## Project Structure

- `app/` — Android app (UI, IME service, settings, model downloads).
- `native/gigaam_core/` — Rust inference core.
- `scripts/build-rust-android.ps1` — Rust Android build script.

## MVP Limitations

- Primary target ABI: `arm64-v8a`.
- Models are downloaded after app installation and stored in app-internal storage.
