param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Catalog stale refresh target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.settings.CatalogStaleRefreshConnectedTest"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Catalog stale refresh: PASS"
} else {
    Write-Host "Catalog stale refresh: FAIL"
    exit $LASTEXITCODE
}

