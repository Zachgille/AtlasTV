param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Movies sort restore target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.movies.MoviesSortRestoreConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Movies sort restore: PASS"
} else {
    Write-Host "Movies sort restore: FAIL"
    exit $LASTEXITCODE
}

