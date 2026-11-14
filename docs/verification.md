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

# Verification: 2026-11-14 (e2e-RNNT int8, long audio cut at pauses)

## Why

One encoder pass over a long recording loses text. PC measurement (onnx-asr, int8, 5 Windows TTS clips glued with 0.8 s silence, exact reference):

| Audio | CTC one pass | RNNT one pass | RNNT, cut at pauses |
|---|---|---|---|
| 38 s | 3.7% WER | 1.9% | 3.7% |
| 69 s | 10.4% | 13.5% | 4.2% |
| 100 s | 16.7% | 25.4% | 4.3% |

A real 182 s user recording came back empty in one RNNT pass; cut at pauses it gave 268 words. The core now keeps audio up to 20 s whole and cuts longer audio at the quietest 300 ms point into 10–20 s pieces.

## Build

`scripts/verify-build.ps1` passed: debug, release and lint, 0 errors, 54 warnings.

## Smartphone

Infinix X6833B, Android 14, debug build installed over 0.1.2.

- Rust unit tests on the phone: 7 of 7 passed, including the pause-cut test. The standalone test process still aborts at exit with the known `FORTIFY` message after the results.
- Integration test, real model, auto mode: 13.4 s phrase word for word in 3.3 s; 100 s TTS file 3.6% WER (5 of 138 words, 2 of them "₽" for "рублей") in 21–22 s; the 182 s user recording complete in 39 s. Decoder and joint took 1–2% of the time.
- The model files were copied into app storage over ADB, not downloaded by the app; their SHA-256 matched `ModelSpec.kt`. Opening settings deleted the old e2e-CTC folder and showed the model as ready.
- Dictation through the keyboard: 61 s of TTS played from PC speakers into a Chrome textarea came out complete; differences from the script: one repeated "отправь" and one "и" dropped, "документы" for "документ", "₽" for "рублей". Text appeared within 17 s after stop. The Settings search field truncates long input, so it is unsuitable for this test.

Not tested: the in-app model download of the RNNT files, acceleration-off mode, live human speech.

# Verification: 2026-11-14 (transcription while recording)

A piece is transcribed during recording once 30 s are buffered past its start: from then on, its cut no longer depends on later audio. A unit test streams 3 minutes in 0.25 s steps and gets exactly the cuts of the whole recording. After stop, only the tail (at most 30 s) is transcribed.

Build: `scripts/verify-build.ps1` passed, lint 0 errors, 54 warnings. Rust unit tests on the phone: 8 of 8 passed.

Wait from the stop tap until the text appeared in a Chrome textarea, TTS played from PC speakers, same phone, polling step about 1.5 s:

| Recording | Before | Now |
|---|---|---|
| 61 s | 17.5 s | 8.3 s, 9.2 s |
| 100 s | 24.8 s | 6.9 s |

The 100 s text matched the earlier build's text. One 61 s run lost the opening "Ну,"; the repeat kept it, so this looks like playback variation. Recordings under 30 s behave as before.

# Verification: 2026-11-14 (piece joins, arm64-only APK)

Joins: a piece cut inside a sentence started with a capital letter. Rust and Kotlin now lowercase it when the previous piece ends without . ! ? or …, unless the word looks like an acronym. On the phone, the 182 s user recording lost all four wrong capitals: "рис он начал", "изменил процесс", "три раза промываю", "что утром". Rust unit tests on the phone: 9 of 9 passed. The Kotlin twin of the rule ran in a 100 s keyboard dictation, but that recording had no cut inside a sentence, so its lowercase branch was not exercised on the device.

APK: only `arm64-v8a` libraries are packaged now; the Rust core never existed for other ABIs. Debug APK 82.3 → 25.9 MB, release 78.9 → 24.6 MB. Lint: 0 errors, 55 warnings; the new one is `ChromeOsAbiSupport` (no x86_64), expected for the same reason. The smaller debug APK installed over the old one, kept the model and dictated 100 s completely; text appeared 8.2 s after stop.

# Measurement: 2026-11-14 (accelerator modes)

Infinix X6833B, MediaTek MT6789 (Helio G99: 2 × Cortex-A76 at 2.2 GHz, 6 × Cortex-A55 at 2.0 GHz), USB power, battery 37–38 °C. Integration test with `GIGAAM_TEST_SPEED_PROFILE` and `GIGAAM_TEST_ACCELERATOR`, Windows TTS phrase of 9.8 s, model e2e-RNNT int8. Two passes in opposite order; median of the warm runs, total time per phrase:

| Mode | Warm median | First run after load |
|---|---|---|
| Auto (XNNPACK + NNAPI + CPU), any profile | 2.18–2.28 s | 2.7–3.4 s |
| Auto with XNNPACK only (temporary build) | 1.71 s | 1.7–1.8 s |
| Auto with NNAPI only (temporary build) | 2.23 s | 3.9 s |
| CPU, 2 threads (temporary build) | 1.04 s | 1.5 s |
| CPU, 4 threads (Balanced, Quality) | 1.12–1.25 s | 1.7–2.1 s |
| CPU, 6 threads (Fast) | 1.03 s | 1.4–1.7 s |
| CPU, 8 threads (temporary build) | 1.19 s | 1.8–2.1 s |

All modes gave the same text. On this phone the default Auto mode is about twice as slow as CPU, mostly because of NNAPI. The temporary provider and thread overrides were removed after the measurement.

# Verification: 2026-11-14 (CPU by default, faster stop)

- CPU is now the default accelerator mode in Kotlin (`AcceleratorMode.fromId`) and Rust (`RuntimeOptions::DEFAULT`, `from_id`). A stored choice is kept. On the phone, with no stored choice, the settings switch showed off and the hint "Только CPU (по умолчанию)".
- Stop no longer waits for the live loop's 1 s pause: the loop is cancelled, and a piece already running finishes in a non-cancellable block and is kept.
- The `TranscribeTiming` log line is now info level, because this firmware sets `log.tag` to `I` and hides debug lines.

Build: `scripts/verify-build.ps1` passed, lint 0 errors, 55 warnings. Keyboard dictation, TTS from PC speakers, timing from the app log (stop tap to text inserted):

| Recording | After stop | Model work |
|---|---|---|
| 11 s phrase | 1.57 s, 1.81 s | 1.5–1.7 s |
| 61 s | 3.92 s | 2.9 s |

External polling of the screen adds about 2 s on top of these numbers; it read 3.8–3.9 s and 6.1 s for the same runs, against 8.3–9.2 s for 61 s with the earlier build.

Live speech: the user then dictated their own phrases on the phone and confirmed that recognition was fast and worked. This is user-confirmed behavior, not a measured accuracy or timing.
