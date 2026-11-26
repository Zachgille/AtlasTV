param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Series sort restore target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.series.SeriesSortRestoreConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Series sort restore: PASS"
} else {
    Write-Host "Series sort restore: FAIL"
    exit $LASTEXITCODE
}

