[Инструкция на русском языке здесь](README.ru.md).

# GigaAM Android IME

An experimental Android keyboard for offline Russian dictation using GigaAM v3 e2e-CTC. Kotlin handles the keyboard and settings; a Rust core runs ONNX inference through JNI. Models are downloaded separately and checked against SHA-256 hashes.

## Status

Beta 0.1.2 fixes keyboard switching on Android 8. Debug/release builds and lint passed, with 71 lint warnings remaining. The user confirmed that int8 dictation inserted text on an Infinix X6833B. See the [verification report](docs/verification.md) for the tested versions and limits; Android 8 hardware, the full model and acceleration were not verified.

## Features

- RU/EN layouts, language switching, Shift, Backspace, Enter, digits and symbols.
- Start/stop recording with the microphone button and insert recognized text into the active field.
- Download/delete models, choose an active model and select a performance profile.
- Experimental hardware acceleration and model warmup settings.
- SHA-256 verification and models stored in the app's internal storage.

## Models

| Model | ONNX file |
|---|---|
| `gigaam-v3-e2e-ctc-int8` | `v3_e2e_ctc.int8.onnx` |
| `gigaam-v3-e2e-ctc` | `v3_e2e_ctc.onnx` |

Both use `v3_e2e_ctc_vocab.txt` and `v3_e2e_ctc.yaml`. URLs, hashes and sizes are defined in [ModelSpec.kt](app/src/main/java/com/servideus/gigaamime/data/ModelSpec.kt). Models are not bundled in the APK.

## Build

Requires Android Studio/SDK, JDK 17, stable Rust, `cargo-ndk`, Android SDK Platform 35, NDK 27.2.12479018 and the `aarch64-linux-android` Rust target.

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android
```

On Windows, set `ANDROID_HOME` to your SDK and `ANDROID_NDK_HOME` to its `ndk/27.2.12479018` directory.

```powershell
.\gradlew.bat assembleDebug
```

Gradle invokes the Rust build during `preBuild`. For debug/release builds and lint together:

```powershell
./scripts/verify-build.ps1
```

The verification script uses a temporary Java socket directory to avoid `Unable to establish loopback connection` with an 8.3 Windows TEMP path, then restores the environment.

Alternatively, build the Rust library explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-rust-android.ps1 -Abi arm64-v8a -Profile release
.\gradlew.bat assembleDebug -PskipRustBuild=true
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. The Gradle release APK is unsigned; a distributable release requires your signing setup.

## Use on a device

1. Install the APK and open GigaAM IME settings.
2. Allow microphone access, download a model and make it active.
3. Enable `GigaAM Keyboard` in Android settings.
4. Select it with the keyboard switcher, open a text field and use the microphone button.

## Build options and layout

`-PskipRustBuild=true` skips Rust compilation; `-PrustAbi=arm64-v8a` selects the ABI; `-PrustProfile=release` or `debug` selects the Rust profile. Configuration is in [app/build.gradle.kts](app/build.gradle.kts).

`app/` contains the Android UI, IME and model downloads. `native/gigaam_core/` contains inference. `scripts/build-rust-android.ps1` builds the Android native library.

The primary target is `arm64-v8a`. Downloading models requires a network connection; transcription uses the installed local model.

## License

Application code: [MIT](LICENSE). GigaAM authorship, model terms and dependency notices are documented in [THIRD_PARTY.md](THIRD_PARTY.md). Dependencies and separately downloaded models retain their respective licenses.
