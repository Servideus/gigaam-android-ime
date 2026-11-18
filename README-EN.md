[Русская версия](README.md)

# GigaAM Android IME

An Android keyboard that turns Russian speech into text without the internet. Recognition runs on the phone, using the GigaAM v3 e2e-RNNT model.

**[Download gigaam-ime.apk](https://github.com/Servideus/gigaam-android-ime/releases/latest/download/gigaam-ime.apk)** — the latest version. Release notes are on the [releases page](https://github.com/Servideus/gigaam-android-ime/releases).

## Requirements

- A phone with an arm64 processor. Nearly all phones released after 2017 have one.
- Android 8.0 or newer. Tested only on Android 14 (Infinix X6833B).
- Free storage: about 500 MB while the model downloads; afterwards the model takes 226 MB.
- Internet only once, to download the model. Wi‑Fi is better.

## Install

1. Download [gigaam-ime.apk](https://github.com/Servideus/gigaam-android-ime/releases/latest/download/gigaam-ime.apk) and open the file on the phone.
2. Android asks whether apps may be installed from this source (your browser or file manager). Allow it. Google Play Protect may warn about an unknown app; this is normal for apps outside the Play Store.
3. Open the «GigaAM Диктовка IME» app (GigaAM Dictation IME) and complete four steps:
   1. Allow microphone access.
   2. Download the model.
   3. Enable «Клавиатура GigaAM» (GigaAM Keyboard) in Android settings.
   4. Select «Клавиатура GigaAM» as the current keyboard.
4. When you enable the keyboard, Android warns that it may collect all text you type. Android shows this for every third-party keyboard. What GigaAM does is described under "Privacy".

## Use

1. Open any text field. «Клавиатура GigaAM» appears.
2. Tap the microphone button and speak.
3. Tap the button again. The text appears in the field about 1.5 s later for a short phrase and about 2.5 s later for a one-minute recording.

Long recordings are cut at pauses and transcribed while you speak, so no text is lost and the wait does not grow. Besides dictation there is a regular keyboard: RU/EN layouts, digits and symbols.

## Privacy

- Voice and text are processed only on the phone. The app sends them nowhere.
- The internet is used only to download the model from huggingface.co. Each model file is checked against its SHA-256 hash.
- The app requests microphone and internet access. It requests no other permissions.

## Troubleshooting

- **«Нет модели. Настроить ›»** (no model, set up) — tap this text in the keyboard and download the model (step 2).
- **«Нет микрофона. Настроить ›»** (no microphone, set up) — tap this text and allow the microphone. If Android no longer shows the prompt, allow it in the app's Android settings.
- **The keyboard is not in the list** — enable it in Android settings first (step 3), then select it (step 4).
- **The model download stopped** — check the internet connection and free storage. If the download does not continue by itself, tap «Скачать модель» (Download model) again.
- **The update does not install** — see "Updating".

## Updating

Since 0.3.0 the APK is signed with the project's permanent key. Later versions will install over it.

Versions before 0.3.0 were signed with other keys, so Android will not install 0.3.0 over them. Uninstall the old version first, then install the new one. Uninstalling deletes the settings and the model, so the model must be downloaded again.

## Status

The latest release is beta 0.3.0. It adds a 4-step setup screen, a background model download that resumes, setup hints in the keyboard, an app icon and a permanent APK signature. Checked on an Infinix X6833B (Helio G99, Android 14): builds and lint pass, live dictation works. Measurements and limits are in the [verification report](docs/verification.md). Android 8 and other phones were not checked.

## For developers

Kotlin handles the keyboard and settings. The Rust core (`native/gigaam_core/`) runs ONNX Runtime on the CPU with 6 threads and is called through JNI. The `gigaam-v3-e2e-rnnt-int8` model is not bundled in the APK. Its URLs, hashes and sizes are defined in [ModelSpec.kt](app/src/main/java/com/servideus/gigaamime/data/ModelSpec.kt).

### Build requirements

- Android Studio / Android SDK, SDK Platform 35, NDK 27.2.12479018
- JDK 17
- Rust (stable), `cargo-ndk` and the `aarch64-linux-android` target

```powershell
cargo install cargo-ndk
rustup target add aarch64-linux-android
```

The SDK path comes from `local.properties` (`sdk.dir`). If `ANDROID_NDK_HOME` is not set, NDK `27.2.12479018` from that SDK is used.

### Build

```powershell
.\gradlew.bat assembleDebug
```

Gradle builds the Rust core during `preBuild`. The APK appears at `app/build/outputs/apk/debug/app-debug.apk`. The Gradle release APK is unsigned: the release is signed separately with `apksigner` and the project key.

Full check (debug, release and lint):

```powershell
./scripts/verify-build.ps1
```

The script sets a separate temporary Java socket directory. This avoids the `Unable to establish loopback connection` error caused by an 8.3 Windows `TEMP` path.

You can build Rust once and then skip it:

```powershell
.\gradlew.bat :app:buildRustCore -PrustProfile=release
.\gradlew.bat assembleDebug -PskipRustBuild=true
```

Options: `-PskipRustBuild=true` skips Rust, `-PrustAbi=arm64-v8a` selects the ABI, `-PrustProfile=release|debug` selects the Rust profile. Configuration is in [app/build.gradle.kts](app/build.gradle.kts).

## License

Application code: [MIT](LICENSE). GigaAM authorship, model terms and dependency licenses are described in [THIRD_PARTY.md](THIRD_PARTY.md).
