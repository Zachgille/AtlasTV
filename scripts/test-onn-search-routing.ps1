param([string] $DeviceSerial = "")

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptRoot "..")
. (Join-Path $ScriptRoot "onn-device.ps1")

$device = Resolve-OnnDevice -Serial $DeviceSerial
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dumpDir = Join-Path $RepoRoot ".local-debug-dumps\onn-search-routing-$timestamp"
$apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"
$runCommand = Join-Path $ScriptRoot "run-debug-command.ps1"

function Send-Key {
    param([string] $Key, [int] $DelayMs = 900)
    & adb -s $device shell input keyevent $Key | Out-Null
    Start-Sleep -Milliseconds $DelayMs
}

function Dump-UiXml {
    param([string] $Name)
    $remote = "/sdcard/atlastv-$Name.xml"
    $local = Join-Path $dumpDir "$Name.xml"
    for ($attempt = 1; $attempt -le 6; $attempt++) {
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

function Start-CleanHome {
    & adb -s $device shell input keyevent KEYCODE_WAKEUP | Out-Null
    & adb -s $device shell am start -S -f 0x10008000 -n com.atlas.atlastv/.MainActivity | Out-Null
    Start-Sleep -Seconds 5
    $xml = Dump-UiXml "home-$script:caseName"
    if (-not $xml.Contains("home_screen")) { throw "Home did not open cleanly for $script:caseName." }
}

function Open-TopNavSearch {
    Send-Key "KEYCODE_DPAD_UP"
    $navXml = Dump-UiXml "top-nav-$script:caseName"
    if ($navXml -notmatch 'resource-id="top_nav_item_search"[^>]*focused="true"') {
        throw "Top-nav Search did not receive focus for $script:caseName."
    }
    Send-Key "KEYCODE_DPAD_CENTER" 1200
    $searchXml = Dump-UiXml "search-open-$script:caseName"
    if (-not $searchXml.Contains("unified_search_screen")) { throw "Search did not open for $script:caseName." }
}

function Run-SearchCase {
    param(
        [string] $Name,
        [string] $Query,
        [string] $FocusedResultTag,
        [string] $ExpectedTitle,
        [string] $DetailTag,
        [string] $DetailTitleTag,
        [bool] $OpenDetail = $true
    )
    $script:caseName = $Name
    Start-CleanHome
    Open-TopNavSearch
    & adb -s $device shell input text $Query | Out-Null
    Start-Sleep -Seconds 2
    $resultsXml = Dump-UiXml "search-results-$Name"
    if (-not $resultsXml.Contains($ExpectedTitle)) { throw "$Name search did not show expected title." }
    if (-not $resultsXml.Contains($FocusedResultTag)) { throw "$Name search did not focus expected result." }
    if ($OpenDetail) {
        Send-Key "KEYCODE_DPAD_CENTER" 2500
        $detailXml = Dump-UiXml "detail-$Name"
        if (-not $detailXml.Contains($DetailTag)) { throw "$Name did not open expected detail screen." }
        if (-not $detailXml.Contains($DetailTitleTag)) { throw "$Name did not expose expected detail title tag." }
        if (-not ($detailXml -match ('text="' + [regex]::Escape($ExpectedTitle) + '"'))) {
            throw "$Name detail did not show exact expected title."
        }
        Send-Key "KEYCODE_BACK" 1800
        $backXml = Dump-UiXml "after-back-$Name"
        if ($backXml.Contains($DetailTag)) {
            throw "$Name detail was still open after a single Back press."
        }
        Write-Host "${Name}: pass ($ExpectedTitle detail opened)"
    } else {
        Write-Host "${Name}: pass ($ExpectedTitle exact result focused)"
    }
}

function Assert-DumpSafe {
    $unsafe = Get-ChildItem -Path $dumpDir -File |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw } |
        Select-String -Pattern "username\s*[=:]|password\s*[=:]\s*[^`"f]|/live/|/movie/|/series/|https?://(?!REDACTED_HOST)" -CaseSensitive:$false
    if ($unsafe) { throw "Unsafe provider or playback text found in search routing dumps." }
}

New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
Push-Location $RepoRoot
try {
    if (-not (Test-Path $apk)) { throw "Debug APK not found. Run .\gradlew assembleDebug first." }
    Write-Host "Onn search routing target: physical Onn"
    & adb -s $device install -r $apk | Out-Null

    $importOutput = powershell -ExecutionPolicy Bypass -File $runCommand -Command run_fixture_catalog_import -DeviceSerial $device -TimeoutSeconds 180
    Set-Content -LiteralPath (Join-Path $dumpDir "run_fixture_catalog_import.txt") -Value ($importOutput -join "`n") -Encoding UTF8
    if (($importOutput -join "`n") -notmatch "Status:\s+success") { throw "Fixture Room import failed." }

    Run-SearchCase `
        -Name "movie" `
        -Query "1499" `
        -FocusedResultTag "focused_unified_search_result_movie-21499" `
        -ExpectedTitle "Imported Movie 1499" `
        -DetailTag "movie_detail_screen" `
        -DetailTitleTag "movie_detail_title"

    Run-SearchCase `
        -Name "series" `
        -Query "series%s1299" `
        -FocusedResultTag "focused_unified_search_result_show-31299" `
        -ExpectedTitle "Imported Series 1299" `
        -DetailTag "series_detail_page" `
        -DetailTitleTag "series_detail_title" `
        -OpenDetail $false

    Assert-DumpSafe
    Write-Host "Onn search routing: pass"
    Write-Host "DumpDir: $dumpDir"
} finally {
    Pop-Location
}
