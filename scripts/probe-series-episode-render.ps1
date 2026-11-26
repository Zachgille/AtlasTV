param(
    [string] $DeviceSerial = "",
    [int] $StreamId = 0,
    [switch] $RefreshRealCatalog,
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Runner = Join-Path $PSScriptRoot "run-debug-command.ps1"
$Importer = Join-Path $PSScriptRoot "import-safe-catalog-cache.ps1"
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$SnapshotPath = Join-Path $DumpRoot "safe-catalog-cache\debug_safe_catalog_cache_snapshot.json"

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

function Try-RestoreSafeSnapshot {
    if (-not (Test-Path -LiteralPath $SnapshotPath)) { return $false }
    try {
        powershell -ExecutionPolicy Bypass -File $Importer -SnapshotPath $SnapshotPath -TimeoutSeconds $TimeoutSeconds
        return $true
    } catch {
        return $false
    }
}

if ($RefreshRealCatalog) {
    powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
} else {
    try {
        powershell -ExecutionPolicy Bypass -File $Runner -Command load_cached_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
        $cached = Get-LatestCommandResult -Command "load_cached_catalog"
        if (($null -eq $cached) -or ($cached.catalogSource -eq "fixture") -or ($cached.redactedUrl -like "fixture://*") -or ($cached.itemCounts.keptSeries -lt 1)) {
            if (-not (Try-RestoreSafeSnapshot)) {
                powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
            }
        }
    } catch {
        if (-not (Try-RestoreSafeSnapshot)) {
            powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
        }
    }
}

$args = @("-ExecutionPolicy", "Bypass", "-File", $Runner, "-Command", "probe_series_episode_render", "-DeviceSerial", "$DeviceSerial", "-TimeoutSeconds", "$TimeoutSeconds")
if ($StreamId -gt 0) { $args += @("-StreamId", "$StreamId") }

powershell @args

$render = Get-LatestCommandResult -Command "probe_series_episode_render"
if (($null -eq $render) -or ($null -eq $render.playbackProbe) -or ($render.playbackProbe.firstFrameRendered -ne $true) -or ($render.playbackProbe.statusCategory -ne "success")) {
    exit 1
}
