<#
Builds and installs the debug app, then checks it on a phone connected over ADB.

Default run (silent; keyboard settings stay untouched):
  1. Build and install com.servideus.gigaamime.debug.
  2. Rust unit tests on the phone.
  3. With -ModelDir: the real model on the phone transcribes a Windows TTS phrase.
With -Dictation (plays sound from the PC speakers; the phone must be unlocked):
  4. The debug keyboard is switched on for the test, records the phrase from the speakers into a
     Chrome text field, and the previous keyboard is restored afterwards.
With -Eval (needs the model on the phone: -ModelDir now or in an earlier run):
  5. The model transcribes the clips in eval/ and the word error rate (WER) is measured.
     Each run appends one line per clip to eval/results.jsonl.

Line 1 of -PhraseFile (UTF-8) is the phrase; line 2 is text the result must contain.
-ModelDir is a folder with the five model files listed in ModelSpec.kt.
Files stay in /data/local/tmp/gigaam-check between runs, so the model is pushed only once.
Exit code 0 means every step that ran passed.
#>
param(
    [string] $ModelDir,
    [string] $PhraseFile = (Join-Path $PSScriptRoot 'phone-check-phrase.txt'),
    [switch] $SkipBuild,
    [switch] $Dictation,
    [switch] $Eval
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$package = 'com.servideus.gigaamime.debug'
$keyboard = "$package/com.servideus.gigaamime.ime.GigaamImeService"
$modelId = 'gigaam-v3-e2e-rnnt-int8'
$remote = '/data/local/tmp/gigaam-check'
$work = Join-Path $env:TEMP 'gigaam-phone-check'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$results = [ordered]@{}

function Invoke-Native {
    # Returns the output lines; throws on a non-zero exit code unless -AllowFail.
    param([string] $Exe, [string[]] $Arguments, [switch] $AllowFail)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) {
        throw "$Exe $($Arguments -join ' ') failed (exit code $LASTEXITCODE):`n$($out -join "`n")"
    }
    $out
}

function Invoke-Shell([string] $Command, [switch] $AllowFail) {
    Invoke-Native -Exe $adb -Arguments @('shell', $Command) -AllowFail:$AllowFail
}

function Invoke-DeviceTests([string] $Environment, [string] $TestArguments) {
    $command = "cd $remote && chmod 755 gigaam_core_tests && LD_LIBRARY_PATH=. " +
        "ORT_DYLIB_PATH=./libonnxruntime.so $Environment ./gigaam_core_tests $TestArguments 2>&1"
    $out = Invoke-Shell $command -AllowFail
    $out | ForEach-Object { Write-Host "    $_" }
    # A known FORTIFY abort after the results makes the exit code non-zero, so judge by the summary.
    $summary = $out | Where-Object { $_ -like 'test result:*' } | Select-Object -Last 1
    if ($summary -match ' (\d+) passed; (\d+) failed' -and [int]$Matches[2] -eq 0 -and [int]$Matches[1] -ge 1) {
        'PASS'
    } else { 'FAIL' }
}

function Get-SdkDir {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    $line = Get-Content (Join-Path $root 'local.properties') -ErrorAction SilentlyContinue |
        Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
    if (-not $line) { throw 'Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties.' }
    # local.properties escapes ':' and '\'.
    ($line.Substring(8) -replace '\\:', ':') -replace '\\+', '\'
}

function Sync-Files([IO.FileInfo[]] $Files, [string] $Dir) {
    # Pushes only the files whose size on the phone differs.
    $sizes = @{}
    Invoke-Shell "mkdir -p $Dir && stat -c '%n %s' $Dir/* 2>/dev/null" -AllowFail |
        ForEach-Object { $name, $size = $_ -split ' '; $sizes[($name -split '/')[-1]] = $size }
    foreach ($file in $Files) {
        if ($sizes[$file.Name] -ne [string]$file.Length) {
            Write-Host "    pushing $($file.Name)"
            Invoke-Native $adb @('push', $file.FullName, "$Dir/") | Out-Null
        }
    }
}

function Get-Words([string] $Text, $Rules) {
    $normalized = $Text.ToLowerInvariant()
    foreach ($rule in $Rules.replace) { $normalized = [regex]::Replace($normalized, $rule[0], $rule[1]) }
    , @([regex]::Matches($normalized, $Rules.word) | ForEach-Object { $_.Value })
}

function Get-WordErrors([string[]] $Reference, [string[]] $Hypothesis) {
    # Word-level edit distance: substituted, missing and extra words.
    $previous = [int[]](0..$Hypothesis.Count)
    for ($i = 1; $i -le $Reference.Count; $i++) {
        $current = New-Object int[] ($Hypothesis.Count + 1)
        $current[0] = $i
        for ($j = 1; $j -le $Hypothesis.Count; $j++) {
            $cost = [int]($Reference[$i - 1] -cne $Hypothesis[$j - 1])
            $current[$j] = [Math]::Min([Math]::Min($current[$j - 1] + 1, $previous[$j] + 1), $previous[$j - 1] + $cost)
        }
        $previous = $current
    }
    $previous[$Hypothesis.Count]
}

function Invoke-Eval {
    # Measures, it does not judge: only a missing model or an unstable transcription is a failure.
    $set = Get-Content (Join-Path $root 'eval\samples.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not ((Invoke-Shell "test -e $remote/model/v3_e2e_rnnt.yaml && echo found" -AllowFail) -match 'found')) {
        Write-Host '    No model on the phone: run once with -ModelDir.'
        return 'FAIL'
    }
    Sync-Files @(Get-ChildItem (Join-Path $root 'eval\audio') -File -Filter '*.wav') "$remote/eval"
    # Rows are comparable only for the same model, so the row names it by a hash of its files.
    $modelHash = ((Invoke-Shell "cat $remote/model/* | sha256sum") -join '').Substring(0, 12)
    $commit = (Invoke-Native 'git' @('-C', $root, 'rev-parse', '--short', 'HEAD')) -join ''
    $dirty = [bool](Invoke-Native 'git' @('-C', $root, 'status', '--porcelain', '--', '.', ':!eval/results.jsonl'))
    $device = ((Invoke-Shell 'getprop ro.product.model') -join '').Trim()
    $byId = @{}
    $set.samples | ForEach-Object { $byId[$_.id] = $_ }
    $rows = @()
    foreach ($sample in $set.samples) {
        $reference = if ($sample.parts) { ($sample.parts | ForEach-Object { $byId[$_].text }) -join ' ' } else { $sample.text }
        $out = Invoke-Shell ("cd $remote && LD_LIBRARY_PATH=. ORT_DYLIB_PATH=./libonnxruntime.so " +
            "GIGAAM_TEST_MODEL_DIR=model GIGAAM_TEST_WAV_PATH=eval/$($sample.file) " +
            './gigaam_core_tests --ignored integration --nocapture 2>&1') -AllowFail
        $runs = @($out | ForEach-Object {
            if ($_ -match '^run \d+: \{.*"total_ms":(\d+).*\} text="(.*)"$') {
                [pscustomobject]@{ ms = [int]$Matches[1]; text = $Matches[2] -replace '\\"', '"' }
            }
        })
        # The test transcribes three times; the same audio must give the same text.
        if ($runs.Count -lt 3 -or -not $runs[0].text -or @($runs.text | Select-Object -Unique).Count -ne 1) {
            Write-Host "    $($sample.id): no stable transcription"
            $out | Select-Object -Last 5 | ForEach-Object { Write-Host "      $_" }
            return 'FAIL'
        }
        $words = Get-Words $reference $set.normalize
        $errors = Get-WordErrors $words (Get-Words $runs[0].text $set.normalize)
        $row = [pscustomobject][ordered]@{
            date = (Get-Date).ToString('yyyy-MM-dd HH:mm'); commit = $commit; dirty = $dirty; device = $device
            model = $modelHash; sample = $sample.id; words = $words.Count; errors = $errors
            wer = [Math]::Round(100 * $errors / $words.Count, 1); first_ms = $runs[0].ms
            warm_ms = ($runs[1..2].ms | Measure-Object -Minimum).Minimum; text = $runs[0].text
        }
        Write-Host ('    {0,-13} {1,4} words {2,3} errors {3,5:N1}%   first {4,6} ms   warm {5,6} ms' -f
            $row.sample, $row.words, $row.errors, $row.wer, $row.first_ms, $row.warm_ms)
        $rows += $row
    }
    $lines = ($rows | ForEach-Object { $_ | ConvertTo-Json -Compress }) -join "`n"
    [IO.File]::AppendAllText((Join-Path $root 'eval\results.jsonl'), "$lines`n", (New-Object Text.UTF8Encoding $false))
    # long.wav repeats the five clips, so it is reported apart from their total.
    $clips = @($rows | Where-Object { $_.sample -ne 'long' })
    $clipsWer = 100 * ($clips | Measure-Object errors -Sum).Sum / ($clips | Measure-Object words -Sum).Sum
    'WER {0:N1}% on {1} clips, {2:N1}% on long.wav' -f $clipsWer, $clips.Count, ($rows | Where-Object { $_.sample -eq 'long' }).wer
}

function Write-DeviceFile([string] $Path, [string] $Text) {
    # Files run by the phone's shell need LF line ends and no BOM.
    [IO.File]::WriteAllText($Path, ($Text -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
}

function New-PhraseWav([string] $Text) {
    Add-Type -AssemblyName System.Speech
    $wav = Join-Path $work 'phrase.wav'
    $tts = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $voice = $tts.GetInstalledVoices() |
            Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'ru-RU' } | Select-Object -First 1
        if (-not $voice) { throw 'No Russian Windows voice is installed (Settings > Time & language > Speech).' }
        $tts.SelectVoice($voice.VoiceInfo.Name)
        # The Rust WAV reader does not resample: the model needs 16 kHz mono.
        $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
            [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
        $tts.SetOutputToWaveFile($wav, $format)
        $tts.Speak($Text)
    } finally { $tts.Dispose() }
    $wav
}

function Get-TestTab([int] $Port, [string] $Token) {
    # Chrome's DevTools socket lists every open tab; keep only the one with this run's token.
    # The list goes into a variable first: PowerShell 5.1 would pipe the whole array as one item.
    $tabs = Invoke-RestMethod "http://127.0.0.1:$Port/json/list" -TimeoutSec 5
    $tabs | Where-Object { $_.url -like "*$Token*" } | Select-Object -First 1
}

function Get-FieldText([int] $Port, [string] $Token) {
    # The test page copies its text field into the page title.
    $tab = Get-TestTab $Port $Token
    if ($tab -and $tab.title -ne $Token) { $tab.title }
}

function Test-Dictation([string] $Wav, [string] $Expect) {
    if ((Invoke-Shell 'dumpsys window' -AllowFail) -match 'isKeyguardShowing=true') {
        Write-Host '    The phone is locked: unlock it and run again.'
        return 'FAIL'
    }
    Write-DeviceFile (Join-Path $work 'copy-model.sh') @'
# Copies the model from the test folder into the debug app when a file is missing or differs in size.
pkg=$1; dir=files/models/$2; src=/data/local/tmp/gigaam-check/model
if [ ! -e "$src/v3_e2e_rnnt.yaml" ]; then
  run-as "$pkg" test -e "$dir/v3_e2e_rnnt.yaml" && { echo model-ready; exit 0; }
  echo "No model in the app or in $src: run once with -ModelDir."; exit 3
fi
run-as "$pkg" mkdir -p "$dir" || exit 4
for f in "$src"/*; do
  n=${f##*/}
  if [ "$(run-as "$pkg" stat -c %s "$dir/$n" 2>/dev/null)" != "$(stat -c %s "$f")" ]; then
    echo "copying $n"
    cat "$f" | run-as "$pkg" sh -c "cat > $dir/$n" || exit 5
  fi
done
echo model-ready
'@
    Invoke-Native $adb @('push', (Join-Path $work 'copy-model.sh'), "$remote/") | Out-Null
    $out = Invoke-Shell "sh $remote/copy-model.sh $package $modelId" -AllowFail
    $out | ForEach-Object { Write-Host "    $_" }
    if (-not ($out -match '^model-ready')) { return 'FAIL' }

    $previousKeyboard = ((Invoke-Shell 'settings get secure default_input_method') -join '').Trim()
    $wasEnabled = @(Invoke-Shell 'ime list -s' | ForEach-Object { $_.Trim() }) -contains $keyboard
    $port = [int]((Invoke-Native $adb @('forward', 'tcp:0', 'localabstract:chrome_devtools_remote')) -join '')
    try {
        # A fresh process picks up the model copied above.
        Invoke-Shell "am force-stop $package; pm grant $package android.permission.RECORD_AUDIO" | Out-Null
        Invoke-Shell "ime enable $keyboard; ime set $keyboard; logcat -c; input keyevent KEYCODE_WAKEUP" | Out-Null
        # The unique token makes Chrome open a new, empty tab instead of an old one with text in it.
        $token = "gigaam-check-$([DateTime]::Now.Ticks)"
        $page = "data:text/html,<title>$token</title><textarea%20style=width:95vw;height:40vh%20" +
            'oninput=document.title=this.value></textarea>'
        Invoke-Shell "am start -a android.intent.action.VIEW -n com.android.chrome/com.google.android.apps.chrome.Main -d '$page'" | Out-Null
        Start-Sleep -Seconds 4
        $size = [regex]::Match(((Invoke-Shell 'wm size') -join ' '), '(\d+)x(\d+)')
        $width, $height = [int]$size.Groups[1].Value, [int]$size.Groups[2].Value
        Invoke-Shell ('input tap {0} {1}' -f [int]($width / 2), [int]($height * 0.28)) | Out-Null
        Start-Sleep -Seconds 2
        if (-not (Get-TestTab $port $token)) {
            Write-Host '    The test page did not open in Chrome.'
            return 'FAIL'
        }
        if (Get-FieldText $port $token) {
            Write-Host '    The text field is not empty before dictation.'
            return 'FAIL'
        }

        # The mic key is the top-right toolbar button: 6 dp padding plus half of its 40 dp size.
        # The keyboard window's "touchable region" line gives its bounds in screen coordinates.
        $windows = (Invoke-Shell 'dumpsys window windows') -join "`n"
        $block = [regex]::Match($windows, '(?s)Window\{[^}]* InputMethod\}:(.*?)(\n  Window #|\z)').Groups[1].Value
        $region = [regex]::Match($block, 'touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)')
        $shown = ((Invoke-Shell 'dumpsys input_method') -join "`n") -match 'mInputShown=true'
        if (-not ($shown -and $region.Success)) {
            Write-Host '    The debug keyboard did not open over the text field.'
            return 'FAIL'
        }
        $dp = [int](((Invoke-Shell 'wm density') -join ' ') -replace '.*\D(\d+)\D*$', '$1') / 160
        $micX = [int]$region.Groups[3].Value - [int](26 * $dp)
        $micY = [int]$region.Groups[2].Value + [int](26 * $dp)
        Write-Host "    keyboard top $($region.Groups[2].Value), mic key at $micX,$micY"

        Invoke-Shell "input tap $micX $micY" | Out-Null
        Start-Sleep -Milliseconds 800
        (New-Object Media.SoundPlayer $Wav).PlaySync()
        Start-Sleep -Milliseconds 300
        Invoke-Shell "input tap $micX $micY" | Out-Null
        $stop = Get-Date
        # Long recordings arrive in pieces, so wait for the expected text, not for any text.
        $passed = $false
        while (-not $passed -and ((Get-Date) - $stop).TotalSeconds -lt 30) {
            $text = Get-FieldText $port $token
            $passed = $text -and $text.IndexOf($Expect, [StringComparison]::OrdinalIgnoreCase) -ge 0
            if (-not $passed) { Start-Sleep -Milliseconds 300 }
        }
        $timing = Invoke-Shell 'logcat -d -s GigaamImeService:I' -AllowFail |
            Where-Object { $_ -match 'TranscribeTiming' } | Select-Object -Last 1
        Write-Host "    text: $text"
        Write-Host ('    {0} {1:N1} s after stop' -f $(if ($passed) { 'expected text seen' } else { 'gave up' }),
            ((Get-Date) - $stop).TotalSeconds)
        if ($timing) { Write-Host "    app log: $($timing -replace '^.*TranscribeTiming', 'TranscribeTiming')" }
        if ($passed) { 'PASS' } else { 'FAIL' }
    } finally {
        Invoke-Shell 'input keyevent KEYCODE_BACK; sleep 0.7; input keyevent KEYCODE_BACK' -AllowFail | Out-Null
        Invoke-Native $adb @('forward', '--remove', "tcp:$port") -AllowFail | Out-Null
        Invoke-Shell "ime set $previousKeyboard" -AllowFail | Out-Null
        if (-not $wasEnabled) { Invoke-Shell "ime disable $keyboard" -AllowFail | Out-Null }
        Write-Host "    keyboard restored: $((Invoke-Shell 'settings get secure default_input_method') -join '')"
    }
}

$sdk = Get-SdkDir
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$devices = @(Invoke-Native $adb @('devices') | Where-Object { $_ -match '^\S+\s+device$' })
if ($devices.Count -ne 1) { throw "Connect exactly one phone with USB debugging allowed (found $($devices.Count))." }
New-Item -ItemType Directory -Force $work | Out-Null
$lines = @(Get-Content -LiteralPath $PhraseFile -Encoding UTF8 | Where-Object { $_.Trim() })
if ($lines.Count -lt 2) { throw "$PhraseFile needs two lines: the phrase and the text the result must contain." }
$phrase, $expect = $lines[0], $lines[1]

Write-Host '== 1. Build and install the debug app'
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'verify-build.ps1') -Tasks assembleDebug }
$out = Invoke-Native $adb @('install', '-r', '-t', $apk)
$results['Install debug app'] = if ($out -match '^Success') { 'PASS' } else { 'FAIL' }

