param(
    [string] $DeviceSerial = "",
    [int] $PlaybackDurationSeconds = 45,
    [int] $TimeoutSeconds = 1800,
    [switch] $ForceSync
)

$ErrorActionPreference = "Stop"

$PackageName = "com.atlas.atlastv"
$ActivityName = "com.atlas.atlastv/.MainActivity"
$ReceiverName = "com.atlas.atlastv/.debug.DebugCommandReceiver"
$ActionName = "com.atlas.atlastv.DEBUG_COMMAND"
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

function Invoke-DebugCommand {
    param(
        [string] $Device,
        [string] $Command,
        [int] $DurationSeconds = 0,
        [int] $Timeout = 300
    )
    & adb -s $Device shell run-as $PackageName rm -f files/debug_command_result.json | Out-Null
    $args = @("-s", $Device, "shell", "am", "broadcast", "-n", $ReceiverName, "-a", $ActionName, "--es", "command", $Command)
    if ($DurationSeconds -gt 0) { $args += @("--es", "duration_seconds", "$DurationSeconds") }
    & adb @args | Out-Null
    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        Start-Sleep -Seconds 1
        $candidate = Read-AppFile -Device $Device -FileName "debug_command_result.json"
        if ($candidate) {
            try {
                $parsed = $candidate | ConvertFrom-Json
                if ($parsed.command -eq $Command) { return $candidate }
            } catch {
            }
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for '$Command'."
}

function Save-AppOutputs {
    param([string] $Device, [string] $DumpDir)
    $files = @(
        "debug_command_result.json",
        "debug_state.json",
        "debug_catalog_summary.json",
        "debug_catalog_audit.json",
        "debug_search_reachability.json",
        "debug_watch_progress_state.json",
        "debug_last_error.json"
    )
    foreach ($file in $files) {
        $content = Read-AppFile -Device $Device -FileName $file
        if ($content) {
            Set-Content -LiteralPath (Join-Path $DumpDir $file) -Value $content -Encoding UTF8
        }
    }
}

function Assert-DumpSafe {
    param([string] $DumpDir)
    $text = (Get-ChildItem -Path $DumpDir -File | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
    $unsafePatterns = @(
        "fake_user",
        "fake_pass",
        "provider.example",
        "/live/fake_",
        "/movie/fake_",
        "/series/fake_"
    )
    foreach ($pattern in $unsafePatterns) {
        if ($text.Contains($pattern)) { throw "Unsafe text '$pattern' found under $DumpDir." }
    }
    if ($text -match "/(live|movie|series)/(?!REDACTED/REDACTED)[^/`"'\s]+/[^/`"'\s]+/\d+") {
        throw "Raw playback path-like text found under $DumpDir."
    }
}

function Read-ProviderSyncStatus {
    param([string] $Device)
    $json = Read-AppFile -Device $Device -FileName "provider_settings.json"
    if (-not $json) { return $null }
    try {
        $state = $json | ConvertFrom-Json
        return $state.syncStatus
    } catch {
        return $null
    }
}

New-Item -ItemType Directory -Force -Path $DumpRoot | Out-Null

$device = if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { Get-OnnDevice } else { $DeviceSerial }
if ([string]::IsNullOrWhiteSpace($device)) {
    throw "Physical Onn device was not found. Pass -DeviceSerial explicitly if needed."
}
if ($device -like "emulator-*") {
    throw "Refusing to run Onn acceptance against emulator serial '$device'."
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Debug APK not found at $ApkPath. Run .\gradlew assembleDebug first."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $DumpRoot "real-provider-onn-acceptance-$timestamp"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null

& adb -s $device install -r $ApkPath | Out-Null
& adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
& adb -s $device shell am start -n $ActivityName | Out-Null

Write-Host "Device: $device"
Write-Host "DumpDir: $dumpDir"

Write-Host ""
Write-Host "== saved provider sync =="
$syncJson = $null
$sync = $null
if (-not $ForceSync) {
    $existingStatus = Read-ProviderSyncStatus -Device $device
    if ($existingStatus -and $existingStatus.status -eq "Success" -and (($existingStatus.moviesImported -gt 0) -or ($existingStatus.seriesImported -gt 0))) {
        Write-Host "Using existing successful app-private provider sync status. Pass -ForceSync to refresh."
        $sync = [pscustomobject]@{
            status = "success"
            catalogImport = [pscustomobject]@{
                moviesImported = $existingStatus.moviesImported
                seriesImported = $existingStatus.seriesImported
                excludedCategories = $existingStatus.excludedCategories
                excludedItems = $existingStatus.excludedItems
            }
            lastErrorCategory = $null
        }
        $syncJson = ([pscustomobject]@{
            status = $existingStatus.status
            moviesImported = $existingStatus.moviesImported
            seriesImported = $existingStatus.seriesImported
            excludedCategories = $existingStatus.excludedCategories
            excludedItems = $existingStatus.excludedItems
            lastStartedAtMs = $existingStatus.lastStartedAtMs
            lastCompletedAtMs = $existingStatus.lastCompletedAtMs
        } | ConvertTo-Json -Depth 6)
    }
}
try {
if (-not $sync) {
    $syncAttemptStartedAtMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $syncJson = Invoke-DebugCommand -Device $device -Command "sync_saved_provider_catalog" -Timeout $TimeoutSeconds
    $sync = $syncJson | ConvertFrom-Json
    if ($sync.status -ne "success" -and $sync.lastErrorCategory -eq "MissingProviderSettings") {
        Write-Host "Saved provider settings missing; seeding from local debug config without printing credentials."
        $seedJson = Invoke-DebugCommand -Device $device -Command "seed_saved_provider_from_debug_config" -Timeout 120
        Set-Content -LiteralPath (Join-Path $dumpDir "seed_saved_provider_from_debug_config.json") -Value $seedJson -Encoding UTF8
        $seed = $seedJson | ConvertFrom-Json
        if ($seed.status -ne "success") { throw "Could not seed saved provider settings: $($seed.lastErrorCategory)" }
        $syncJson = Invoke-DebugCommand -Device $device -Command "sync_saved_provider_catalog" -Timeout $TimeoutSeconds
        $sync = $syncJson | ConvertFrom-Json
    }
}
} catch {
    $status = Read-ProviderSyncStatus -Device $device
    $statusMatchesAttempt = (-not $ForceSync) -or ($status -and $status.lastStartedAtMs -ge $syncAttemptStartedAtMs)
    if ($status -and $statusMatchesAttempt -and $status.status -eq "Success" -and (($status.moviesImported -gt 0) -or ($status.seriesImported -gt 0))) {
        Write-Host "Sync command timed out waiting for result, but app-private safe sync status shows success."
        $sync = [pscustomobject]@{
            status = "success"
            catalogImport = [pscustomobject]@{
                moviesImported = $status.moviesImported
                seriesImported = $status.seriesImported
                excludedCategories = $status.excludedCategories
                excludedItems = $status.excludedItems
            }
            lastErrorCategory = $null
        }
        $safeStatus = [pscustomobject]@{
            status = $status.status
            moviesImported = $status.moviesImported
            seriesImported = $status.seriesImported
            excludedCategories = $status.excludedCategories
            excludedItems = $status.excludedItems
            lastStartedAtMs = $status.lastStartedAtMs
            lastCompletedAtMs = $status.lastCompletedAtMs
        }
        $syncJson = $safeStatus | ConvertTo-Json -Depth 6
    } else {
        throw
    }
}
Set-Content -LiteralPath (Join-Path $dumpDir "sync_saved_provider_catalog.json") -Value $syncJson -Encoding UTF8
Write-Host "Status: $($sync.status)"
Write-Host "MoviesImported: $($sync.catalogImport.moviesImported)"
Write-Host "SeriesImported: $($sync.catalogImport.seriesImported)"
Write-Host "ExcludedCategories: $($sync.catalogImport.excludedCategories)"
Write-Host "ExcludedItems: $($sync.catalogImport.excludedItems)"
if ($sync.status -ne "success") { throw "Saved provider sync failed: $($sync.lastErrorCategory)" }

Write-Host ""
Write-Host "== English catalog audit =="
$auditJson = Invoke-DebugCommand -Device $device -Command "audit_english_catalog_coverage" -Timeout $TimeoutSeconds
Set-Content -LiteralPath (Join-Path $dumpDir "audit_english_catalog_coverage.json") -Value $auditJson -Encoding UTF8
$audit = ($auditJson | ConvertFrom-Json).catalogAudit
Write-Host ("VOD categories: available={0} allowed={1} imported={2} itemsFetched={3} itemsImported={4}" -f $audit.vodCategoriesAvailable, $audit.vodCategoriesAllowedForDownload, $audit.vodCategoriesImported, $audit.vodItemsFetched, $audit.vodItemsImported)
Write-Host ("Series categories: available={0} allowed={1} imported={2} itemsFetched={3} itemsImported={4}" -f $audit.seriesCategoriesAvailable, $audit.seriesCategoriesAllowedForDownload, $audit.seriesCategoriesImported, $audit.seriesItemsFetched, $audit.seriesItemsImported)
Write-Host ("Excluded: categories={0} items={1} skippedGeneric={2} usefulSkipped={3}" -f $audit.excludedCategoryCount, $audit.excludedItemCount, $audit.skippedOrGenericCategoryCount, $audit.usefulEnglishSkippedCategoryCount)
Write-Host "Allowed VOD examples: $($audit.allowedVodCategoryExamples -join '; ')"
Write-Host "Allowed Series examples: $($audit.allowedSeriesCategoryExamples -join '; ')"
if ($audit.usefulEnglishSkippedCategoryCount -gt 0) {
    Write-Host "Potential useful skipped examples: $($audit.usefulEnglishSkippedCategoryExamples -join '; ')"
}

Write-Host ""
Write-Host "== full catalog search reachability =="
$searchJson = Invoke-DebugCommand -Device $device -Command "probe_full_catalog_search" -Timeout 300
Set-Content -LiteralPath (Join-Path $dumpDir "probe_full_catalog_search.json") -Value $searchJson -Encoding UTF8
$search = ($searchJson | ConvertFrom-Json).searchReachability
Write-Host ("Totals: movies={0} series={1} deepOffset={2}" -f $search.movieTotal, $search.seriesTotal, $search.deepOffsetUsed)
if ($search.movieSearch) {
    Write-Host ("Movie query: '{0}' matches={1} bounded={2} foundDeep={3}" -f $search.movieSearch.query, $search.movieSearch.totalMatches, $search.movieSearch.boundedResultCount, $search.movieSearch.foundSelectedItem)
}
if ($search.seriesSearch) {
    Write-Host ("Series query: '{0}' matches={1} bounded={2} foundDeep={3}" -f $search.seriesSearch.query, $search.seriesSearch.totalMatches, $search.seriesSearch.boundedResultCount, $search.seriesSearch.foundSelectedItem)
}

Write-Host ""
Write-Host "== playback deep health =="
$cachedJson = Invoke-DebugCommand -Device $device -Command "load_cached_catalog" -Timeout 120
$cached = $cachedJson | ConvertFrom-Json
if (($cached.status -ne "success") -or ($cached.catalogSource -eq "fixture") -or ($cached.redactedUrl -like "fixture://*") -or ($cached.itemCounts.keptVodStreams -lt 1) -or ($cached.itemCounts.keptSeries -lt 1)) {
    Invoke-DebugCommand -Device $device -Command "refresh_real_catalog" -Timeout $TimeoutSeconds | Out-Null
}
$deepJson = Invoke-DebugCommand -Device $device -Command "probe_playback_deep_health" -DurationSeconds $PlaybackDurationSeconds -Timeout $TimeoutSeconds
Set-Content -LiteralPath (Join-Path $dumpDir "probe_playback_deep_health.json") -Value $deepJson -Encoding UTF8
$deep = ($deepJson | ConvertFrom-Json).playbackDeepHealth
Write-Host ("{0,-15} {1,-20} {2,-11} {3,-8} {4,-8} {5,-9} {6,-7} {7,-10} {8}" -f "section", "profile", "video", "fps", "dropped", "dropRatio", "buffers", "rebufferMs", "bottleneck")
foreach ($section in $deep.results) {
    foreach ($profile in $section.profiles) {
        $video = if ($profile.video) { "$($profile.video.width)x$($profile.video.height)" } else { "0x0" }
        $fps = if ($null -ne $profile.renderedFramesPerSecond) { "{0:N1}" -f [double]$profile.renderedFramesPerSecond } else { "unknown" }
        $ratio = if ($null -ne $profile.droppedRenderedRatio) { "{0:N3}" -f [double]$profile.droppedRenderedRatio } else { "unknown" }
        Write-Host ("{0,-15} {1,-20} {2,-11} {3,-8} {4,-8} {5,-9} {6,-7} {7,-10} {8}" -f $section.section, $profile.profile, $video, $fps, $profile.counters.droppedBufferCount, $ratio, $profile.rebufferCount, $profile.totalRebufferMs, $section.bottleneck)
    }
}

Save-AppOutputs -Device $device -DumpDir $dumpDir
Assert-DumpSafe -DumpDir $dumpDir

Write-Host ""
Write-Host "Onn real-provider acceptance: PASS"
