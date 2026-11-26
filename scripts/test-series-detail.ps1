param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Series detail target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.series.SeriesNavigationConnectedTest,com.atlas.atlastv.ui.series.SeriesEpisodesConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Series detail: PASS" } else { Write-Host "Series detail: FAIL"; exit $LASTEXITCODE }

