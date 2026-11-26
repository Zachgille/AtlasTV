param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Movies watchlist target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.movies.MoviesWatchlistConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Movies watchlist: PASS"
} else {
    Write-Host "Movies watchlist: FAIL"
    exit $LASTEXITCODE
}

