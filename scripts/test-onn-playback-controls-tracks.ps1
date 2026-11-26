param(
    [string] $DeviceSerial = "",
    [int] $SeriesId = 36621,
    [int] $EpisodeId = 1528989,
    [int] $TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptRoot "..")
. (Join-Path $ScriptRoot "onn-device.ps1")

$device = Resolve-OnnDevice -Serial $DeviceSerial
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $RepoRoot ".local-debug-dumps\onn-playback-controls-tracks-$timestamp"
$apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
$importSafeCache = Join-Path $ScriptRoot "import-safe-catalog-cache.ps1"

function Send-Key {
    param([string] $Key, [int] $DelayMs = 900)
    & adb -s $device shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds $DelayMs
}

function Dump-UiXml {
    param([string] $Name)
    $remote = "/sdcard/atlastv-$Name.xml"
    $local = Join-Path $dumpDir "$Name.xml"
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        & adb -s $device shell uiautomator dump $remote | Out-Null
        $xml = (& adb -s $device exec-out cat $remote 2>$null) -join "`n"
        if ($xml -match "<hierarchy" -and $xml -notmatch "ERROR: null root") {
            Set-Content -LiteralPath $local -Value $xml -Encoding UTF8
            return $xml
        }
        & adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Seconds 1
    }
    throw "Could not dump UI XML for $Name."
}

function Assert-DumpSafe {
    $unsafe = Get-ChildItem -Path $dumpDir -File |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw } |
        Select-String -Pattern "username\s*[=:]|password\s*[=:]\s*[^`"f]|/live/|/movie/|/series/|https?://(?!REDACTED_HOST)" -CaseSensitive:$false
    if ($unsafe) { throw "Unsafe provider or playback text found in playback controls dumps." }
}

New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
Push-Location $RepoRoot
try {
    if (-not (Test-Path $apk)) { throw "Debug APK not found. Run .\gradlew assembleDebug first." }
    Write-Host "Onn playback controls target: physical Onn"
    & adb -s $device install -r $apk | Out-Null

    powershell -ExecutionPolicy Bypass -File $importSafeCache -DeviceSerial $device -TimeoutSeconds 300 |
        Set-Content -LiteralPath (Join-Path $dumpDir "import-safe-catalog-cache.txt") -Encoding UTF8

    Stop-AtlasTvOnnApp -Serial $device
    & adb -s $device shell am start -S `
        -n com.atlas.atlastv/.MainActivity `
        -a com.atlas.atlastv.DEBUG_OPEN_SERIES_PLAYBACK `
        --ei series_id $SeriesId `
        --ei episode_id $EpisodeId | Out-Null

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $playerXml = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $playerXml = Dump-UiXml "player"
        if ($playerXml.Contains("live_tv_player_screen")) { break }
    }
    if ($null -eq $playerXml -or -not $playerXml.Contains("live_tv_player_screen")) {
        throw "Playback screen did not open."
    }

    $panelXml = $null
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        Send-Key "KEYCODE_DPAD_CENTER" 500
        Send-Key "KEYCODE_DPAD_UP" 1200
        $panelXml = Dump-UiXml "track-panel-$attempt"
        if ($panelXml.Contains("playback_track_options_panel")) { break }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $panelXml -or -not $panelXml.Contains("playback_track_options_panel")) {
        throw "Audio/Subtitles panel did not open. The selected sample may not expose track options."
    }
    if (
        $panelXml -notmatch '(focused_playback_audio_track_option|focused_playback_subtitle_off_option|focused_playback_subtitle_track_option)' -or
        $panelXml -notmatch 'focused="true"'
    ) {
        throw "Audio/Subtitles panel opened but no selectable track option had focus."
    }

    Send-Key "KEYCODE_DPAD_DOWN" 700
    $downXml = Dump-UiXml "track-panel-after-down"
    if (-not $downXml.Contains("playback_track_options_panel")) {
        throw "D-pad Down left the Audio/Subtitles panel instead of navigating inside it."
    }

    Send-Key "KEYCODE_DPAD_CENTER" 900
    $afterSelectXml = Dump-UiXml "after-track-select"
    if ($afterSelectXml.Contains("playback_track_options_panel")) {
        throw "Center did not select the focused track option and close the panel."
    }

    Send-Key "KEYCODE_DPAD_UP" 900
    $reopenedXml = Dump-UiXml "track-panel-reopened"
    if (-not $reopenedXml.Contains("playback_track_options_panel")) {
        throw "Audio/Subtitles panel did not reopen for Back verification."
    }
    Send-Key "KEYCODE_BACK" 900
    $afterBackXml = Dump-UiXml "after-panel-back"
    if ($afterBackXml.Contains("playback_track_options_panel")) {
        throw "Back did not close the Audio/Subtitles panel."
    }
    if (-not $afterBackXml.Contains("live_tv_player_screen")) {
        throw "Back from Audio/Subtitles panel exited playback instead of closing the panel first."
    }

    Assert-DumpSafe
    Write-Host "Onn playback controls/tracks: pass"
    Write-Host "DumpDir: $dumpDir"
} finally {
    Pop-Location
}
