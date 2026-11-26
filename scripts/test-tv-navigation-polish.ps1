param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "TV navigation polish target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.navigation.TvNavigationPolishConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "TV navigation polish: PASS"
} else {
    Write-Host "TV navigation polish: FAIL"
    exit $LASTEXITCODE
}

