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
