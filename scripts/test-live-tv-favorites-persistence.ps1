param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Live TV favorites persistence target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.livetv.LiveTvFavoritesPersistenceConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Live TV favorites persistence: PASS"
} else {
    Write-Host "Live TV favorites persistence: FAIL"
    exit $LASTEXITCODE
}

