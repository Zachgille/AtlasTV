param(
    [string] $DeviceSerial = "",
    [switch] $ClearAppData,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptRoot "onn-device.ps1")

$device = Resolve-OnnDevice -Serial $DeviceSerial
$projectRoot = Resolve-Path (Join-Path $ScriptRoot "..")
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        Write-Host "Building debug APK..."
        .\gradlew assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed." }
    }

    if (-not (Test-Path $apkPath)) {
        throw "Debug APK not found at $apkPath"
    }

    Write-Host "Installing debug APK on physical Onn..."
    adb -s $device install -r $apkPath | Out-Null

    if ($ClearAppData) {
        Write-Host "Clearing app data..."
        adb -s $device shell pm clear com.atlas.atlastv | Out-Null
    }

    Write-Host "Launching AtlasTV..."
    adb -s $device shell am start -n com.atlas.atlastv/.MainActivity | Out-Null
    Start-Sleep -Seconds 3

    $rawSettings = adb -s $device shell run-as com.atlas.atlastv cat files/provider_settings.json 2>$null
    if ([string]::IsNullOrWhiteSpace($rawSettings)) {
        throw "Provider settings file was not created. Check that debug-secrets.properties has local Xtream values."
    }

    $persisted = $rawSettings | ConvertFrom-Json
    $settings = $persisted.settings
    $hasSettings = -not [string]::IsNullOrWhiteSpace($settings.baseUrl) -and
        -not [string]::IsNullOrWhiteSpace($settings.username) -and
        -not [string]::IsNullOrWhiteSpace($settings.password)

    if (-not $hasSettings) {
        throw "Provider settings are still empty. Check that debug-secrets.properties has local Xtream values."
    }

    Write-Host "Device: physical Onn"
    Write-Host "Saved provider settings: present"
    Write-Host "Credentials printed: no"
    Write-Host "AtlasTV launched. Settings should show a redacted saved provider and Sync should be available."
} finally {
    Pop-Location
}
