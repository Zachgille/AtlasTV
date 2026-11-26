param(
    [string] $JavaHome = "",
    [string] $SdkHome = (Join-Path $env:LOCALAPPDATA "Android\Sdk")
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$backupDir = Join-Path $repoRoot ".local-env-backups"

function Test-Java17Home {
    param([string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    $releaseFile = Join-Path $Path "release"
    return (Test-Path -LiteralPath (Join-Path $Path "bin\javac.exe")) -and
        (Test-Path -LiteralPath $releaseFile) -and
        [bool](Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="17[.\"]')
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $candidates = @($env:JAVA_HOME)
    $localJdks = Join-Path $backupDir "jdk17"
    if (Test-Path -LiteralPath $localJdks) {
        $candidates += @(Get-ChildItem -LiteralPath $localJdks -Directory | ForEach-Object { $_.FullName })
    }
    $candidates += "C:\Program Files\Android\Android Studio\jbr"
    $JavaHome = $candidates | Where-Object { Test-Java17Home $_ } | Select-Object -First 1
}
if (-not (Test-Java17Home $JavaHome)) {
    throw "Java 17 JDK is required. Supply -JavaHome with an installed JDK 17 path."
}
$jbrHome = (Resolve-Path -LiteralPath $JavaHome).Path
$sdkHome = $SdkHome

function Write-Status {
    param(
        [string] $Status,
        [string] $Message
    )
    Write-Host "[$Status] $Message"
}

function Test-ExpectedPath {
    param([string] $Path)

    if (Test-Path -LiteralPath $Path) {
        Write-Status "PASS" "Found $Path"
        return $true
    }

    Write-Status "FAIL" "Missing $Path"
    return $false
}

function Add-PathEntries {
    param(
        [string] $ExistingPath,
        [string[]] $Entries
    )

    $seen = New-Object "System.Collections.Generic.HashSet[string]" ([System.StringComparer]::OrdinalIgnoreCase)
    $result = New-Object "System.Collections.Generic.List[string]"

    foreach ($entry in $Entries) {
        if ((Test-Path -LiteralPath $entry) -and $seen.Add($entry)) {
            [void] $result.Add($entry)
            Write-Status "PASS" "Ensured PATH entry $entry"
        } elseif (Test-Path -LiteralPath $entry) {
            Write-Status "PASS" "PATH already contains $entry"
        } else {
            Write-Status "WARN" "Skipped missing PATH entry $entry"
        }
    }

    foreach ($entry in ($ExistingPath -split ";")) {
        $trimmed = $entry.Trim()
        if ($trimmed.Length -eq 0) {
            continue
        }
        if ($seen.Add($trimmed)) {
            [void] $result.Add($trimmed)
        }
    }

    return ($result -join ";")
}

$requiredPaths = @(
    $jbrHome,
    (Join-Path $jbrHome "bin\java.exe"),
    $sdkHome,
    (Join-Path $sdkHome "platform-tools\adb.exe")
)

$cmdlineTools = Join-Path $sdkHome "cmdline-tools\latest\bin"
$allRequiredPresent = $true
foreach ($path in $requiredPaths) {
    if (-not (Test-ExpectedPath $path)) {
        $allRequiredPresent = $false
    }
}

if (-not $allRequiredPresent) {
    throw "Required Android/JBR paths are missing. Install Android Studio and SDK tools before rerunning."
}

if (Test-Path -LiteralPath $cmdlineTools) {
    Write-Status "PASS" "Found $cmdlineTools"
} else {
    Write-Status "WARN" "Optional cmdline-tools path is missing: $cmdlineTools"
}

[Environment]::SetEnvironmentVariable("JAVA_HOME", $jbrHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $sdkHome, "User")

$env:JAVA_HOME = $jbrHome
$env:ANDROID_HOME = $sdkHome
$env:ANDROID_SDK_ROOT = $sdkHome

Write-Status "PASS" "Set User JAVA_HOME to $jbrHome"
Write-Status "PASS" "Set User ANDROID_HOME to $sdkHome"
Write-Status "PASS" "Set User ANDROID_SDK_ROOT to $sdkHome"

# Android Studio uses #GRADLE_LOCAL_JAVA_HOME. Keep this machine-specific path
# in ignored project-local configuration rather than tracked gradle.properties.
$gradleConfigDir = Join-Path $repoRoot ".gradle"
New-Item -ItemType Directory -Force -Path $gradleConfigDir | Out-Null
$gradleConfigFile = Join-Path $gradleConfigDir "config.properties"
if (Test-Path -LiteralPath $gradleConfigFile) {
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    Copy-Item -LiteralPath $gradleConfigFile -Destination (Join-Path $backupDir ("gradle-config-{0}.properties" -f (Get-Date -Format "yyyyMMdd-HHmmss")))
    $otherConfig = @(Get-Content -LiteralPath $gradleConfigFile | Where-Object { $_ -notmatch '^java\.home\s*=' })
} else {
    $otherConfig = @()
}
$javaPropertyPath = $jbrHome.Replace("\", "/")
Set-Content -LiteralPath $gradleConfigFile -Value ($otherConfig + "java.home=$javaPropertyPath") -Encoding ASCII
Write-Status "PASS" "Set project-local Android Studio Gradle JDK to Java 17"

$oldUserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($null -eq $oldUserPath) {
    $oldUserPath = ""
}

New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupPath = Join-Path $backupDir "user-path-$timestamp.txt"
Set-Content -LiteralPath $backupPath -Value $oldUserPath -Encoding UTF8
Write-Status "PASS" "Backed up old User PATH to $backupPath"

$pathEntries = @(
    (Join-Path $jbrHome "bin"),
    (Join-Path $sdkHome "platform-tools")
)
if (Test-Path -LiteralPath $cmdlineTools) {
    $pathEntries += $cmdlineTools
}

$newUserPath = Add-PathEntries -ExistingPath $oldUserPath -Entries $pathEntries
[Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")

$processPathEntries = @($pathEntries) + @($env:Path -split ";")
$processSeen = New-Object "System.Collections.Generic.HashSet[string]" ([System.StringComparer]::OrdinalIgnoreCase)
$processPath = New-Object "System.Collections.Generic.List[string]"
foreach ($entry in $processPathEntries) {
    $trimmed = $entry.Trim()
    if ($trimmed.Length -gt 0 -and $processSeen.Add($trimmed)) {
        [void] $processPath.Add($trimmed)
    }
}
$env:Path = ($processPath -join ";")

Write-Status "PASS" "Updated User PATH without removing existing entries"
Write-Status "WARN" "Open a new PowerShell window so Windows reloads the updated User environment."
Write-Host ""
Write-Host "Verification:"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
Write-Host "java: $((Get-Command java -ErrorAction SilentlyContinue).Source)"
Write-Host "adb: $((Get-Command adb -ErrorAction SilentlyContinue).Source)"
