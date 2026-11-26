param(
    [int] $StreamId = 0,
    [switch] $UseCachedCatalog
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunCommandScript = Join-Path $ScriptRoot "run-debug-command.ps1"

if ($UseCachedCatalog) {
    Write-Host "Using cached catalog when available. If no cache exists, loading fixture catalog."
    powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command load_cached_catalog -TimeoutSeconds 180
    if ($LASTEXITCODE -ne 0) {
        powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command load_fixture_catalog -TimeoutSeconds 180
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to prepare cached or fixture catalog for probe."
        }
    }
} else {
    Write-Host "Refreshing real catalog before probe..."
    powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command refresh_real_catalog -TimeoutSeconds 600
    if ($LASTEXITCODE -ne 0) {
        throw "refresh_real_catalog failed."
    }
}

$args = @(
    "-ExecutionPolicy", "Bypass",
    "-File", $RunCommandScript,
    "-Command", "probe_live_stream",
    "-TimeoutSeconds", "180"
)
if ($StreamId -gt 0) {
    $args += @("-StreamId", $StreamId.ToString())
}

Write-Host ""
Write-Host "Running live stream probe..."
powershell @args
if ($LASTEXITCODE -ne 0) {
    throw "probe_live_stream failed."
}
