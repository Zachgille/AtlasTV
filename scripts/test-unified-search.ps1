param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Unified search target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.search.UnifiedSearchConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Unified search: PASS" } else { Write-Host "Unified search: FAIL"; exit $LASTEXITCODE }

