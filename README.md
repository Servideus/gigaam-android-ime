[Инструкция на русском языке здесь](README.ru.md).

# GigaAM Android IME

An experimental Android keyboard for offline Russian dictation using GigaAM v3 e2e-RNNT. Kotlin handles the keyboard and settings; a Rust core runs ONNX inference through JNI. Models are downloaded separately and checked against SHA-256 hashes.

## Status

Beta 0.1.2 fixes keyboard switching on Android 8. Debug/release builds and lint passed, with 71 lint warnings remaining. The user confirmed that int8 dictation inserted text on an Infinix X6833B. See the [verification report](docs/verification.md) for the tested versions and limits; Android 8 hardware was not verified. The switch from e2e-CTC to e2e-RNNT was checked on the same phone, except the in-app model download.

## Features

- RU/EN layouts, language switching, Shift, Backspace, Enter, digits and symbols.
- Start/stop recording with the microphone button and insert recognized text into the active field.
- Recordings longer than 20 s are cut at pauses into 10–20 s pieces; one model pass over a long recording loses text.
- Pieces are transcribed while recording goes on. After stop at most a 30 s tail is left, so the wait does not grow with recording length.
- When a cut falls inside a sentence, the capital letter at the start of the next piece becomes lowercase.
- Download/delete the model; optional model warmup.
- Inference runs on ONNX Runtime's CPU provider with 6 threads, the fastest setup measured on the test phone. XNNPACK/NNAPI acceleration and speed profiles were removed: acceleration was twice as slow there, and the profiles gave the same text.
- SHA-256 verification and models stored in the app's internal storage.

## Model

One model, `gigaam-v3-e2e-rnnt-int8` (about 226 MB): `v3_e2e_rnnt_encoder.int8.onnx`, `v3_e2e_rnnt_decoder.int8.onnx`, `v3_e2e_rnnt_joint.int8.onnx`, `v3_e2e_rnnt_vocab.txt` and `v3_e2e_rnnt.yaml`. URLs, hashes and sizes are defined in [ModelSpec.kt](app/src/main/java/com/servideus/gigaamime/data/ModelSpec.kt). The model is not bundled in the APK. Folders of the earlier e2e-CTC models are deleted when the settings screen opens.

## Build

Requires Android Studio/SDK, JDK 17, stable Rust, `cargo-ndk`, Android SDK Platform 35, NDK 27.2.12479018 and the `aarch64-linux-android` Rust target.

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android
```

The SDK path comes from `local.properties` (`sdk.dir`). If `ANDROID_NDK_HOME` is not set, the Rust build uses NDK `27.2.12479018` from that SDK.

```powershell
.\gradlew.bat assembleDebug
```

Gradle invokes the Rust build during `preBuild`. For debug/release builds and lint together:

```powershell
./scripts/verify-build.ps1
```

The verification script uses a temporary Java socket directory to avoid `Unable to establish loopback connection` with an 8.3 Windows TEMP path, then restores the environment.

Alternatively, build the Rust library on its own, then reuse it:

```powershell
.\gradlew.bat :app:buildRustCore -PrustProfile=release
.\gradlew.bat assembleDebug -PskipRustBuild=true
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. The Gradle release APK is unsigned; a distributable release requires your signing setup.

## Use on a device

1. Install the APK and open GigaAM IME settings.
2. Allow microphone access and download the model.
3. Enable `GigaAM Keyboard` in Android settings.
4. Select it with the keyboard switcher, open a text field and use the microphone button.

## Build options and layout

`-PskipRustBuild=true` skips Rust compilation; `-PrustAbi=arm64-v8a` selects the ABI; `-PrustProfile=release` or `debug` selects the Rust profile. Configuration is in [app/build.gradle.kts](app/build.gradle.kts).

`app/` contains the Android UI, IME and model downloads. `native/gigaam_core/` contains inference. The `buildRustCore` Gradle task builds the Android native library with `cargo-ndk`.

The APK contains `arm64-v8a` libraries only (about 25 MB), the one ABI the Rust core is built for. Downloading models requires a network connection; transcription uses the installed local model.

## License

Application code: [MIT](LICENSE). GigaAM authorship, model terms and dependency notices are documented in [THIRD_PARTY.md](THIRD_PARTY.md). Dependencies and separately downloaded models retain their respective licenses.
