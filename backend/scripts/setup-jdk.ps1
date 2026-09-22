<#
.SYNOPSIS
    Installs a portable Temurin JDK 21 under %USERPROFILE%\tools (no admin rights, no system changes).

.DESCRIPTION
    Why this exists: the project targets Java 17 bytecode, but the local dev machine only exposes a
    JDK 25 runtime plus a stale JAVA_HOME. Hibernate and Mockito (ByteBuddy-based) do not support
    JDK 25 yet, so builds must run on a JDK 17-24 toolchain. This script provisions a self-contained
    Temurin 21 JDK inside the user profile; backend/scripts/build.ps1 then picks it up automatically.

.EXAMPLE
    powershell -NoProfile -File backend/scripts/setup-jdk.ps1
#>
param(
    [string]$InstallRoot = (Join-Path $env:USERPROFILE 'tools'),
    [string]$MajorVersion = '21',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$targetDir = Join-Path $InstallRoot "jdk-$MajorVersion"

if ((Test-Path (Join-Path $targetDir 'bin\javac.exe')) -and -not $Force) {
    $existing = & (Join-Path $targetDir 'bin\javac.exe') -version 2>&1 | Out-String
    Write-Host "Toolchain already present: $($existing.Trim()) at $targetDir" -ForegroundColor Green
    return
}

$archive = Join-Path $env:TEMP "temurin-$MajorVersion.zip"
$url = "https://api.adoptium.net/v3/binary/latest/$MajorVersion/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"

Write-Host "Downloading Temurin JDK $MajorVersion ..." -ForegroundColor Cyan
& curl.exe -L --fail --silent --show-error -o $archive $url
if ($LASTEXITCODE -ne 0) { throw "Download failed from $url" }

$extractDir = Join-Path $env:TEMP "temurin-$MajorVersion-extract"
if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
Expand-Archive -Path $archive -DestinationPath $extractDir -Force

$inner = Get-ChildItem $extractDir -Directory | Select-Object -First 1
if (-not $inner) { throw 'Extracted archive did not contain a JDK directory.' }

New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
if (Test-Path $targetDir) { Remove-Item $targetDir -Recurse -Force }
Move-Item $inner.FullName $targetDir

Remove-Item $archive -Force -ErrorAction SilentlyContinue
Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue

$javac = Join-Path $targetDir 'bin\javac.exe'
$version = & $javac -version 2>&1 | Out-String
Write-Host "Installed $($version.Trim())" -ForegroundColor Green
Write-Host "JAVA_HOME for this project: $targetDir" -ForegroundColor Green
