param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Series episodes target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.series.SeriesEpisodesConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Series episodes: PASS"
} else {
    Write-Host "Series episodes: FAIL"
    exit $LASTEXITCODE
}

