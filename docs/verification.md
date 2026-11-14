# Verification: 2026-10-03

## Build

The documented Gradle route builds the Rust arm64-v8a library through cargo-ndk before the APK. Both `assembleDebug` and `assembleRelease` completed with JDK 17, Android SDK 35 and NDK 27.2.12479018.

Android lint initially found a call to `InputMethodService.switchToNextInputMethod` (API 28) despite minSdk 26. The call now has an API guard; Android 8 uses the system keyboard picker. Final lint result: 0 errors, 71 warnings. No Android 8 device was available to exercise that branch.

`scripts/verify-build.ps1` passed for version 0.1.2, versionCode 3. Its Java socket-directory workaround was verified; IPv4 and selector-provider changes alone did not resolve the environment failure.

Release APK output is unsigned by Gradle. The debug APK uses the development signing key and is suitable for beta testing, not a production signing scheme. Older signed APKs in the build directory are not fresh 0.1.2 builds.

## Smartphone

An Infinix X6833B connected through ADB. Debug 0.1.1 was installed and its settings activity launched. The user downloaded the int8 model through the app; on-device SHA-256 matched `2e3fcb7a7b66030336fd10c2fcfb033bd1dc7e1bf238fe5cfd83b1d0cfc9d28e`.

Microphone permission and the GigaAM IME were enabled for testing. The user dictated a phrase in a text field and confirmed that recognized text appeared. This is user-confirmed behavior, not an automated measurement of transcription accuracy or speed. The subsequent 0.1.2 patch changes keyboard switching compatibility, not the dictation pipeline.

The freshly built debug 0.1.2 APK was then installed successfully, its versionCode 3 confirmed and settings activity launched. The original Gboard default was restored after testing; the app and downloaded model remain available.

Full-model inference, airplane-mode dictation and hardware acceleration were not tested. Model downloads and source attribution were checked; the downloaded model is not included in the APK.

# Verification: 2026-11-14 (refactor, still 0.1.2)

## Build

`scripts/verify-build.ps1` passed: debug, release and lint, 0 errors, 54 warnings (was 71). Gradle built the Rust library itself with `ANDROID_NDK_HOME` unset; the NDK came from `sdk.dir`.

## Smartphone

Infinix X6833B, Android 14, int8 model already on the device. The debug build was installed over the old one; the model and settings stayed.

- Rust unit tests ran on the phone: 6 of 6 passed. One of them shows that the new sparse mel frontend gives bit-identical features to the old dense one.
- The Rust core with the real int8 model transcribed a Windows TTS phrase (voice Irina, 6.7 s) word for word: "Привет. Сегодня хорошая погода, и я проверяю голосовой ввод." Feature extraction took 2 ms; ONNX Runtime took about 1.6 s after the first run. After the passing test, the standalone test process aborted at exit with `FORTIFY: pthread_mutex_lock called on a destroyed mutex`; this was not compared with the old version.
- The keyboard: RU/EN letters, Shift auto-off, digits, symbols, Backspace tap and hold worked in the Settings search field.
- Dictation through the keyboard: the same phrase played from PC speakers was inserted word for word, with the trailing space.
- Gboard was restored as the default keyboard after testing.

Not tested: the full model, model download, hardware-acceleration-off mode, airplane mode.
