param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Home image cards target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.home.HomeImageCardsConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Home image cards: PASS"
} else {
    Write-Host "Home image cards: FAIL"
    exit $LASTEXITCODE
}

