param(
    [string] $DeviceSerial = "",
    [int] $DurationSeconds = 45,
    [switch] $RefreshRealCatalog,
    [int] $TimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"

$PackageName = "com.atlas.atlastv"
$ActivityName = "com.atlas.atlastv/.MainActivity"
$ReceiverName = "com.atlas.atlastv/.debug.DebugCommandReceiver"
$ActionName = "com.atlas.atlastv.DEBUG_COMMAND"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DumpRoot = Join-Path $RepoRoot ".local-debug-dumps"
$ApkPath = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
. (Join-Path $PSScriptRoot "onn-device.ps1")

function Read-AppFile {
    param([string] $Device, [string] $FileName)
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & adb -s $Device shell run-as $PackageName cat "files/$FileName" 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return ($output -join "`n")
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Invoke-DebugCommand {
    param(
        [string] $Device,
        [string] $Command,
        [int] $Duration = 0,
        [int] $Timeout = 300
    )
    & adb -s $Device shell run-as $PackageName rm -f files/debug_command_result.json | Out-Null
    $args = @("-s", $Device, "shell", "am", "broadcast", "-n", $ReceiverName, "-a", $ActionName, "--es", "command", $Command)
    if ($Duration -gt 0) { $args += @("--es", "duration_seconds", "$Duration") }
    & adb @args | Out-Null
    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        Start-Sleep -Seconds 1
        $candidate = Read-AppFile -Device $Device -FileName "debug_command_result.json"
        if ($candidate) {
            try {
                $parsed = $candidate | ConvertFrom-Json
                if ($parsed.command -eq $Command) { return $candidate }
            } catch {
            }
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for command result for '$Command'."
}

New-Item -ItemType Directory -Force -Path $DumpRoot | Out-Null

$device = $DeviceSerial
$device = Resolve-OnnDevice -Serial $device

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Debug APK not found at $ApkPath. Run .\gradlew assembleDebug first."
}

& adb -s $device install -r $ApkPath | Out-Null
& adb -s $device shell am start -n $ActivityName | Out-Null
& adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null

if ($RefreshRealCatalog) {
    Invoke-DebugCommand -Device $device -Command "refresh_real_catalog" -Timeout $TimeoutSeconds | Out-Null
} else {
    $cachedJson = Invoke-DebugCommand -Device $device -Command "load_cached_catalog" -Timeout 120
    $cached = $cachedJson | ConvertFrom-Json
    if (($cached.status -ne "success") -or ($cached.catalogSource -eq "fixture") -or ($cached.redactedUrl -like "fixture://*") -or ($cached.itemCounts.keptLiveStreams -lt 1) -or ($cached.itemCounts.keptVodStreams -lt 1) -or ($cached.itemCounts.keptSeries -lt 1)) {
        Invoke-DebugCommand -Device $device -Command "refresh_real_catalog" -Timeout $TimeoutSeconds | Out-Null
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $DumpRoot "playback-deep-health-$timestamp"
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null

$resultJson = Invoke-DebugCommand -Device $device -Command "probe_playback_deep_health" -Duration $DurationSeconds -Timeout $TimeoutSeconds
Set-Content -LiteralPath (Join-Path $dumpDir "debug_command_result.json") -Value $resultJson -Encoding UTF8
$state = Read-AppFile -Device $device -FileName "debug_state.json"
if ($state) { Set-Content -LiteralPath (Join-Path $dumpDir "debug_state.json") -Value $state -Encoding UTF8 }

$result = $resultJson | ConvertFrom-Json
Write-Host "Device: $device"
Write-Host "Status: $($result.status)"
Write-Host "DurationMs: $($result.durationMs)"
Write-Host "DumpDir: $dumpDir"
Write-Host ""
Write-Host ("{0,-15} {1,-20} {2,-11} {3,-12} {4,-7} {5,-8} {6,-9} {7,-19} {8,-7} {9,-10} {10,-8} {11,-7} {12,-6} {13,-24} {14}" -f "section", "profile", "video", "codec", "fps", "dropped", "dropRatio", "maxConsecutiveDrops", "buffers", "rebufferMs", "avgMbps", "minMbps", "stalls", "decoder", "bottleneck")
foreach ($section in $result.playbackDeepHealth.results) {
    foreach ($profile in $section.profiles) {
        $video = if ($profile.video) { "$($profile.video.width)x$($profile.video.height)" } else { "0x0" }
        $codec = if ($profile.video.codecs) { $profile.video.codecs } elseif ($profile.video.sampleMimeType) { $profile.video.sampleMimeType } else { "unknown" }
        $fps = if ($null -ne $profile.renderedFramesPerSecond) { "{0:N1}" -f [double]$profile.renderedFramesPerSecond } else { "unknown" }
        $ratio = if ($null -ne $profile.droppedRenderedRatio) { "{0:N3}" -f [double]$profile.droppedRenderedRatio } else { "unknown" }
        $avg = if ($section.transport) { "{0:N1}" -f [double]$section.transport.averageMbps } else { "0.0" }
        $min = if ($section.transport) { "{0:N1}" -f [double]$section.transport.minWindowMbps } else { "0.0" }
        $stalls = if ($section.transport) { $section.transport.stalledIntervalCount } else { 0 }
        $decoder = if ($profile.decoder.videoDecoderName) { $profile.decoder.videoDecoderName } else { "unknown" }
        Write-Host ("{0,-15} {1,-20} {2,-11} {3,-12} {4,-7} {5,-8} {6,-9} {7,-19} {8,-7} {9,-10} {10,-8} {11,-7} {12,-6} {13,-24} {14}" -f `
            $section.section, $profile.profile, $video, $codec, $fps, $profile.counters.droppedBufferCount, $ratio, $profile.counters.maxConsecutiveDroppedBufferCount, $profile.rebufferCount, $profile.totalRebufferMs, $avg, $min, $stalls, $decoder, $section.bottleneck)
    }
}

if ($result.status -ne "success") { exit 1 }
