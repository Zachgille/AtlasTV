param(
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Runner = Join-Path $PSScriptRoot "run-debug-command.ps1"
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$SnapshotDir = Join-Path $DumpRoot "safe-catalog-cache"
$SnapshotFile = Join-Path $SnapshotDir "debug_safe_catalog_cache_snapshot.json"

function Get-LatestDumpFile {
    param([string] $Command, [string] $FileName)
    $latest = Get-ChildItem -LiteralPath $DumpRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-$Command" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) { throw "No dump found for $Command." }
    $file = Join-Path $latest.FullName $FileName
    if (-not (Test-Path -LiteralPath $file)) { throw "Dump did not include $FileName." }
    return $file
}

function Assert-SafeText {
    param([string] $Text)
    if ($Text -match "fake_user|fake_pass|example\.test") { throw "Snapshot contains test credential/host markers." }
    if ($Text -match "https?://[^`"']+/(live|movie|series)/(?!REDACTED/REDACTED/)[^`"']+") { throw "Snapshot contains a raw playback URL." }
}

New-Item -ItemType Directory -Force -Path $SnapshotDir | Out-Null

powershell -ExecutionPolicy Bypass -File $Runner -Command export_safe_catalog_cache_snapshot -TimeoutSeconds $TimeoutSeconds

$source = Get-LatestDumpFile -Command "export_safe_catalog_cache_snapshot" -FileName "debug_safe_catalog_cache_snapshot.json"
$text = Get-Content -LiteralPath $source -Raw
Assert-SafeText -Text $text
Set-Content -LiteralPath $SnapshotFile -Value $text -Encoding UTF8

$parsed = $text | ConvertFrom-Json
Write-Host "Safe catalog cache exported: $SnapshotFile"
Write-Host "Source: $($parsed.catalog.catalogSource)"
Write-Host "Counts: live=$($parsed.catalog.itemCounts.liveStreams), vod=$($parsed.catalog.itemCounts.vodStreams), series=$($parsed.catalog.itemCounts.series), keptSeries=$($parsed.catalog.itemCounts.keptSeries)"
