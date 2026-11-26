$ErrorActionPreference = "Stop"

$RunCommandScript = Join-Path $PSScriptRoot "run-debug-command.ps1"

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command "load_cached_catalog" -TimeoutSeconds 180
$loadCachedExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference

if ($loadCachedExitCode -ne 0) {
    powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command "load_fixture_catalog" -TimeoutSeconds 180
    if ($LASTEXITCODE -ne 0) {
        throw "Could not prepare a cached Live TV catalog."
    }
}

powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command "dump_live_tv_state" -TimeoutSeconds 180

if ($LASTEXITCODE -ne 0) {
    throw "dump_live_tv_state failed."
}
