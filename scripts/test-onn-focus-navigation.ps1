param([string] $DeviceSerial = "")

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptRoot "..")
. (Join-Path $ScriptRoot "onn-device.ps1")

$device = Resolve-OnnDevice -Serial $DeviceSerial
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $RepoRoot ".local-debug-dumps\onn-focus-navigation-$timestamp"
$apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
$runCommand = Join-Path $ScriptRoot "run-debug-command.ps1"

function Send-Key {
    param([string] $Key)
    & adb -s $device shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds 900
}

function Dump-UiXml {
    param([string] $Name)
    $remote = "/sdcard/atlastv-$Name.xml"
    $local = Join-Path $dumpDir "$Name.xml"
    for ($attempt = 1; $attempt -le 5; $attempt++) {
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

function Assert-RowFocus {
    param(
        [string] $Xml,
        [string] $Step,
        [string] $RowTag
    )
    if (-not $Xml.Contains($RowTag)) { throw "$Step did not show $RowTag." }
    if (-not $Xml.Contains("focused_home_card")) { throw "$Step did not expose focused_home_card." }
    if (($Xml | Select-String -Pattern 'focused="true"' -AllMatches).Matches.Count -lt 1) {
        throw "$Step had no focused UI node."
    }
    Write-Host "${Step}: pass ($RowTag focused card visible)"
}

function Assert-TopNavFocus {
    param([string] $Xml)
    if ($Xml -notmatch 'resource-id="top_nav_item_search"[^>]*focused="true"') {
        throw "Top nav Search did not receive focus."
    }
    Write-Host "top_nav_up: pass (Search focused)"
}

function Assert-DumpSafe {
    $unsafe = Get-ChildItem -Path $dumpDir -File |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw } |
        Select-String -Pattern "username\s*[=:]|password\s*[=:]\s*[^`"f]|/live/|/movie/|/series/|https?://(?!REDACTED_HOST)" -CaseSensitive:$false
    if ($unsafe) { throw "Unsafe provider or playback text found in focus navigation dumps." }
}

New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
Push-Location $RepoRoot
try {
    if (-not (Test-Path $apk)) { throw "Debug APK not found. Run .\gradlew assembleDebug first." }
    Write-Host "Onn focus navigation target: physical Onn"
    & adb -s $device install -r $apk | Out-Null

    $importOutput = powershell -ExecutionPolicy Bypass -File $runCommand -Command run_fixture_catalog_import -DeviceSerial $device -TimeoutSeconds 180
    Set-Content -LiteralPath (Join-Path $dumpDir "run_fixture_catalog_import.txt") -Value ($importOutput -join "`n") -Encoding UTF8
    if (($importOutput -join "`n") -notmatch "Status:\s+success") { throw "Fixture Room import failed." }

    & adb -s $device shell am force-stop com.atlas.atlastv | Out-Null
    & adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
    & adb -s $device shell am start -n com.atlas.atlastv/.MainActivity | Out-Null
    Start-Sleep -Seconds 5

    $initial = Dump-UiXml "00-initial"
    Assert-RowFocus -Xml $initial -Step "initial" -RowTag "popular_row"

    Send-Key "KEYCODE_DPAD_DOWN"
    $movies = Dump-UiXml "01-down-recent-movies"
    Assert-RowFocus -Xml $movies -Step "down_to_recent_movies" -RowTag "recently_added_movies_row"

    Send-Key "KEYCODE_DPAD_DOWN"
    $shows = Dump-UiXml "02-down-recent-shows"
    Assert-RowFocus -Xml $shows -Step "down_to_recent_shows" -RowTag "recently_added_shows_row"

    Send-Key "KEYCODE_DPAD_UP"
    $backToMovies = Dump-UiXml "03-up-recent-movies"
    Assert-RowFocus -Xml $backToMovies -Step "up_to_recent_movies" -RowTag "recently_added_movies_row"

    Send-Key "KEYCODE_DPAD_UP"
    $backToPopular = Dump-UiXml "04-up-popular"
    Assert-RowFocus -Xml $backToPopular -Step "up_to_popular" -RowTag "popular_row"

    Send-Key "KEYCODE_DPAD_UP"
    $topNav = Dump-UiXml "05-up-top-nav"
    Assert-TopNavFocus -Xml $topNav

    Assert-DumpSafe
    Write-Host "Onn focus navigation: pass"
    Write-Host "DumpDir: $dumpDir"
} finally {
    Pop-Location
}
