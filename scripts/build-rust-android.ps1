param(
    [ValidateSet("arm64-v8a")]
    [string]$Abi = "arm64-v8a",
    [ValidateSet("debug", "release")]
    [string]$Profile = "release"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$crateManifest = Join-Path $projectRoot "native\\gigaam_core\\Cargo.toml"
$crateDir = Split-Path -Parent $crateManifest
$jniLibsDir = Join-Path $projectRoot "app\\src\\main\\jniLibs"

if (!(Test-Path $crateManifest)) {
    throw "Rust crate manifest not found: $crateManifest"
}

if (!(Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo is required"
}

if (!(Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    throw "cargo-ndk is required. Install with: cargo install cargo-ndk"
}

New-Item -ItemType Directory -Force $jniLibsDir | Out-Null

Write-Host "Building Rust core for $Abi ($Profile)..."
Push-Location $crateDir
try {
    $args = @("ndk", "-t", $Abi, "-o", $jniLibsDir, "build")
    if ($Profile -eq "release") {
        $args += "--release"
    }
    & cargo @args
    if ($LASTEXITCODE -ne 0) {
        throw "cargo ndk build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host "Rust shared library output copied to: $jniLibsDir"
