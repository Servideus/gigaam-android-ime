# AGENTS.md

Guidance for AI coding agents in this repository. Build prerequisites and commands are in the
README ("Для разработчиков"). This file adds what is easy to get wrong.

## Layout

- `app/` — Kotlin: keyboard (`ime/`), settings (`SettingsActivity.kt`), model download
  (`data/ModelRepository.kt`), JNI bridge (`nativebridge/`).
- `native/gigaam_core/` — Rust core: mel features and ONNX Runtime (`load-dynamic`) on CPU.
- `scripts/verify-build.ps1` — debug build, release build and lint.
- `.github/workflows/checks.yml` — CI on Linux: Rust unit tests on the host, plus the same build
  and lint as `verify-build.ps1`.
- `docs/verification.md` — dated log of what was checked on a device and what was not.

## Build and test on Windows

- Run `./scripts/verify-build.ps1` before calling a change done.
- Gradle can leave the ReadOnly attribute on folders under `app/build` and `.gradle`. The next
  build then fails with "Unable to delete directory". Clear it before building:

  ```powershell
  Get-ChildItem -Recurse -Force -Directory app\build, .gradle -ErrorAction SilentlyContinue |
      ForEach-Object { $_.Attributes = $_.Attributes -band -bnot [IO.FileAttributes]::ReadOnly }
  ```

- cargo-ndk 4 ignores `--manifest-path`. Run it from `native/gigaam_core`.
- Without MSVC `link.exe`, host `cargo test` does not link on Windows. Compile the tests for the
  phone (`cargo ndk -t arm64-v8a test --release --no-run`) and run them there, or rely on the CI
  run. When neither ran them, report Rust tests as compiled, not passed.

## Device checks over ADB

- Test with the debug build. Package `com.servideus.gigaamime.debug`, IME
  `com.servideus.gigaamime.debug/com.servideus.gigaamime.ime.GigaamImeService`. It installs next to
  the release and has its own model and settings. `run-as` works only on the debug build.
- Do not change the default keyboard without the owner's consent. Restore it after testing.
- Never hide a model by renaming its folder. `ModelRepository.deleteRetiredModels()` runs when the
  settings screen opens and deletes every unknown folder under `files/models`. Move the model
  outside `files/models` instead.
- Copy a model without downloading: push the file to `/data/local/tmp`, then
  `adb shell "cat /data/local/tmp/<file> | run-as com.servideus.gigaamime.debug sh -c 'cat > files/models/<model-id>/<file>'"`.
- Rust tests on the phone: push the test binary
  (`native/gigaam_core/target/aarch64-linux-android/release/deps/gigaam_core-*`) and
  `libonnxruntime.so` (from `app/build/intermediates/merged_native_libs/debug/.../arm64-v8a`) to
  `/data/local/tmp`. Run with `LD_LIBRARY_PATH` and `ORT_DYLIB_PATH` set. The integration test is
  `--ignored integration` with `GIGAAM_TEST_MODEL_DIR` and `GIGAAM_TEST_WAV_PATH`. A
  `FORTIFY ... destroyed mutex` abort after the results is known and is not a test failure.
- Dictation check: `uiautomator dump` does not include the IME window, and the Settings search field
  cuts text at about 200 characters. Open a Chrome `data:text/html,<textarea>` page, dictate, then
  read the EditText text from `uiautomator dump`.
- Timing: `adb logcat -s GigaamImeService:I`, line `TranscribeTiming`. Some firmware (the Infinix
  test phone) sets `log.tag=I`, so `Log.d` never appears. Log with `Log.i` or higher.
- Screenshots: PowerShell `>` corrupts the binary output of `adb exec-out screencap -p`. Use Bash.
- Settings screen state: `am start` on an already resumed activity does not call `onResume`; press
  HOME first. `pm revoke` marks the permission USER_SET, so one later denial already counts as
  "don't ask again".
- Model download goes through the system DownloadManager into
  `/sdcard/Android/data/<package>/files/model-download`, then is hashed and moved into
  `files/models`. Staged files are preallocated to full size, so their size is not progress. During
  a download the screen polls every second and `uiautomator dump` fails ("could not get idle
  state"): use screenshots. `am kill` does not stop the app; use `run-as <package> kill <pid>`. To
  test resume, cut the network a few seconds after the start.

## Decisions

- The encoder runs on CPU with 6 threads (`ENCODER_THREADS` in `gigaam.rs`). On Helio G99 with
  e2e-RNNT int8, CPU takes about 0.1 × audio length. XNNPACK + NNAPI was about 2 × slower, mostly
  because of NNAPI, so the acceleration switch and speed profiles were removed (2026-11-14). To
  compare providers again, patch the session builder temporarily.
- The debug build has its own application ID suffix, so it can sit next to the signed release.
- Gradle produces an unsigned release APK. The owner signs releases with `apksigner`. Signing keys
  and passwords never go into the repository; `.gitignore` blocks `*.jks`, `*.keystore` and
  `*password*.txt`.

## Reporting

- Separate what ran on a device from what was only built or compiled.
- After a device check, add a dated section to `docs/verification.md`: what passed and what was
  not tested.
