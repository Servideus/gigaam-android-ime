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

# Verification: 2026-11-14 (acceleration and speed profiles removed)

The hardware-acceleration switch and the speed profiles are gone from settings, Kotlin, JNI and the Rust core. The encoder always runs on ONNX Runtime's CPU provider with 6 threads (the fastest setup in the measurement above); the decoder and joint keep 1 thread. The profiles only changed thread counts and NNAPI precision, and every mode gave the same text. Stored `speed_profile` / `accelerator_mode` preferences are now ignored. The integration-test variables `GIGAAM_TEST_SPEED_PROFILE` / `GIGAAM_TEST_ACCELERATOR` no longer exist.

Build: `scripts/verify-build.ps1` passed, lint 0 errors, 55 warnings. Rust unit tests on the phone: 9 of 9 passed. Integration test, 9.8 s phrase: 1.00–1.17 s per run, same text as before. The settings screen shows the model status, trailing space, warmup, and the download, delete and keyboard buttons.

Keyboard dictation, TTS from PC speakers, timing from the app log (stop tap to text inserted): 11 s phrase 1.71 s and 1.37 s; 61 s recording 2.47 s. All texts complete.

# Verification: 2026-11-14 (release 0.2.0)

Version 0.2.0, versionCode 4. `scripts/verify-build.ps1` passed: lint 0 errors, 55 warnings. The debug APK is signed with the Android debug key (certificate SHA-256 `6432c067…be42aa`) and installed over the previous debug build on the Infinix X6833B.

In-app model download, checked for the first time: "Удалить модель", then "Скачать модель" in settings. All five RNNT files downloaded from Hugging Face over the phone's network; their SHA-256 on the device matched `ModelSpec.kt`; the app then warmed the model and showed it as ready. A 61 s keyboard dictation afterwards came out complete, 2.67 s after stop.

# Verification: 2026-11-18 (release 0.3.0)

Version 0.3.0, versionCode 5. `scripts/verify-build.ps1` passed: lint 0 errors, 55 warnings. The release APK was built from a clean tree, signed outside Gradle with `apksigner` and a new project key (certificate SHA-256 `2b2f7ac8014cd662d78eafd1638f18edba96f3c7525b7c805908a637851835ad`, schemes v2 and v3), and checked with `apksigner verify` and `zipalign -c`. It is not debuggable. The password of the 0.1.0/0.1.1 key was lost, so no earlier version updates in place.

Smartphone: Infinix X6833B, Android 14, Wi‑Fi.

- Setup checklist (debug build): each of the four steps showed a number and a button when undone and a check mark when done. The state refreshed after returning from Android settings and right after the keyboard picker closed. The picker button stayed disabled until the keyboard was enabled. After a permanent microphone denial the button opened the app settings; after a single denial on a clean install it kept asking.
- Model download (debug build) through the system DownloadManager: a full download installed with matching SHA-256 for all five files and an empty staging folder. With Wi‑Fi and mobile data off the screen showed the waiting text; after Wi‑Fi returned the download continued from 15 % instead of 0 %, and the file fetched in two parts passed SHA-256. The download finished while the app process was killed; opening the app verified and installed it. A changed staged file was rejected by size and, with the size kept, by SHA-256; the retry queued only that file. Cancel removed the queued downloads and staged files.
- Keyboard hint (debug build): without microphone permission or without the model the status line showed «Нет микрофона. Настроить ›» or «Нет модели. Настроить ›» as soon as the keyboard opened; tapping it opened the setup screen. With everything ready the line showed «Ожидание» and the tap did nothing.
- Signed release: the debug build was uninstalled, the signed APK installed, and all four steps were completed through the UI on a clean install, including the model download, warmup and the Android keyboard warning.

Not tested: a server-side download failure, the low-space message, the «Загрузка отменена» text, swiping the app from recents (the process was killed instead), the core-error hint, the system download notification (not seen in the shade on this firmware), Android 8 and other phones.

# Verification: 2026-11-20 (CI and the phone-check script)

CI: `.github/workflows/checks.yml` ran on GitHub for commit `47cf290`, on its branch and again on `main`, green both times. The Linux job ran the Rust unit tests on the host (9 passed, the model integration test ignored). The Android job built debug and release and ran lint, with NDK 27.2.12479018.

`scripts/phone-check.ps1` on the Infinix X6833B, Android 14:

- Green run with `-ModelDir`: debug app installed; Rust unit tests 9 of 9 passed on the phone; the real model transcribed the Windows TTS phrase (voice Irina, 16 kHz) word for word in 0.96–1.10 s per run. Exit code 0.
- Deliberate red run, expected text changed to «плохая погода»: the integration test failed with the real transcription in the message; exit code 1.
- Without `-ModelDir` the integration step is reported as skipped; exit code 0.
- A repeat run did not push the model again: the sizes on the phone matched.
- `-Dictation`, with the owner's consent: the model was copied into the debug app, the debug keyboard was switched on, the phrase played from the PC speakers went into a Chrome text field word for word and was seen 1.4 s after the stop tap (app log: 0.98 s after stop, model 0.84 s). Gboard was restored after every run, and no test tab or port forward was left behind.
- Deliberate red run with `-Dictation`: with the wrong expected text both the model step and the dictation step failed; exit code 1, keyboard restored.
- Full run with the build, `-ModelDir` and `-Dictation`: four steps passed in 55 s; exit code 0.

