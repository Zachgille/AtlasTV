param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Home rows target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.home.HomeRowsConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Home rows: PASS" } else { Write-Host "Home rows: FAIL"; exit $LASTEXITCODE }

