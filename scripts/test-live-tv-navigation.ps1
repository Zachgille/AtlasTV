param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Live TV navigation target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.livetv.LiveTvNavigationConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Live TV navigation: PASS"
} else {
    Write-Host "Live TV navigation: FAIL"
    exit $LASTEXITCODE
}

