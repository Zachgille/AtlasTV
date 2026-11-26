param(
    [Parameter(Mandatory = $true)]
    [string] $Command,

    [string] $DeviceSerial = "",

    [int] $StreamId = 0,

    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$PackageName = "com.atlas.atlastv"
$ActivityName = "com.atlas.atlastv/.MainActivity"
$ReceiverName = "com.atlas.atlastv/.debug.DebugCommandReceiver"
$ActionName = "com.atlas.atlastv.DEBUG_COMMAND"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

function Invoke-Adb {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]] $AdbArgs
    )
    & adb @AdbArgs
}

function Get-OnnDevice {
    $lines = Invoke-Adb "devices" | Select-Object -Skip 1
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
    if ($device -like "emulator-*") { throw "Refusing to run debug command against emulator serial '$device'." }
    $connected = Invoke-Adb "devices" | Select-String -SimpleMatch "$device`tdevice"
    if (-not $connected) { throw "Requested Onn serial '$device' is not connected." }
    return $device
}

function Read-AppFile {
    param(
        [string] $Device,
        [string] $FileName
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & adb "-s" $Device "shell" "run-as" $PackageName "cat" "files/$FileName" 2>$null
        if ($LASTEXITCODE -ne 0) {
            return $null
        }
        return ($output -join "`n")
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

New-Item -ItemType Directory -Force -Path $DumpRoot | Out-Null

$deviceId = Resolve-OnnDevice -Serial $DeviceSerial

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Debug APK not found at $ApkPath. Run .\gradlew assembleDebug first."
}

Invoke-Adb "-s" $deviceId "install" "-r" $ApkPath | Out-Null
Invoke-Adb "-s" $deviceId "shell" "am" "start" "-n" $ActivityName | Out-Null
Invoke-Adb "-s" $deviceId "shell" "run-as" $PackageName "rm" "-f" "files/debug_command_result.json" | Out-Null

if ($StreamId -gt 0) {
    Invoke-Adb "-s" $deviceId "shell" "am" "broadcast" "-n" $ReceiverName "-a" $ActionName "--es" "command" $Command "--es" "stream_id" "$StreamId" | Out-Null
} else {
    Invoke-Adb "-s" $deviceId "shell" "am" "broadcast" "-n" $ReceiverName "-a" $ActionName "--es" "command" $Command | Out-Null
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$resultJson = $null
do {
    Start-Sleep -Seconds 1
    $candidate = Read-AppFile -Device $deviceId -FileName "debug_command_result.json"
    if ($candidate) {
        try {
            $parsed = $candidate | ConvertFrom-Json
            if ($parsed.command -eq $Command.ToLowerInvariant()) {
                $resultJson = $candidate
                break
            }
        } catch {
        }
    }
} while ((Get-Date) -lt $deadline)

if (-not $resultJson) {
    throw "Timed out waiting for command result for '$Command'."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $DumpRoot "$timestamp-$($Command.ToLowerInvariant())"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null

$files = @(
    "debug_command_result.json",
    "debug_state.json",
    "debug_catalog_summary.json",
    "debug_last_error.json",
    "debug_live_tv_state.json",
    "debug_movies_state.json",
    "debug_watch_progress_state.json",
    "debug_safe_catalog_cache_snapshot.json",
    "debug_catalog_audit.json",
    "debug_search_reachability.json"
)

foreach ($file in $files) {
    $content = Read-AppFile -Device $deviceId -FileName $file
    if ($content) {
        Set-Content -LiteralPath (Join-Path $dumpDir $file) -Value $content -Encoding UTF8
    }
}

$result = $resultJson | ConvertFrom-Json
$counts = $result.itemCounts

Write-Host "Device: $deviceId"
Write-Host "Command: $($result.command)"
Write-Host "Status: $($result.status)"
Write-Host "DurationMs: $($result.durationMs)"
Write-Host "State: $($result.currentScreen) / $($result.currentState)"
Write-Host "CatalogSource: $($result.catalogSource)"
Write-Host "Counts: live=$($counts.liveStreams), vod=$($counts.vodStreams), series=$($counts.series), keptLive=$($counts.keptLiveStreams)"
if ($null -ne $result.cacheAgeMs) {
    Write-Host "CacheAgeMs: $($result.cacheAgeMs)"
}
if ($result.lastErrorCategory) {
    Write-Host "LastErrorCategory: $($result.lastErrorCategory)"
}
if ($result.redactedUrl) {
    Write-Host "RedactedUrl: $($result.redactedUrl)"
}
if ($result.playbackProbe) {
    $probe = $result.playbackProbe
    Write-Host "ProbeItem: $($probe.selectedItemName)"
    Write-Host "ProbeStreamId: $($probe.streamId)"
    Write-Host "ProbeExtension: $($probe.containerExtension)"
    Write-Host "ProbeHttpStatus: $($probe.httpStatus)"
    Write-Host "ProbeStatusCategory: $($probe.statusCategory)"
    if ($probe.media3ErrorCode) {
        Write-Host "ProbeMedia3ErrorCode: $($probe.media3ErrorCode)"
    }
    if ($probe.playerState) {
        Write-Host "ProbePlayerState: $($probe.playerState)"
    }
    if ($null -ne $probe.firstFrameRendered) {
        Write-Host "ProbeFirstFrameRendered: $($probe.firstFrameRendered)"
    }
    if (($null -ne $probe.videoWidth) -or ($null -ne $probe.videoHeight)) {
        Write-Host "ProbeVideoSize: $($probe.videoWidth)x$($probe.videoHeight)"
    }
    if ($null -ne $probe.droppedFrames) {
        Write-Host "ProbeDroppedFrames: $($probe.droppedFrames)"
    }
    Write-Host "ProbeDurationMs: $($probe.durationMs)"
    Write-Host "ProbeRedactedUrl: $($probe.redactedUrl)"
    if ($probe.comparisons) {
        Write-Host "ProbeComparisons:"
        foreach ($comparison in $probe.comparisons) {
            $httpStatus = if ($null -ne $comparison.httpStatus) { $comparison.httpStatus } else { "null" }
            $media3Error = if ($comparison.media3ErrorCode) { $comparison.media3ErrorCode } else { "none" }
            Write-Host ("  {0,-26} http={1,-5} category={2,-14} media3={3,-32} durationMs={4}" -f `
                $comparison.name, `
                $httpStatus, `
                $comparison.statusCategory, `
                $media3Error, `
                $comparison.durationMs)
            if (($null -ne $comparison.firstFrameRendered) -or ($null -ne $comparison.videoWidth) -or ($null -ne $comparison.droppedFrames)) {
                $firstFrame = if ($null -ne $comparison.firstFrameRendered) { $comparison.firstFrameRendered } else { "null" }
                $width = if ($null -ne $comparison.videoWidth) { $comparison.videoWidth } else { 0 }
                $height = if ($null -ne $comparison.videoHeight) { $comparison.videoHeight } else { 0 }
                $dropped = if ($null -ne $comparison.droppedFrames) { $comparison.droppedFrames } else { 0 }
                Write-Host ("    render playerState={0} firstFrame={1} video={2}x{3} dropped={4}" -f `
                    $comparison.playerState, `
                    $firstFrame, `
                    $width, `
                    $height, `
                    $dropped)
            }
            Write-Host "    request=$($comparison.requestShapeSummary)"
        }
    }
}
if ($result.liveTvState) {
    $live = $result.liveTvState
    Write-Host "LiveTvCacheSource: $($live.cacheSource)"
    Write-Host "LiveTvCappedItemCount: $($live.cappedItemCount)"
    Write-Host "LiveTvSelectedCategory: $($live.selectedCategoryName) [$($live.selectedCategoryId)]"
    Write-Host "LiveTvSelectedChannel: $($live.selectedChannelName) [$($live.selectedChannelStreamId)]"
    Write-Host "LiveTvSelectedChannelCategory: $($live.selectedChannelCategory)"
    Write-Host "LiveTvSort: $($live.selectedSortModeLabel) [$($live.selectedSortMode)]"
    if ($live.searchQuery) {
        Write-Host "LiveTvSearchQuery: $($live.searchQuery)"
    }
    Write-Host "LiveTvFavoritesCount: $($live.favoritesCount)"
    Write-Host "LiveTvSelectedFavorite: $($live.selectedChannelIsFavorite)"
    if ($live.redactedStreamUrl) {
        Write-Host "LiveTvRedactedUrl: $($live.redactedStreamUrl)"
    }
}
if ($result.moviesState) {
    $movies = $result.moviesState
    Write-Host "MoviesCacheSource: $($movies.cacheSource)"
    Write-Host "MoviesCappedItemCount: $($movies.cappedItemCount)"
    Write-Host "MoviesSelectedCategory: $($movies.selectedCategoryName) [$($movies.selectedCategoryId)]"
    Write-Host "MoviesSelectedMovie: $($movies.selectedMovieTitle) [$($movies.selectedMovieStreamId)]"
    Write-Host "MoviesSelectedMovieCategory: $($movies.selectedMovieCategory)"
    Write-Host "MoviesSort: $($movies.selectedSortModeLabel) [$($movies.selectedSortMode)]"
    if ($movies.searchQuery) {
        Write-Host "MoviesSearchQuery: $($movies.searchQuery)"
    }
    Write-Host "MoviesWatchlistCount: $($movies.watchlistCount)"
    Write-Host "MoviesSelectedWatchlisted: $($movies.selectedMovieIsWatchlisted)"
    if ($movies.containerExtension) {
        Write-Host "MoviesExtension: $($movies.containerExtension)"
    }
    if ($movies.redactedStreamUrl) {
        Write-Host "MoviesRedactedUrl: $($movies.redactedStreamUrl)"
    }
}
if ($result.watchProgressState) {
    $progress = $result.watchProgressState
    Write-Host "WatchProgressTotal: $($progress.totalCount)"
    Write-Host "ContinueWatchingCount: $($progress.continueWatchingCount)"
    foreach ($record in $progress.records) {
        Write-Host ("WatchProgress: {0} id={1} title='{2}' posMs={3} durationMs={4} completed={5} resume={6}" -f `
            $record.contentType, `
            $record.streamId, `
            $record.title, `
            $record.positionMs, `
            $record.durationMs, `
            $record.completed, `
            $record.resumeEligible)
    }
}
if ($result.catalogCacheSnapshotState) {
    $snapshot = $result.catalogCacheSnapshotState
    Write-Host "CatalogSnapshotExists: $($snapshot.exists)"
    if ($snapshot.schemaVersion) {
        Write-Host "CatalogSnapshotSchema: $($snapshot.schemaVersion)"
    }
    if ($snapshot.catalogSource) {
        Write-Host "CatalogSnapshotSource: $($snapshot.catalogSource)"
    }
    Write-Host "CatalogSnapshotCounts: live=$($snapshot.itemCounts.liveStreams), vod=$($snapshot.itemCounts.vodStreams), series=$($snapshot.itemCounts.series), keptSeries=$($snapshot.itemCounts.keptSeries)"
}
if ($result.catalogImport) {
    $import = $result.catalogImport
    Write-Host "CatalogImportStatus: $($import.status)"
    Write-Host "CatalogImportMovies: $($import.moviesImported)"
    Write-Host "CatalogImportSeries: $($import.seriesImported)"
    Write-Host "CatalogImportExcludedCategories: $($import.excludedCategories)"
    Write-Host "CatalogImportExcludedItems: $($import.excludedItems)"
    Write-Host "CatalogImportDurationMs: $($import.durationMs)"
    if ($null -ne $import.totalDurationMs) {
        Write-Host "CatalogImportTiming: total=$($import.totalDurationMs) account=$($import.accountInfoDurationMs) vodCategories=$($import.vodCategoriesDurationMs) vodStreams=$($import.vodStreamsDurationMs) seriesCategories=$($import.seriesCategoriesDurationMs) series=$($import.seriesDurationMs) filtering=$($import.filteringDurationMs) roomWrite=$($import.roomWriteDurationMs)"
        Write-Host "CatalogImportFetchedCounts: vodCategories=$($import.vodCategoryCount) vodStreams=$($import.vodStreamCount) seriesCategories=$($import.seriesCategoryCount) series=$($import.seriesCount)"
    }
    if ($import.errorCategory) {
        Write-Host "CatalogImportErrorCategory: $($import.errorCategory)"
    }
}
Write-Host "DumpDir: $dumpDir"

if ($result.status -ne "success") {
    exit 1
}
