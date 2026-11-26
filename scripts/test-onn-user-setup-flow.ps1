param(
    [string] $DeviceSerial = "",
    [switch] $UseEnvironmentCredentials,
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$PackageName = "com.atlas.atlastv"
$ActivityName = "com.atlas.atlastv/.MainActivity"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

function Get-OnnDevice {
    $lines = & adb devices | Select-Object -Skip 1
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^(adb-PUSA[^\s]+)\s+device$") { return $Matches[1] }
    }
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -match "^([^\s]+)\s+device$" -and $Matches[1] -notlike "emulator-*") { return $Matches[1] }
    }
    return $null
}

function Resolve-OnnDevice {
    param([string] $Serial)
    $device = if ([string]::IsNullOrWhiteSpace($Serial)) { Get-OnnDevice } else { $Serial }
    if ([string]::IsNullOrWhiteSpace($device)) { throw "Physical Onn device is required and was not found." }
    if ($device -like "emulator-*") { throw "Refusing to run Onn setup flow against emulator serial '$device'." }
    $connected = (& adb devices | Select-String -SimpleMatch "$device`tdevice")
    if (-not $connected) { throw "Requested Onn serial '$device' is not connected." }
    return $device
}

function Save-Screenshot {
    param([string] $Device, [string] $DumpDir, [string] $Name)
    $remote = "/sdcard/atlastv-$Name.png"
    $local = Join-Path $DumpDir "$Name.png"
    & adb -s $Device shell screencap -p $remote | Out-Null
    & adb -s $Device pull $remote $local | Out-Null
    & adb -s $Device shell rm $remote | Out-Null
    return $local
}

function Dump-UiXml {
    param([string] $Device, [string] $DumpDir, [string] $Name)
    $remote = "/sdcard/window.xml"
    $local = Join-Path $DumpDir "$Name.xml"
    & adb -s $Device shell uiautomator dump $remote | Out-Null
    & adb -s $Device pull $remote $local | Out-Null
    return $local
}

