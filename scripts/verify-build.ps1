$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$socketDirectory = Join-Path $projectRoot '.gradle/socket-tmp'
New-Item -ItemType Directory -Force $socketDirectory | Out-Null
$previousOptions = $env:JAVA_TOOL_OPTIONS
try {
    # Java's AF_UNIX pipe can fail with a Windows 8.3 TEMP path.
    $env:JAVA_TOOL_OPTIONS = "$previousOptions `"-Djdk.net.unixdomain.tmpdir=$socketDirectory`""
    Push-Location $projectRoot
    # PowerShell 5.1 turns redirected native stderr (e.g. "Picked up JAVA_TOOL_OPTIONS") into
    # terminating errors under 'Stop'; Gradle's exit code is checked explicitly instead.
    $ErrorActionPreference = 'Continue'
    try {
        & './gradlew.bat' --no-daemon assembleDebug assembleRelease lintDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed.' }
    } finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS = $previousOptions }
