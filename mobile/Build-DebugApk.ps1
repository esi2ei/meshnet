[CmdletBinding()]
param(
    [switch]$RunConnectedTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mobileRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent $mobileRoot
$apkSource = Join-Path $mobileRoot "app\build\outputs\apk\debug\app-debug.apk"
$distRoot = Join-Path $mobileRoot "dist"
$apkDestination = Join-Path $distRoot "MeshNet-debug.apk"

function Get-RequiredApplication([string]$Name) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required command '$Name' was not found on PATH."
    }
    return $command
}

$java = Get-RequiredApplication "java"
$javaVersionText = (& $java.Source -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to query the Java runtime: $javaVersionText"
}
$javaMajor = $null
if ($javaVersionText -match '(?m)(?:openjdk|java) version "(?<major>\d+)') {
    $javaMajor = [int]$Matches["major"]
}
elseif ($javaVersionText -match '(?m)openjdk\s+(?<major>\d+)\.') {
    $javaMajor = [int]$Matches["major"]
}
if ($null -eq $javaMajor -or $javaMajor -lt 17) {
    throw "MeshNet requires JDK 17 or newer. Detected Java output: $javaVersionText"
}

$gradle = Get-RequiredApplication "gradle"
$gradleVersionText = (& $gradle.Source --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to query Gradle: $gradleVersionText"
}
if ($gradleVersionText -notmatch '(?m)^Gradle\s+(?<version>\d+\.\d+(?:\.\d+)?)') {
    throw "Could not determine the Gradle version. Output: $gradleVersionText"
}
$gradleVersion = [version]$Matches["version"]
if ($gradleVersion -lt [version]"9.3.1") {
    throw "Gradle 9.3.1 or newer is required; found $gradleVersion."
}

$sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$sdkRoot = $null
foreach ($candidate in $sdkCandidates) {
    $platformJar = Join-Path $candidate "platforms\android-37\android.jar"
    if (Test-Path -LiteralPath $platformJar -PathType Leaf) {
        $sdkRoot = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}
if ($null -eq $sdkRoot) {
    throw "Android SDK Platform 37 was not found. Set ANDROID_SDK_ROOT or ANDROID_HOME to an SDK containing platforms\android-37\android.jar."
}

$node = Get-RequiredApplication "node"
$nodeVersionText = (& $node.Source --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $nodeVersionText -notmatch '^v(?<major>\d+)\.') {
    throw "Unable to determine the Node.js version: $nodeVersionText"
}
$nodeMajor = [int]$Matches["major"]
if ($nodeMajor -lt 22) {
    throw "MeshNet dashboard builds require Node.js 22 or newer. Detected: $nodeVersionText"
}
$npm = Get-RequiredApplication "npm"

$env:ANDROID_SDK_ROOT = $sdkRoot
if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $env:ANDROID_HOME = $sdkRoot
}

Write-Host "Building MeshNet debug APK locally (no CI)..."
Write-Host "JDK major: $javaMajor"
Write-Host "Gradle: $gradleVersion"
Write-Host "Android SDK: $sdkRoot"
Write-Host "Node.js: $nodeVersionText"

Write-Host "Installing dashboard dependencies (no lockfile is written)..."
Push-Location -LiteralPath $repositoryRoot
try {
    & $npm.Source install --no-package-lock --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) {
        throw "npm dependency installation failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

Push-Location -LiteralPath $mobileRoot
try {
    & $gradle.Source --no-daemon --stacktrace :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle debug APK build failed with exit code $LASTEXITCODE."
    }

    if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) {
        throw "Gradle completed but the expected APK was not found: $apkSource"
    }

    New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
    Copy-Item -LiteralPath $apkSource -Destination $apkDestination -Force

    $apksigner = $null
    $buildToolsRoot = Join-Path $sdkRoot "build-tools"
    if (Test-Path -LiteralPath $buildToolsRoot -PathType Container) {
        $toolDirectories = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object { try { [version]$_.Name } catch { [version]"0.0" } } -Descending
        foreach ($toolDirectory in $toolDirectories) {
            foreach ($toolName in @("apksigner.bat", "apksigner")) {
                $candidate = Join-Path $toolDirectory.FullName $toolName
                if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                    $apksigner = $candidate
                    break
                }
            }
            if ($null -ne $apksigner) { break }
        }
    }

    if ($null -ne $apksigner) {
        & $apksigner verify --verbose $apkDestination
        if ($LASTEXITCODE -ne 0) {
            throw "APK signature verification failed."
        }
        Write-Host "APK signature: verified"
    }
    else {
        Write-Warning "apksigner was not found; the APK was built, but this script could not independently verify its signature."
    }

    $apkInfo = Get-Item -LiteralPath $apkDestination
    $apkHash = (Get-FileHash -LiteralPath $apkDestination -Algorithm SHA256).Hash
    Write-Host ""
    Write-Host "APK: $($apkInfo.FullName)"
    Write-Host "Size: $($apkInfo.Length) bytes"
    Write-Host "SHA-256: $apkHash"
}
finally {
    Pop-Location
}

if ($RunConnectedTests) {
    Write-Host ""
    Write-Host "Running Android instrumentation tests on a connected device/emulator..."
    Push-Location -LiteralPath $mobileRoot
    try {
        & $gradle.Source --no-daemon --stacktrace :app:connectedDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw "Connected Android instrumentation tests failed with exit code $LASTEXITCODE. The debug APK remains at $apkDestination."
        }
    }
    finally {
        Pop-Location
    }
}
