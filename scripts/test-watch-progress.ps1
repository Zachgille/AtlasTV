param(
    [int] $TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$Script = Join-Path $PSScriptRoot "run-debug-command.ps1"

function Invoke-WatchProgressCommand {
    param([string] $Command)
    $output = powershell -ExecutionPolicy Bypass -File $Script -Command $Command -TimeoutSeconds $TimeoutSeconds
    $output | ForEach-Object { Write-Host $_ }
    $text = $output -join "`n"
    if ($text -notmatch "Status: success") {
        throw "$Command failed."
    }
    return $text
}

$seed = Invoke-WatchProgressCommand "seed_fixture_watch_progress"
if ($seed -notmatch "ContinueWatchingCount: 2") {
    throw "Expected seeded Continue Watching count of 2."
}

$dump = Invoke-WatchProgressCommand "dump_watch_progress_state"
if ($dump -notmatch "WatchProgressTotal: 2") {
    throw "Expected watch progress total count of 2."
}

$clear = Invoke-WatchProgressCommand "clear_watch_progress"
if ($clear -notmatch "WatchProgressTotal: 0") {
    throw "Expected watch progress to clear."
}

$afterClear = Invoke-WatchProgressCommand "dump_watch_progress_state"
if ($afterClear -notmatch "ContinueWatchingCount: 0") {
    throw "Expected empty Continue Watching after clear."
}

Write-Host "Watch progress: PASS"
