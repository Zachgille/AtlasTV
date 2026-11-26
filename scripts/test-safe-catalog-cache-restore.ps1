param(
    [string] $DeviceSerial = "",
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Runner = Join-Path $PSScriptRoot "run-debug-command.ps1"
$Exporter = Join-Path $PSScriptRoot "export-safe-catalog-cache.ps1"
$Importer = Join-Path $PSScriptRoot "import-safe-catalog-cache.ps1"
$ProbeSeries = Join-Path $PSScriptRoot "probe-series-episode-render.ps1"
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$PackageName = "com.atlas.atlastv"
$SnapshotFileName = "debug_safe_catalog_cache_snapshot.json"
. (Join-Path $PSScriptRoot "onn-device.ps1")

function Get-LatestCommandResult {
    param([string] $Command)
    $latest = Get-ChildItem -LiteralPath $DumpRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-$Command" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) { return $null }
    $file = Join-Path $latest.FullName "debug_command_result.json"
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    return Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
}

function Assert-RealCounts {
    param($Result)
    if (($null -eq $Result) -or ($Result.status -ne "success")) { throw "Expected successful command result." }
    if (($Result.itemCounts.keptLiveStreams -lt 1) -or ($Result.itemCounts.keptVodStreams -lt 1) -or ($Result.itemCounts.keptSeries -lt 1)) {
        throw "Restored catalog counts are incomplete."
    }
}

function Assert-SafeDumpOutputs {
    $recent = Get-ChildItem -LiteralPath $DumpRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 12
    foreach ($dir in $recent) {
        foreach ($file in Get-ChildItem -LiteralPath $dir.FullName -Filter *.json -ErrorAction SilentlyContinue) {
            $text = Get-Content -LiteralPath $file.FullName -Raw
            if ($text -match "fake_user|fake_pass|example\.test") { throw "Unsafe credential marker in $($file.FullName)." }
            if ($text -match "https?://[^`"']+/(live|movie|series)/(?!REDACTED/REDACTED/)[^`"']+") { throw "Raw playback URL in $($file.FullName)." }
        }
    }
}

powershell -ExecutionPolicy Bypass -File $Runner -Command load_cached_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
$cached = Get-LatestCommandResult -Command "load_cached_catalog"
if (($null -eq $cached) -or ($cached.status -ne "success") -or ($cached.catalogSource -eq "fixture") -or ($cached.redactedUrl -like "fixture://*") -or ($cached.itemCounts.keptSeries -lt 1)) {
    $snapshotPath = Join-Path $DumpRoot "safe-catalog-cache\$SnapshotFileName"
    if (Test-Path -LiteralPath $snapshotPath) {
        powershell -ExecutionPolicy Bypass -File $Importer -SnapshotPath $snapshotPath -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
        powershell -ExecutionPolicy Bypass -File $Runner -Command load_cached_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
        $cached = Get-LatestCommandResult -Command "load_cached_catalog"
    }
}
if (($null -eq $cached) -or ($cached.status -ne "success") -or ($cached.catalogSource -eq "fixture") -or ($cached.redactedUrl -like "fixture://*") -or ($cached.itemCounts.keptSeries -lt 1)) {
    powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
    $cached = Get-LatestCommandResult -Command "refresh_real_catalog"
}
Assert-RealCounts -Result $cached

powershell -ExecutionPolicy Bypass -File $Exporter -TimeoutSeconds $TimeoutSeconds

$device = Resolve-OnnDevice -Serial $DeviceSerial

& adb -s $device shell run-as $PackageName rm -f "/data/data/$PackageName/files/debug_catalog_summary.json" "/data/data/$PackageName/files/$SnapshotFileName" | Out-Null

powershell -ExecutionPolicy Bypass -File $Importer -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
powershell -ExecutionPolicy Bypass -File $Runner -Command dump_catalog_cache_snapshot_state -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
powershell -ExecutionPolicy Bypass -File $Runner -Command load_cached_catalog -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
$restored = Get-LatestCommandResult -Command "load_cached_catalog"
Assert-RealCounts -Result $restored

powershell -ExecutionPolicy Bypass -File $ProbeSeries -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
$probeExitCode = $LASTEXITCODE
$probeResult = Get-LatestCommandResult -Command "probe_series_episode_render"
if ($probeExitCode -ne 0) {
    if (($null -ne $probeResult) -and
        ($probeResult.catalogSource -eq "cached") -and
        ($probeResult.lastErrorCategory -eq "DnsFailure")) {
        Write-Host "Series render probe: DNS_BLOCKED_AFTER_CACHE_RESTORE"
    } else {
        throw "Series render probe failed before validating restored cached catalog."
    }
} else {
    Write-Host "Series render probe: PASS"
}

Assert-SafeDumpOutputs
Write-Host "Safe catalog cache restore: PASS"
