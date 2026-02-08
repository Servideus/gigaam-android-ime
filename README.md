# GigaAM Android IME (MVP)

Android keyboard (IME) for offline Russian dictation using GigaAM v3 e2e-CTC.

## Implemented

- Dedicated Android project in `gigaam-android-ime/`
- Keyboard service (`InputMethodService`) with:
  - mic start/stop
  - transcription trigger
  - `commitText()` insertion into focused app
- Settings screen with:
  - model choice: `int8` or `full`
  - download/delete model
  - set active model
  - append trailing space toggle
  - open Android keyboard settings/input picker
- Model repository with per-file SHA-256 verification
- Native Rust core (`native/gigaam_core`) with JNI bridge:
  - model validation
  - model load cache
  - transcription call
- Reused GigaAM processing implementation copied from:
  - `Handy/src-tauri/src/managers/gigaam.rs`

## Model IDs and files

- `gigaam-v3-e2e-ctc-int8`
  - `v3_e2e_ctc.int8.onnx`
  - `v3_e2e_ctc_vocab.txt`
  - `v3_e2e_ctc.yaml`
- `gigaam-v3-e2e-ctc`
  - `v3_e2e_ctc.onnx`
  - `v3_e2e_ctc_vocab.txt`
  - `v3_e2e_ctc.yaml`

Both model catalogs (URLs + SHA-256 + size) are embedded in:

- `app/src/main/java/com/servideus/gigaamime/data/ModelSpec.kt`

## Build prerequisites

- Android Studio / Android SDK
- JDK 17
- Rust toolchain
- `cargo-ndk`:
  - `cargo install cargo-ndk`

## Build steps

1. Build Rust `.so` for Android (manual option):
   - `powershell -ExecutionPolicy Bypass -File .\\scripts\\build-rust-android.ps1 -Abi arm64-v8a -Profile release`
2. Open `gigaam-android-ime` in Android Studio.
3. Sync Gradle and build/install app (`.\\gradlew.bat assembleDebug` also works).
4. In app settings:
   - grant microphone permission
   - download selected model
   - set active model
   - choose trailing space behavior
5. Enable keyboard in Android Input Method settings.
6. Switch to `GigaAM Keyboard` and use mic/stop buttons.

## Gradle wrapper and Rust build hook

- Gradle wrapper is included:
  - `gradlew`
  - `gradlew.bat`
  - `gradle/wrapper/gradle-wrapper.jar`
  - `gradle/wrapper/gradle-wrapper.properties`
- Android `preBuild` depends on `buildRustCore` in:
  - `app/build.gradle.kts`
- Build controls:
  - skip Rust build: `-PskipRustBuild=true`
  - custom ABI: `-PrustAbi=arm64-v8a`
  - custom profile: `-PrustProfile=release` or `-PrustProfile=debug`

## Notes

- Target ABI: `arm64-v8a` (MVP scope).
- Models are downloaded post-installation and stored in app files.
- If native libs are missing, settings screen shows native load error text.
