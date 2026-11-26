param(
    [string] $DeviceSerial = "",
    [switch] $RefreshRealCatalog,
    [int] $TimeoutSeconds = 360
)

$ErrorActionPreference = "Stop"

$PackageName = "com.atlas.atlastv"
$ActivityName = "com.atlas.atlastv/.MainActivity"
$ReceiverName = "com.atlas.atlastv/.debug.DebugCommandReceiver"
$ActionName = "com.atlas.atlastv.DEBUG_COMMAND"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Runner = Join-Path $PSScriptRoot "run-debug-command.ps1"
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
. (Join-Path $PSScriptRoot "onn-device.ps1")

function Read-AppFile {
    param([string] $Device, [string] $FileName)
    $output = & adb -s $Device shell run-as $PackageName cat "files/$FileName" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output -join "`n")
}

function Get-LatestCommandResult {
    param([string] $Command)
    $latest = Get-ChildItem -LiteralPath $DumpRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-$Command" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latest) { return $null }
    $file = Join-Path $latest.FullName "debug_command_result.json"
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    return Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
}

New-Item -ItemType Directory -Force -Path $DumpRoot | Out-Null

$device = Resolve-OnnDevice -Serial $DeviceSerial

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Debug APK not found at $ApkPath. Run .\gradlew assembleDebug first."
}

if ($RefreshRealCatalog) {
    powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
} else {
    try {
        powershell -ExecutionPolicy Bypass -File $Runner -Command load_cached_catalog -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
        $cached = Get-LatestCommandResult -Command "load_cached_catalog"
        if (($null -eq $cached) -or ($cached.catalogSource -eq "fixture") -or ($cached.redactedUrl -like "fixture://*") -or ($cached.itemCounts.keptLiveStreams -lt 1) -or ($cached.itemCounts.keptVodStreams -lt 1) -or ($cached.itemCounts.keptSeries -lt 1)) {
            powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
        }
    } catch {
        powershell -ExecutionPolicy Bypass -File $Runner -Command refresh_real_catalog -DeviceSerial $device -TimeoutSeconds $TimeoutSeconds
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$healthDir = Join-Path $DumpRoot "playback-health-$timestamp"
New-Item -ItemType Directory -Force -Path $healthDir | Out-Null

& adb -s $device install -r $ApkPath | Out-Null
& adb -s $device shell am start -n $ActivityName | Out-Null
& adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
& adb -s $device shell input keyevent KEYCODE_MENU | Out-Null
& adb -s $device shell run-as $PackageName rm -f files/debug_command_result.json | Out-Null
& adb -s $device shell am broadcast -n $ReceiverName -a $ActionName --es command probe_playback_health_sweep | Out-Null

$screenshots = [ordered]@{
    live_tv = Join-Path $healthDir "live-tv.png"
    movies = Join-Path $healthDir "movies.png"
    series_episode = Join-Path $healthDir "series-episode.png"
}

Start-Sleep -Seconds 18
& adb -s $device exec-out screencap -p > $screenshots.live_tv
Start-Sleep -Seconds 16
& adb -s $device exec-out screencap -p > $screenshots.movies
Start-Sleep -Seconds 16
& adb -s $device exec-out screencap -p > $screenshots.series_episode

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$resultJson = $null
do {
    Start-Sleep -Seconds 1
    $candidate = Read-AppFile -Device $device -FileName "debug_command_result.json"
    if ($candidate) {
        try {
            $parsed = $candidate | ConvertFrom-Json
            if ($parsed.command -eq "probe_playback_health_sweep") {
                $resultJson = $candidate
                break
            }
        } catch {
        }
    }
} while ((Get-Date) -lt $deadline)

if (-not $resultJson) { throw "Timed out waiting for playback health sweep result." }

$resultPath = Join-Path $healthDir "debug_command_result.json"
Set-Content -LiteralPath $resultPath -Value $resultJson -Encoding UTF8
$state = Read-AppFile -Device $device -FileName "debug_state.json"
if ($state) { Set-Content -LiteralPath (Join-Path $healthDir "debug_state.json") -Value $state -Encoding UTF8 }

$result = $resultJson | ConvertFrom-Json
Write-Host "Device: $device (physical Onn)"
Write-Host "Status: $($result.status)"
Write-Host "DurationMs: $($result.durationMs)"
Write-Host "DumpDir: $healthDir"
Write-Host ""
Write-Host ("{0,-16} {1,-10} {2,-8} {3,-10} {4,-11} {5,-11} {6,-8} {7,-7} {8,-9} {9}" -f "section", "stream id", "ext", "player", "firstFrame", "video", "dropped", "buffers", "advanced", "screenshot")
foreach ($section in $result.playbackHealthSweep.results) {
    $shot = $screenshots[$section.section]
    $video = "{0}x{1}" -f (($section.videoWidth | ForEach-Object { if ($null -eq $_) { 0 } else { $_ } })), (($section.videoHeight | ForEach-Object { if ($null -eq $_) { 0 } else { $_ } }))
    $streamId = if ($null -ne $section.streamId) { $section.streamId } else { "none" }
    $ext = if ($section.containerExtension) { $section.containerExtension } else { "none" }
    $player = if ($section.playerState) { $section.playerState } else { "none" }
    Write-Host ("{0,-16} {1,-10} {2,-8} {3,-10} {4,-11} {5,-11} {6,-8} {7,-7} {8,-9} {9}" -f $section.section, $streamId, $ext, $player, $section.firstFrameRendered, $video, $section.droppedFrames, $section.bufferCount, $section.playbackAdvanced, $shot)
}

if ($result.status -ne "success") { exit 1 }
