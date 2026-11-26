param(
    [int] $TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$Script = Join-Path $PSScriptRoot "run-debug-command.ps1"

powershell -ExecutionPolicy Bypass -File $Script -Command "dump_watch_progress_state" -TimeoutSeconds $TimeoutSeconds
