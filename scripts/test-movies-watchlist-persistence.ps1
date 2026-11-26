param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Movies watchlist persistence target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.movies.MoviesWatchlistPersistenceConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Movies watchlist persistence: PASS"
} else {
    Write-Host "Movies watchlist persistence: FAIL"
    exit $LASTEXITCODE
}

