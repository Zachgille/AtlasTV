param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Movie details target: $device (physical Onn)"
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.movies.MoviesNavigationConnectedTest,com.atlas.atlastv.ui.navigation.PlaybackBackBehaviorConnectedTest"
if ($LASTEXITCODE -eq 0) { Write-Host "Movie details: PASS" } else { Write-Host "Movie details: FAIL"; exit $LASTEXITCODE }

