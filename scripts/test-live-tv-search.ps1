param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Live TV search target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.livetv.LiveTvSearchConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Live TV search: PASS"
} else {
    Write-Host "Live TV search: FAIL"
    exit $LASTEXITCODE
}