function Tap-NodeByResourceId {
    param([string] $Device, [string] $XmlPath, [string] $ResourceId)
    $text = Get-Content -LiteralPath $XmlPath -Raw
    $pattern = "resource-id=`"$([regex]::Escape($ResourceId))`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) { throw "Could not find UI node '$ResourceId' in $XmlPath." }
    $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
    $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
    & adb -s $Device shell input tap $x $y | Out-Null
    Start-Sleep -Seconds 1
}

function Assert-Text {
    param([string] $Text, [string] $Expected)
    if (-not $Text.Contains($Expected)) { throw "Expected visible UI text/tag '$Expected' was not found." }
}

function Send-Key {
    param([string] $Device, [string] $Key)
    & adb -s $Device shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds 350
}

function Escape-AdbInputText {
    param([string] $Value)
    return ($Value -replace "\\", "\\\\" -replace " ", "%s" -replace "&", "\&" -replace "<", "\<" -replace ">", "\>" -replace "\(", "\(" -replace "\)", "\)")
}

function Type-IfEnabled {
    param([string] $Device, [string] $Value)
    if ([string]::IsNullOrEmpty($Value)) { return }
    & adb -s $Device shell input text (Escape-AdbInputText $Value) | Out-Null
    Start-Sleep -Milliseconds 500
}

New-Item -ItemType Directory -Force -Path $DumpRoot | Out-Null
$device = Resolve-OnnDevice -Serial $DeviceSerial
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "Debug APK not found at $ApkPath. Run .\gradlew assembleDebug first." }

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $DumpRoot "onn-user-setup-$timestamp"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null

& adb -s $device install -r $ApkPath | Out-Null
& adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
& adb -s $device shell am start -n $ActivityName | Out-Null
Start-Sleep -Seconds 3
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "01-home" | Out-Null
$homeXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "01-home"
$homeText = Get-Content -LiteralPath $homeXml -Raw
if ($homeText.Contains("settings_screen")) {
    Write-Host "Settings screen already visible; continuing setup checks."
} else {
    Tap-NodeByResourceId -Device $device -XmlPath $homeXml -ResourceId "top_nav_item_settings"
    Start-Sleep -Seconds 2
}

$settingsShot = Save-Screenshot -Device $device -DumpDir $dumpDir -Name "02-settings"
$settingsXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "02-settings"
$settingsText = Get-Content -LiteralPath $settingsXml -Raw
Assert-Text $settingsText "Provider setup"
Assert-Text $settingsText "Save provider"
Assert-Text $settingsText "Test connection"
Assert-Text $settingsText "Sync Movies/Shows"
Assert-Text $settingsText "provider_actions_panel"
Assert-Text $settingsText "provider_sync_status"
Assert-Text $settingsText "provider_sync_stage"

if ($UseEnvironmentCredentials) {
    $baseUrl = $env:LUMAIRE_ONN_BASE_URL
    $username = $env:LUMAIRE_ONN_USERNAME
    $password = $env:LUMAIRE_ONN_PASSWORD
    if (!$baseUrl) { $baseUrl = $env:ATLASTV_ONN_BASE_URL }
    if (!$username) { $username = $env:ATLASTV_ONN_USERNAME }
    if (!$password) { $password = $env:ATLASTV_ONN_PASSWORD }
    if ([string]::IsNullOrWhiteSpace($baseUrl) -or [string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
        throw "UseEnvironmentCredentials requires LUMAIRE_ONN_BASE_URL, LUMAIRE_ONN_USERNAME, and LUMAIRE_ONN_PASSWORD."
    }
    # Focus first field from Settings and type via normal input. Values are not printed or saved in dumps.
    1..4 | ForEach-Object { Send-Key -Device $device -Key "KEYCODE_DPAD_LEFT" }
    Send-Key -Device $device -Key "KEYCODE_DPAD_CENTER"
    Type-IfEnabled -Device $device -Value $baseUrl
    Send-Key -Device $device -Key "KEYCODE_DPAD_DOWN"
    Type-IfEnabled -Device $device -Value $username
    Send-Key -Device $device -Key "KEYCODE_DPAD_DOWN"
    Type-IfEnabled -Device $device -Value $password
    Send-Key -Device $device -Key "KEYCODE_DPAD_RIGHT"
    Send-Key -Device $device -Key "KEYCODE_DPAD_UP"
    Send-Key -Device $device -Key "KEYCODE_DPAD_UP"
    Send-Key -Device $device -Key "KEYCODE_DPAD_CENTER"
    Start-Sleep -Seconds 1
}

$afterXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "03-settings-after-entry"
$afterText = Get-Content -LiteralPath $afterXml -Raw
Assert-Text $afterText "Saved provider"
Assert-Text $afterText "provider_sync_status"

Save-Screenshot -Device $device -DumpDir $dumpDir -Name "03-settings-after-entry" | Out-Null

Tap-NodeByResourceId -Device $device -XmlPath $afterXml -ResourceId "provider_test_connection_action"
Start-Sleep -Seconds 2
$testXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "04-after-test-connection"
$testText = Get-Content -LiteralPath $testXml -Raw
Assert-Text $testText "Connection:"
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "04-after-test-connection" | Out-Null

Tap-NodeByResourceId -Device $device -XmlPath $testXml -ResourceId "provider_sync_action"
Start-Sleep -Seconds 3
$syncXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "05-after-sync-pressed"
$syncText = Get-Content -LiteralPath $syncXml -Raw
Assert-Text $syncText "Sync:"
Assert-Text $syncText "Stage:"
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "05-after-sync-pressed" | Out-Null

$unsafeText = (Get-ChildItem -Path $dumpDir -File | Where-Object { $_.Extension -ne ".png" } | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
if ($unsafeText -match "/(live|movie|series)/(?!REDACTED/REDACTED)[^/`"'\s]+/[^/`"'\s]+/\d+") {
    throw "Raw playback path-like text found in Onn setup dumps."
}

Write-Host "Onn user setup flow: PASS"
Write-Host "Device: $device"
Write-Host "SettingsScreenshot: $settingsShot"
Write-Host "DumpDir: $dumpDir"
