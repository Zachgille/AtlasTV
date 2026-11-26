param(
    [string] $DeviceSerial = "",
    [int] $TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunCommandScript = Join-Path $ScriptRoot "run-debug-command.ps1"

$commands = @(
    "health_check",
    "load_fixture_catalog",
    "dump_current_state"
)

foreach ($command in $commands) {
    Write-Host ""
    Write-Host "== $command =="
    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command $command -TimeoutSeconds $TimeoutSeconds
    } else {
        powershell -ExecutionPolicy Bypass -File $RunCommandScript -Command $command -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Debug command failed: $command"
    }
}

Write-Host ""
Write-Host "Fast Onn smoke passed."
