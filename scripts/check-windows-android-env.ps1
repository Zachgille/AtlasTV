$ErrorActionPreference = "Continue"

$jbrHome = $env:JAVA_HOME
$sdkHome = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Write-Check {
    param(
        [string] $Status,
        [string] $Name,
        [string] $Detail
    )
    Write-Host ("[{0}] {1}: {2}" -f $Status, $Name, $Detail)
}

function Get-CommandPath {
    param([string] $Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        return $null
    }
    return $command.Source
}

function Get-FirstOutputLine {
    param([scriptblock] $Command)

    try {
        $output = & $Command 2>&1 | Out-String
        return (($output -split "`r?`n") | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1)
    } catch {
        return $_.Exception.Message
    }
}

Write-Host "Windows Android Environment Check"
Write-Host ""

$javaPath = Get-CommandPath "java"
if ($javaPath) {
    $javaVersion = Get-FirstOutputLine { java -version }
    if ($jbrHome -and $javaPath.StartsWith($jbrHome, [System.StringComparison]::OrdinalIgnoreCase) -and $javaVersion -match 'version "17[.\"]') {
        Write-Check "PASS" "java" "$javaPath ($javaVersion)"
    } else {
        Write-Check "WARN" "java" "$javaPath ($javaVersion); expected configured JDK 17 first on PATH"
    }
} else {
    Write-Check "FAIL" "java" "not found on PATH"
}

if ($jbrHome -and (Test-Path (Join-Path $jbrHome "bin\javac.exe")) -and (Select-String -LiteralPath (Join-Path $jbrHome "release") -Pattern '^JAVA_VERSION="17[.\"]')) {
    Write-Check "PASS" "JAVA_HOME" $env:JAVA_HOME
} else {
    Write-Check "FAIL" "JAVA_HOME" "expected a Java 17 JDK"
}

$adbPath = Get-CommandPath "adb"
if ($adbPath) {
    $adbVersion = Get-FirstOutputLine { adb version }
    Write-Check "PASS" "adb" "$adbPath ($adbVersion)"
} else {
    Write-Check "FAIL" "adb" "not found on PATH"
}

$sdkmanagerPath = Get-CommandPath "sdkmanager"
if ($sdkmanagerPath) {
    $sdkmanagerVersion = Get-FirstOutputLine { sdkmanager --version }
    Write-Check "PASS" "sdkmanager" "$sdkmanagerPath ($sdkmanagerVersion)"
} else {
    Write-Check "WARN" "sdkmanager" "not found on PATH"
}

if ($env:ANDROID_HOME -eq $sdkHome) {
    Write-Check "PASS" "ANDROID_HOME" $env:ANDROID_HOME
} else {
    Write-Check "FAIL" "ANDROID_HOME" "found '$env:ANDROID_HOME', expected '$sdkHome'"
}

if ($env:ANDROID_SDK_ROOT -eq $sdkHome) {
    Write-Check "PASS" "ANDROID_SDK_ROOT" $env:ANDROID_SDK_ROOT
} else {
    Write-Check "FAIL" "ANDROID_SDK_ROOT" "found '$env:ANDROID_SDK_ROOT', expected '$sdkHome'"
}

$gradlew = Join-Path $repoRoot "gradlew.bat"
if (Test-Path -LiteralPath $gradlew) {
    $gradleOutput = & $gradlew --version 2>&1 | Out-String
    $gradleVersion = (($gradleOutput -split "`r?`n") | Where-Object { $_ -match "^Gradle " } | Select-Object -First 1)
    if (-not $gradleVersion) {
        $gradleVersion = (($gradleOutput -split "`r?`n") | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1)
    }
    if ($LASTEXITCODE -eq 0) {
        Write-Check "PASS" "Gradle wrapper" $gradleVersion
    } else {
        Write-Check "FAIL" "Gradle wrapper" $gradleVersion
    }
} else {
    Write-Check "FAIL" "Gradle wrapper" "gradlew.bat not found"
}
