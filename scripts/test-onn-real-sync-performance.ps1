param(
    [string] $DeviceSerial = "",
    [int] $MaxSyncSeconds = 300,
    [int] $TimeoutSeconds = 420
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
    if ($device -like "emulator-*") { throw "Refusing to run real sync performance against emulator serial '$device'." }
    $connected = (& adb devices | Select-String -SimpleMatch "$device`tdevice")
    if (-not $connected) { throw "Requested Onn serial '$device' is not connected." }
    return $device
}

function Read-AppFile {
    param([string] $Device, [string] $FileName)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & adb -s $Device shell run-as $PackageName cat "files/$FileName" 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return ($output -join "`n")
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Read-SyncStatus {
    param([string] $Device)
    $json = Read-AppFile -Device $Device -FileName "provider_settings.json"
    if (-not $json) { return $null }
    try {
        return (($json | ConvertFrom-Json).syncStatus)
    } catch {
        return $null
    }
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

function Send-Key {
    param([string] $Device, [string] $Key)
    & adb -s $Device shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds 300
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

function Assert-DumpSafe {
    param([string] $DumpDir)
    $text = (Get-ChildItem -Path $DumpDir -File | Where-Object { $_.Extension -ne ".png" } | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
    $unsafePatterns = @("fake_user", "fake_pass", "provider.example", "/live/fake_", "/movie/fake_", "/series/fake_")
    foreach ($pattern in $unsafePatterns) {
        if ($text.Contains($pattern)) { throw "Unsafe text '$pattern' found under $DumpDir." }
    }
    if ($text -match "/(live|movie|series)/(?!REDACTED/REDACTED)[^/`"'\s]+/[^/`"'\s]+/\d+") {
        throw "Raw playback path-like text found under $DumpDir."
    }
}

New-Item -ItemType Directory -Force -Path $DumpRoot | Out-Null
$device = Resolve-OnnDevice -Serial $DeviceSerial
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "Debug APK not found at $ApkPath. Run .\gradlew assembleDebug first." }

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $DumpRoot "onn-sync-$timestamp"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null

& adb -s $device install -r $ApkPath | Out-Null
& adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
& adb -s $device shell am force-stop $PackageName | Out-Null
& adb -s $device shell am start -n $ActivityName | Out-Null
Start-Sleep -Seconds 3
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "01-home-before-sync" | Out-Null
$homeXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "01-home-before-sync"
$homeText = Get-Content -LiteralPath $homeXml -Raw
if ($homeText.Contains("settings_screen")) {
    Write-Host "Settings screen already visible; continuing visible sync flow."
} else {
    Tap-NodeByResourceId -Device $device -XmlPath $homeXml -ResourceId "top_nav_item_settings"
    Start-Sleep -Seconds 2
}

$settingsXml = Dump-UiXml -Device $device -DumpDir $dumpDir -Name "02-settings-before-sync"
$settingsText = Get-Content -LiteralPath $settingsXml -Raw
if (-not $settingsText.Contains("provider_sync_action")) { throw "Settings Sync action was not visible." }
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "02-settings-before-sync" | Out-Null

$beforeStatus = Read-SyncStatus -Device $device
if ($beforeStatus -and $beforeStatus.status -eq "Loading") {
    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $previousStartedAt = 0L
    if ($beforeStatus.lastStartedAtMs) { $previousStartedAt = [int64]$beforeStatus.lastStartedAtMs }
    $ageMs = $nowMs - $previousStartedAt
    if ($ageMs -lt ($MaxSyncSeconds * 1000)) {
        Write-Host "Existing persisted sync status is still within the active window; waiting until it is safe to restart visibly."
        do {
            Start-Sleep -Seconds 5
            $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $ageMs = $nowMs - $previousStartedAt
        } while ($ageMs -lt ($MaxSyncSeconds * 1000))
        Tap-NodeByResourceId -Device $device -XmlPath $settingsXml -ResourceId "provider_sync_action"
    } else {
        Write-Host "Existing sync status is stale; tapping Sync to restart through visible UI."
        Tap-NodeByResourceId -Device $device -XmlPath $settingsXml -ResourceId "provider_sync_action"
    }
} else {
    Tap-NodeByResourceId -Device $device -XmlPath $settingsXml -ResourceId "provider_sync_action"
}

Start-Sleep -Seconds 3
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "03-sync-started" | Out-Null
$startedStatus = Read-SyncStatus -Device $device

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$lastStatus = $startedStatus
do {
    Start-Sleep -Seconds 5
    $lastStatus = Read-SyncStatus -Device $device
    if ($lastStatus) {
        Write-Host ("status={0} stage='{1}' movies={2} series={3} totalMs={4}" -f $lastStatus.status, $lastStatus.currentStage, $lastStatus.moviesImported, $lastStatus.seriesImported, $lastStatus.totalDurationMs)
        if ($lastStatus.status -ne "Loading") { break }
    }
} while ((Get-Date) -lt $deadline)

if (-not $lastStatus) { throw "Provider sync status was not readable." }
if ($lastStatus.status -eq "Loading") { throw "Timed out waiting for visible UI-triggered sync to finish." }

$safeStatus = [pscustomobject]@{
    status = $lastStatus.status
    startedAtMs = $lastStatus.lastStartedAtMs
    completedAtMs = $lastStatus.lastCompletedAtMs
    totalDurationMs = $lastStatus.totalDurationMs
    accountInfoDurationMs = $lastStatus.accountInfoDurationMs
    vodCategoriesDurationMs = $lastStatus.vodCategoriesDurationMs
    vodStreamsDurationMs = $lastStatus.vodStreamsDurationMs
    seriesCategoriesDurationMs = $lastStatus.seriesCategoriesDurationMs
    seriesDurationMs = $lastStatus.seriesDurationMs
    filteringDurationMs = $lastStatus.filteringDurationMs
    roomWriteDurationMs = $lastStatus.roomWriteDurationMs
    vodCategoryCount = $lastStatus.vodCategoryCount
    vodStreamCount = $lastStatus.vodStreamCount
    seriesCategoryCount = $lastStatus.seriesCategoryCount
    seriesCount = $lastStatus.seriesCount
    moviesImported = $lastStatus.moviesImported
    seriesImported = $lastStatus.seriesImported
    excludedCategories = $lastStatus.excludedCategories
    excludedItems = $lastStatus.excludedItems
    errorCategory = $lastStatus.errorCategory
}
$safeJson = $safeStatus | ConvertTo-Json -Depth 6
Set-Content -LiteralPath (Join-Path $dumpDir "visible_ui_sync_status.json") -Value $safeJson -Encoding UTF8
Save-Screenshot -Device $device -DumpDir $dumpDir -Name "04-sync-finished" | Out-Null
Assert-DumpSafe -DumpDir $dumpDir

Write-Host "Onn real sync performance"
Write-Host "Device: $device"
Write-Host "DumpDir: $dumpDir"
Write-Host "Status: $($safeStatus.status)"
Write-Host "MoviesImported: $($safeStatus.moviesImported)"
Write-Host "SeriesImported: $($safeStatus.seriesImported)"
Write-Host "ExcludedCategories: $($safeStatus.excludedCategories)"
Write-Host "ExcludedItems: $($safeStatus.excludedItems)"
Write-Host ("TimingMs: total={0} account={1} vodCategories={2} vodStreams={3} seriesCategories={4} series={5} filtering={6} roomWrite={7}" -f `
    $safeStatus.totalDurationMs, $safeStatus.accountInfoDurationMs, $safeStatus.vodCategoriesDurationMs, $safeStatus.vodStreamsDurationMs, $safeStatus.seriesCategoriesDurationMs, $safeStatus.seriesDurationMs, $safeStatus.filteringDurationMs, $safeStatus.roomWriteDurationMs)
Write-Host ("FetchedCounts: vodCategories={0} vodStreams={1} seriesCategories={2} series={3}" -f `
    $safeStatus.vodCategoryCount, $safeStatus.vodStreamCount, $safeStatus.seriesCategoryCount, $safeStatus.seriesCount)
if ($safeStatus.errorCategory) { Write-Host "ErrorCategory: $($safeStatus.errorCategory)" }

if ($safeStatus.status -ne "Success") { throw "Onn visible UI sync failed: $($safeStatus.errorCategory)" }
if ($safeStatus.totalDurationMs -gt ($MaxSyncSeconds * 1000)) {
    throw "Onn visible UI sync exceeded ${MaxSyncSeconds}s: $($safeStatus.totalDurationMs)ms."
}

Write-Host "Onn real sync performance: PASS"
