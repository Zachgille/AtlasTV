$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"

Set-Location $RepoRoot

function Invoke-DebugCommand {
    param([string] $Command)
    powershell -ExecutionPolicy Bypass -File .\scripts\run-debug-command.ps1 -Command $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Debug command '$Command' failed."
    }
}

function Assert-LatestDumpSafe {
    param([string] $Command)
    $latest = Get-ChildItem -Path $DumpRoot -Directory |
        Where-Object { $_.Name -like "*-$Command" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) {
        throw "No dump directory found for $Command."
    }
    $text = Get-ChildItem -Path $latest.FullName -File | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }
    $unsafePatterns = @("provider.example", "fake_user", "fake_pass", "/movie/fake_user", "/series/fake_user", "/live/fake_user")
    foreach ($pattern in $unsafePatterns) {
        if (($text -join "`n").Contains($pattern)) {
            throw "Unsafe text '$pattern' found in $($latest.FullName)."
        }
    }
    return $latest.FullName
}

Write-Host "Clearing Room catalog..."
Invoke-DebugCommand "clear_room_catalog"

Write-Host "Running fixture catalog import..."
Invoke-DebugCommand "run_fixture_catalog_import"
$importDump = Assert-LatestDumpSafe "run_fixture_catalog_import"

Write-Host "Validating Room-backed Home/Movies/Series/Search without debug JSON..."
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.atlas.atlastv.data.catalog.CatalogSyncImportConnectedTest"
if ($LASTEXITCODE -ne 0) {
    throw "Catalog sync import connected verification failed."
}

Write-Host "Catalog sync import: PASS"
Write-Host "Import dump: $importDump"