Two earlier attempts showed why the checks matter. The mic tap first missed (the keyboard window spans the whole screen, so its touchable region is used now). Then `uiautomator dump` did not show the page text in Chrome 158, so the result is read through the page title instead. A stale tab with old text could have passed the check, so each run opens a new page and requires an empty field before dictating.

Not covered by the script: the in-app model download, live human speech.

# Measurement: 2026-11-20 (recognition eval baseline)

`eval/` holds six fixed clips: five synthetic TTS recordings from the VoicePaste benchmark of 2026-10-04 (voices Irina and Pavel, one with noise at SNR 20 dB) and `long.wav`, the five glued with 0.8 s pauses (byte-identical to the 100 s file of 2026-11-14). `scripts/phone-check.ps1 -Eval` transcribes each on the phone through the Rust integration test and appends the result to `eval/results.jsonl`.

Baseline, Infinix X6833B, model files hash `740ea55c365a`, core of commit `ec241c6`:

| Clip | Words | Errors | WER | First run | Warm |
|---|---|---|---|---|---|
| short | 15 | 1 | 6.7% | 1.78 s | 1.45 s |
| instructions | 21 | 0 | 0.0% | 2.11 s | 1.96 s |
| disfluency | 18 | 1 | 5.6% | 2.57 s | 1.87 s |
| facts (noise) | 42 | 2 | 4.8% | 4.50 s | 4.45 s |
| facts-clean | 42 | 2 | 4.8% | 4.64 s | 4.37 s |
| five clips | 138 | 6 | 4.3% | | |
| long (100 s) | 138 | 6 | 4.3% | 15.3 s | 14.8 s |

The errors: «смыслы» for «смысл», one «отправь» of the repeated pair dropped, «₽» for «рублей» (twice), «документы» for «документ» (twice). On 2026-11-14 the same long file gave 5 errors of 138; that run used the Auto accelerator mode, which may explain the difference (not checked).

Checks of the measurement itself:

- The reference word counts (15, 21, 18, 42, 42, 138) match the 2026-11-14 normalization in Python; 138 matches the earlier report.
- On the phone texts, the PowerShell word errors equal Python's Levenshtein and difflib counts for all six clips.
- Red runs: a clip scored against another clip's reference gave 95–222% WER; with no model on the phone the eval step failed with exit code 1.
- All three runs of every clip gave identical text; the script fails the step otherwise.

# Verification: 2026-11-21 (keyboard fixes)

Changes: Enter runs the field's action (`sendKeyChar('\n')`); Backspace sends a DEL key event; the Russian layout gains «ъ» and «ё»; hiding the keyboard stops a running recording and transcribes it; the audio read loop ends on a read error, and `AudioRecord` is released when `startRecording()` fails.

Build: `scripts/verify-build.ps1` passed, lint 0 errors, 57 warnings (two new `HardcodedText` for the new keys).

`scripts/phone-check.ps1 -ModelDir … -Dictation` on the Infinix X6833B, Android 14: debug app installed, Rust unit tests 9 of 9 passed on the phone, the real model transcribed the TTS phrase word for word (0.67–0.70 s per run), keyboard dictation put the phrase into Chrome 1.4 s after stop. Exit code 0.

Keys, debug keyboard on a local test page in Chrome, values read from the page:

- «ъ», «ё», «а» typed into a search field gave «ъёа».
- Enter in that search field submitted the form and inserted no line break; in a textarea it inserted a line break.
- Backspace after «a😀» left «a» (the whole emoji went). Backspace with «удалить» fully selected left an empty field.
- Holding Backspace for 1.5 s deleted six letters.
- Recording, then the system Back key hid the keyboard: `dumpsys audio` showed the app's MIC client while recording and a `rec stop` right after the hide; the phrase was transcribed and inserted into the still-focused textarea.

Gboard was restored, the debug keyboard disabled and the test tabs closed.

Not tested: hiding the keyboard by switching to another app or field (the text may then have nowhere to go), an `AudioRecord` read error, a failing `startRecording()`, the narrower Russian keys on a small screen, Android 8 and other phones.

# Verification: 2026-11-21 (release 0.3.1)

Version 0.3.1, versionCode 6, with the keyboard fixes above. `scripts/verify-build.ps1 -Tasks clean,assembleDebug,assembleRelease,lintDebug` passed from a clean tree: Rust rebuilt, lint 0 errors, 57 warnings. The first attempt showed that `clean` always failed, because the script cleared the ReadOnly attribute only inside `app/build`, not on the folder itself; that is fixed.

The release APK was signed outside Gradle with `apksigner` and the project key: certificate SHA-256 `2b2f7ac8014cd662d78eafd1638f18edba96f3c7525b7c805908a637851835ad` (the same as 0.3.0), schemes v2 and v3, `zipalign -c` passed, not debuggable. APK SHA-256 `90638a87773022b80dd4aab9a339cb8f08db3b3e7fca0d8776ce3134534ddc65`.

Infinix X6833B, Android 14: `adb install -r` put 0.3.1 over the signed 0.3.0 without uninstalling. The settings screen then showed the microphone, the model and the enabled keyboard as done, so the model and settings survived the update.

Not tested on the signed release itself: dictation and the new keys (they were checked on the debug build of the same code earlier today), Android 8 and other phones.