Write-Host '== 2. Rust unit tests on the phone'
$crate = Join-Path $root 'native\gigaam_core'
$gradleFile = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$ndkVersion = [regex]::Match($gradleFile, 'ndkVersion\s*=\s*"([^"]+)"').Groups[1].Value
$previousNdk = $env:ANDROID_NDK_HOME
if (-not $previousNdk) { $env:ANDROID_NDK_HOME = Join-Path $sdk "ndk\$ndkVersion" }
Push-Location $crate
try { Invoke-Native 'cargo' @('ndk', '-t', 'arm64-v8a', 'test', '--release', '--no-run') | Out-Null }
finally { Pop-Location; $env:ANDROID_NDK_HOME = $previousNdk }
# cargo-ndk does not report the test binary; the newest one without an extension is this build.
$testBinary = Get-ChildItem (Join-Path $crate 'target\aarch64-linux-android\release\deps') -File -Filter 'gigaam_core-*' |
    Where-Object { -not $_.Extension } | Sort-Object LastWriteTime | Select-Object -Last 1

Add-Type -AssemblyName System.IO.Compression.FileSystem
$ort = Join-Path $work 'libonnxruntime.so'
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('lib/arm64-v8a/libonnxruntime.so'), $ort, $true)
} finally { $zip.Dispose() }

