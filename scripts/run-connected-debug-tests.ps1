param(
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot

function Get-OnnDevice {
    $lines = & adb devices | Select-Object -Skip 1
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^(adb-PUSA[^\s]+)\s+device$") { return $Matches[1] }
    }
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^([^\s]+)\s+device$" -and $Matches[1] -notlike "emulator-*") {
            return $Matches[1]
        }
    }
    return $null
}

function Resolve-OnnDevice {
    param([string] $Serial)
    $device = if ([string]::IsNullOrWhiteSpace($Serial)) { Get-OnnDevice } else { $Serial }
    if ([string]::IsNullOrWhiteSpace($device)) { throw "Physical Onn device is required and was not found." }
    if ($device -like "emulator-*") { throw "Refusing to run connected debug tests against emulator serial '$device'." }
    $connected = (& adb devices | Select-String -SimpleMatch "$device`tdevice")
    if (-not $connected) { throw "Requested Onn serial '$device' is not connected." }
    return $device
}

$device = Resolve-OnnDevice -Serial $DeviceSerial
$env:ANDROID_SERIAL = $device
Write-Host "Running connectedDebugAndroidTest on physical Onn $device."
Set-Location $RepoRoot
.\gradlew connectedDebugAndroidTest
