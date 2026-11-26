param(
    [string] $DeviceSerial = "",
    [int] $TimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptRoot "onn-device.ps1")

$device = Resolve-OnnDevice -Serial $DeviceSerial
$runDebugCommand = Join-Path $ScriptRoot "run-debug-command.ps1"

$output = & powershell -ExecutionPolicy Bypass -File $runDebugCommand `
    -DeviceSerial $device `
    -Command "audit_english_catalog_coverage" `
    -TimeoutSeconds $TimeoutSeconds

$output | ForEach-Object { Write-Host $_ }

$dumpLine = $output | Where-Object { $_ -like "DumpDir:*" } | Select-Object -Last 1
if (-not $dumpLine) {
    throw "Could not locate DumpDir in debug-command output."
}

$dumpDir = ($dumpLine -replace "^DumpDir:\s*", "").Trim()
$auditPath = Join-Path $dumpDir "debug_catalog_audit.json"
if (-not (Test-Path -LiteralPath $auditPath)) {
    throw "Category audit JSON was not saved at $auditPath"
}

$audit = Get-Content -Raw -LiteralPath $auditPath | ConvertFrom-Json
$categories = @($audit.categories)
$movieCategories = @($categories | Where-Object { $_.contentType -eq "movie" })
$seriesCategories = @($categories | Where-Object { $_.contentType -eq "series" })
$kept = @($categories | Where-Object { $_.decision -eq "keep" })
$excluded = @($categories | Where-Object { $_.decision -eq "exclude" })
$review = @($categories | Where-Object { $_.reason -eq "review_needed_potentially_useful_english" })

Write-Host "== Real Provider Category Audit =="
Write-Host "Device: physical Onn"
Write-Host "Categories: total=$($categories.Count) movies=$($movieCategories.Count) series=$($seriesCategories.Count)"
Write-Host "Decisions: keep=$($kept.Count) exclude=$($excluded.Count) review=$($review.Count)"
Write-Host "Items: fetchedMovies=$($audit.vodItemsFetched) importedMovies=$($audit.vodItemsImported) fetchedSeries=$($audit.seriesItemsFetched) importedSeries=$($audit.seriesItemsImported)"
Write-Host "AuditJson: $auditPath"

if ($review.Count -gt 0) {
    Write-Host "ReviewNeededExamples:"
    $review | Select-Object -First 12 | ForEach-Object {
        Write-Host ("  {0} [{1}] count={2} reason={3}" -f $_.categoryName, $_.contentType, $_.itemCount, $_.reason)
    }
}

if ($categories.Count -eq 0) {
    throw "Category audit contained no per-category decisions."
}
