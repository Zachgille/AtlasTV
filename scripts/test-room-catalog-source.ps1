param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Room catalog source target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.data.db.RoomCatalogFallbackConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Room catalog source: PASS" } else { Write-Host "Room catalog source: FAIL"; exit $LASTEXITCODE }

