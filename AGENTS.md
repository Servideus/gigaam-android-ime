# AGENTS.md

Guidance for AI coding agents in this repository. Build prerequisites and commands are in the
README ("Для разработчиков"). This file adds what is easy to get wrong.

## Layout

- `app/` — Kotlin: keyboard (`ime/`), settings (`SettingsActivity.kt`), model download
  (`data/ModelRepository.kt`), JNI bridge (`nativebridge/`).
- `native/gigaam_core/` — Rust core: mel features and ONNX Runtime (`load-dynamic`) on CPU.
- `scripts/verify-build.ps1` — debug build, release build and lint.
- `scripts/phone-check.ps1` — installs the debug app and runs the Rust tests on a connected phone.
- `eval/` — fixed TTS clips with reference texts (`samples.json`) and the measured history
  (`results.jsonl`, one line per clip and run).
- `.github/workflows/checks.yml` — CI on Linux: Rust unit tests on the host, plus the same build
  and lint as `verify-build.ps1`.
- `docs/verification.md` — dated log of what was checked on a device and what was not.

## Build and test on Windows

- Run `./scripts/verify-build.ps1` before calling a change done.
- Gradle can leave the ReadOnly attribute on folders under `app/build` and `.gradle`. The next
  build then fails with "Unable to delete directory". `verify-build.ps1` clears it; before a
  direct Gradle run, clear it yourself:

  ```powershell
  Get-ChildItem -Recurse -Force -Directory app\build, .gradle -ErrorAction SilentlyContinue |
      ForEach-Object { $_.Attributes = $_.Attributes -band -bnot [IO.FileAttributes]::ReadOnly }
  ```

- cargo-ndk 4 ignores `--manifest-path`. Run it from `native/gigaam_core`.
- Without MSVC `link.exe`, host `cargo test` does not link on Windows. Run the tests on the phone
  with `scripts/phone-check.ps1`, or rely on the CI run. When neither ran them, report Rust tests
  as compiled, not passed.

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
- Rust tests on the phone: `./scripts/phone-check.ps1 -ModelDir <folder with the five model files>`
  builds and installs the debug app, runs the unit tests, then the integration test: the real
  model transcribes a Windows TTS phrase from `scripts/phone-check-phrase.txt`. It needs a Russian
  Windows voice. Without `-ModelDir` the integration test is skipped; `-SkipBuild` reuses the last
  APK. A non-zero exit code means a failed step. Files stay in `/data/local/tmp/gigaam-check`
  (about 230 MB with the model); delete that folder to free the space.
- The test process aborts with `FORTIFY ... destroyed mutex` after printing its results. This is
  known; judge the run by the `test result:` line, as the script does.
- Dictation check: `./scripts/phone-check.ps1 -Dictation` (with `-ModelDir` on the first run, so
  the model can be copied into the debug app). It switches to the debug keyboard, plays the phrase
  from the PC speakers into a Chrome text field, and restores the previous keyboard. Ask the owner
  first; the phone must be unlocked and near the speakers.
- How the script reads the result: `uiautomator dump` sees neither the keyboard nor, in Chrome 158,
  the page content. The test page copies its field into the page title, and the script reads the
  title through Chrome's DevTools socket (`adb forward tcp:0 localabstract:chrome_devtools_remote`).
  That socket lists every open Chrome tab: read only the test tab, never print the others.
- The mic key position comes from the keyboard window's `touchable region` line in
  `dumpsys window windows` (screen coordinates), plus 26 dp from its top and right edges.
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

## Recognition quality (eval)

- Before and after changing the model, ONNX Runtime, the audio pipeline or the piece cutting, run
  `./scripts/phone-check.ps1 -SkipBuild -Eval` (about 2.5 minutes) and commit the new lines of
  `eval/results.jsonl`. Compare only rows with the same `model` hash and `device`.
- WER ignores case and punctuation; the normalization rules live in `eval/samples.json` and must
  not change, or old rows stop being comparable. `long.wav` repeats the five clips, so it is
  reported apart from their total.
- Scope: the Rust core on synthetic speech. Not covered: the keyboard's live piece path, real
  voices, accents, noise other than the one noisy clip.

## Reporting

- Separate what ran on a device from what was only built or compiled.
- After a device check, add a dated section to `docs/verification.md`: what passed and what was
  not tested.
