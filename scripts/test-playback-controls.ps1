param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Playback controls target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.navigation.PlaybackBackBehaviorConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Playback controls: PASS" } else { Write-Host "Playback controls: FAIL"; exit $LASTEXITCODE }

