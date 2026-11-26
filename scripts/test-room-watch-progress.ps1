param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial

Set-Location $RepoRoot
$env:ANDROID_SERIAL = $device
Write-Host "Room watch progress target: $device (physical Onn)"

.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.data.db.AtlasDatabaseMigrationConnectedTest,com.atlas.atlastv.data.db.WatchProgressRoomStoreConnectedTest"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Room watch progress connected tests: FAIL"
    exit $LASTEXITCODE
}

powershell -ExecutionPolicy Bypass -File .\scripts\test-watch-progress.ps1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Room watch progress debug commands: FAIL"
    exit $LASTEXITCODE
}

Write-Host "Room watch progress: PASS"

