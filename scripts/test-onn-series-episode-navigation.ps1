param([string] $DeviceSerial = "")

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptRoot "onn-device.ps1")
$device = Resolve-OnnDevice -Serial $DeviceSerial
$projectRoot = Resolve-Path (Join-Path $ScriptRoot "..")

Push-Location $projectRoot
try {
    Stop-LumaireOnnApp -Serial $device
    $env:ANDROID_SERIAL = $device
    .\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.ui.series.SeriesEpisodesConnectedTest"
    if ($LASTEXITCODE -ne 0) { throw "Onn series episode navigation connected test failed." }
    Write-Host "Onn series episode navigation: pass"
} finally {
    Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue
    Pop-Location
}
