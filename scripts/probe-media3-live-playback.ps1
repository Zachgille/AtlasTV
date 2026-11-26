param(
    [int] $StreamId = 0,
    [switch] $RefreshRealCatalog
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunCommandScript = Join-Path $ScriptRoot "run-debug-command.ps1"

Write-Host "Preparing cached catalog..."
powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command load_cached_catalog -TimeoutSeconds 180
if ($LASTEXITCODE -ne 0 -or $RefreshRealCatalog) {
    Write-Host "Refreshing real catalog..."
    powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command refresh_real_catalog -TimeoutSeconds 600
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to prepare real catalog for Media3 probe."
    }
}

$args = @(
    "-ExecutionPolicy", "Bypass",
    "-File", $RunCommandScript,
    "-Command", "probe_media3_live_stream",
    "-TimeoutSeconds", "240"
)
if ($StreamId -gt 0) {
    $args += @("-StreamId", $StreamId.ToString())
}

Write-Host ""
Write-Host "Running Media3 live playback comparison probe..."
powershell @args
if ($LASTEXITCODE -ne 0) {
    throw "probe_media3_live_stream failed."
}
