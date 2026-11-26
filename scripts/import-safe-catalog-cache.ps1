param(
    [string] $SnapshotPath = "",
    [string] $DeviceSerial = "",
    [int] $TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Runner = Join-Path $PSScriptRoot "run-debug-command.ps1"
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$PackageName = "com.atlas.atlastv"
$SnapshotFileName = "debug_safe_catalog_cache_snapshot.json"
. (Join-Path $PSScriptRoot "onn-device.ps1")
if ([string]::IsNullOrWhiteSpace($SnapshotPath)) {
    $SnapshotPath = Join-Path $DumpRoot "safe-catalog-cache\$SnapshotFileName"
}

function Assert-SafeText {
    param([string] $Text)
    if ($Text -match "fake_user|fake_pass|example\.test") { throw "Snapshot contains test credential/host markers." }
    if ($Text -match "https?://[^`"']+/(live|movie|series)/(?!REDACTED/REDACTED/)[^`"']+") { throw "Snapshot contains a raw playback URL." }
}

if (-not (Test-Path -LiteralPath $SnapshotPath)) {
    throw "Safe catalog cache snapshot not found at $SnapshotPath."
}

$text = Get-Content -LiteralPath $SnapshotPath -Raw
Assert-SafeText -Text $text

# Ensures the debug APK is installed and the package data directory exists.
powershell -ExecutionPolicy Bypass -File $Runner -Command health_check -DeviceSerial $DeviceSerial -TimeoutSeconds $TimeoutSeconds

$device = Resolve-OnnDevice -Serial $DeviceSerial

$temp = [System.IO.Path]::GetTempFileName()
try {
    Set-Content -LiteralPath $temp -Value $text -Encoding UTF8
    $remoteTemp = "/data/local/tmp/$SnapshotFileName"
    & adb -s $device push $temp $remoteTemp | Out-Null
    & adb -s $device shell chmod 644 $remoteTemp | Out-Null
    & adb -s $device shell run-as $PackageName cp $remoteTemp "files/$SnapshotFileName" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to write snapshot into app-private storage." }
} finally {
    if (-not [string]::IsNullOrWhiteSpace($device)) {
        & adb -s $device shell rm -f "/data/local/tmp/$SnapshotFileName" | Out-Null
    }
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
}

powershell -ExecutionPolicy Bypass -File $Runner -Command import_safe_catalog_cache_snapshot -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds

Write-Host "Safe catalog cache imported from: $SnapshotPath"
