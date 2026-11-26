[CmdletBinding()]
param(
    [string]$Device,
    [string]$AdbPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$AdbPath) {
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdk = [regex]::Match((Get-Content -LiteralPath $localProperties -Raw), '(?m)^sdk\.dir=(.+)$').Groups[1].Value.Trim()
        if ($sdk) { $AdbPath = Join-Path ($sdk.Replace('\:', ':').Replace('\\', '\')) 'platform-tools\adb.exe' }
    }
    if (!$AdbPath) { $AdbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
}
$previewApk = Join-Path $projectRoot 'app\build\outputs\apk\preview\app-preview.apk'
if (!(Test-Path -LiteralPath $previewApk)) { throw 'Build the app first with .\gradlew assemblePreview.' }
if (!(Test-Path -LiteralPath $AdbPath)) { throw 'Pass the Android SDK platform-tools adb.exe path with -AdbPath.' }
if (!$Device) {
    $connected = @(& $AdbPath devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($connected.Count -ne 1) { throw 'Connect one authorized device, or pass its serial with -Device.' }
    $Device = ($connected[0] -split '\s+')[0]
}
& $AdbPath -s $Device install -r $previewApk
if ($LASTEXITCODE -ne 0) { throw 'Preview installation failed. No data-clear or uninstall was attempted.' }
$compiled = & $AdbPath -s $Device shell cmd package compile -m speed -f com.atlas.atlastv
if ($LASTEXITCODE -ne 0 -or !($compiled -match '^Success\s*$')) { throw 'Installed, but Android compilation did not succeed.' }
$packageState = & $AdbPath -s $Device shell dumpsys package com.atlas.atlastv
if ($LASTEXITCODE -ne 0 -or !($packageState -match '\[status=speed\]')) {
    throw 'Installed, but Android did not confirm optimized code for this device.'
}
Write-Output 'Preview installed; optimized Android compilation verified.'
