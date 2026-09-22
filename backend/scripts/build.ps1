<#
.SYNOPSIS
    Reproducible Maven build/run wrapper for the backend.

.DESCRIPTION
    The dev machine's JAVA_HOME points at a JDK that is not installed, and the `java` on PATH is a
    bleeding-edge JDK that Hibernate/Mockito do not support yet. This script therefore resolves a
    supported toolchain (JDK 17-24) explicitly and always invokes the same Maven binary, so every
    agent/phase produces identical results.

    Toolchain resolution order:
      1. -JavaHome parameter
      2. $env:ORDER_JDK_HOME
      3. portable Temurin 21 installed by scripts/setup-jdk.ps1
      4. any JAVA_HOME that actually contains bin\javac.exe and reports version 17-24
      5. Android Studio's bundled JetBrains Runtime 21 (present on this machine)

.EXAMPLE
    powershell -File backend/scripts/build.ps1 -Goal "clean compile"
    powershell -File backend/scripts/build.ps1 -Goal "test -Dtest=OrderProcessingPropertiesTest"
    powershell -File backend/scripts/build.ps1 -Goal "spring-boot:run" -SpringProfile local-mysql
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Goal,

    # Not named -JavaHome: PowerShell has a read-only automatic variable $HOME (case-insensitive).
    [string]$JdkHome,

    [string]$SpringProfile,

    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot

function Get-JdkVersion([string]$jdkRoot) {
    $javac = Join-Path $jdkRoot 'bin\javac.exe'
    if (-not (Test-Path $javac)) { return $null }
    $raw = & $javac -version 2>&1 | Out-String
    if ($raw -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    return $null
}

function Resolve-Toolchain {
    param([string]$Explicit, [string]$EnvHome)

    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($EnvHome) { $candidates += $EnvHome }
    $candidates += (Get-ChildItem -Path (Join-Path $env:USERPROFILE 'tools') -Directory -Filter 'jdk-2*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $version = Get-JdkVersion $candidate
        if ($version -and $version -ge 17 -and $version -le 24) {
            return [pscustomobject]@{ Home = $candidate; Version = $version }
        }
    }
    throw 'No supported JDK (17-24) found. Run backend/scripts/setup-jdk.ps1 first.'
}

$toolchain = Resolve-Toolchain -Explicit $JdkHome -EnvHome $env:ORDER_JDK_HOME
$env:JAVA_HOME = $toolchain.Home
Write-Host "Using JDK $($toolchain.Version) at $($toolchain.Home)" -ForegroundColor Cyan

$mavenArgs = @('-B')
if ($Quiet) { $mavenArgs += '-q' }
$mavenArgs += ($Goal -split '\s+')
if ($SpringProfile) { $mavenArgs += "-Dspring-boot.run.profiles=$SpringProfile" }
if ($SpringProfile -eq 'test') {
    # The `test` profile runs on H2, which is a test-scope dependency: without the test classpath
    # `spring-boot:run` cannot find org.h2.Driver at all (see smoke-test.ps1 for the same flag).
    $mavenArgs += '-Dspring-boot.run.useTestClasspath=true'
}

Push-Location $backendRoot
try {
    & mvn @mavenArgs
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($exitCode -ne 0) { throw "Maven goal '$Goal' failed with exit code $exitCode" }
Write-Host "Maven goal '$Goal' succeeded." -ForegroundColor Green
