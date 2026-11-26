param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Provider settings sync target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.settings.ProviderSettingsSyncConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Provider settings sync: PASS"
} else {
    Write-Host "Provider settings sync: FAIL"
    exit $LASTEXITCODE
}