Invoke-Shell "mkdir -p $remote/model" | Out-Null
Invoke-Native $adb @('push', $testBinary.FullName, "$remote/gigaam_core_tests") | Out-Null
Invoke-Native $adb @('push', $ort, "$remote/") | Out-Null
$results['Rust unit tests on the phone'] = Invoke-DeviceTests '' ''

Write-Host '== 3. Real model transcribes a TTS phrase on the phone'
$wav = if ($ModelDir -or $Dictation) { New-PhraseWav $phrase }
if (-not $ModelDir) {
    $results['Model transcribes TTS phrase'] = 'SKIPPED (no -ModelDir)'
} else {
    $files = @(Get-ChildItem -LiteralPath $ModelDir -File -Filter 'v3_e2e_rnnt*')
    if ($files.Count -lt 5) { throw "$ModelDir must hold the five model files listed in ModelSpec.kt." }
    Sync-Files $files "$remote/model"
    $expectFile = Join-Path $work 'expect.txt'
    Write-DeviceFile $expectFile $expect
    Invoke-Native $adb @('push', $wav, $expectFile, "$remote/") | Out-Null
    $results['Model transcribes TTS phrase'] = Invoke-DeviceTests `
        'GIGAAM_TEST_MODEL_DIR=model GIGAAM_TEST_WAV_PATH=phrase.wav GIGAAM_TEST_EXPECT_CONTAINS=$(cat expect.txt)' `
        '--ignored integration --nocapture'
}

Write-Host '== 4. Dictation through the debug keyboard'
$results['Dictation through keyboard'] = if ($Dictation) { Test-Dictation $wav $expect } else { 'SKIPPED (no -Dictation)' }

Write-Host '== 5. Word error rate on the eval clips'
$results['Eval (WER)'] = if ($Eval) { Invoke-Eval } else { 'SKIPPED (no -Eval)' }

Write-Host '== Summary'
$results.GetEnumerator() | ForEach-Object { Write-Host ('{0,-32} {1}' -f $_.Key, $_.Value) }
# Set the exit code explicitly: the last adb call may have left the FORTIFY abort code behind.
if ($results.Values -contains 'FAIL') { exit 1 } else { exit 0 }
