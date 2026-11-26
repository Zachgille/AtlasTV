param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Row search target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.movies.MoviesNavigationConnectedTest,com.atlas.atlastv.ui.series.SeriesNavigationConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Row search: PASS" } else { Write-Host "Row search: FAIL"; exit $LASTEXITCODE }

