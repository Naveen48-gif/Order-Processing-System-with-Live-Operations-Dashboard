<#
.SYNOPSIS
    Live API smoke test: boots the real application, drives the order flow over HTTP, and reports which
    required endpoints exist.

.DESCRIPTION
    The automated suite (backend/scripts/build.ps1 -Goal "clean test") runs in-process with MockMvc. This
    script is the complement: it starts the actual Spring Boot server on :8080 and talks to it over real
    HTTP, which is how you catch wiring problems a slice test cannot see (missing controllers, absent
    WebSocket broker, wrong profile, CORS, actuator).

    It runs on the `test` profile with H2 and the in-process transport, so no Docker, PostgreSQL or
    RabbitMQ is needed. Exit code 0 = every endpoint the frozen contract requires answered; exit code
    1 = at least one required endpoint is missing (the output names them).

.EXAMPLE
    powershell -NoProfile -File backend/scripts/smoke-test.ps1
    powershell -NoProfile -File backend/scripts/smoke-test.ps1 -KeepRunning
#>
param(
    [string]$JdkHome,
    [int]$StartupTimeoutSeconds = 90,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$logFile = Join-Path $env:TEMP 'order-management-smoke.log'

function Resolve-BuildJdk {
    $candidates = @()
    if ($JdkHome) { $candidates += $JdkHome }
    if ($env:ORDER_JDK_HOME) { $candidates += $env:ORDER_JDK_HOME }
    $candidates += (Get-ChildItem -Path (Join-Path $env:USERPROFILE 'tools') -Directory -Filter 'jdk-2*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'C:\Program Files\Android\Android Studio\jbr'

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $javac = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path $javac)) { continue }
        $raw = & $javac -version 2>&1 | Out-String
        if ($raw -match 'javac\s+(\d+)' -and [int]$Matches[1] -ge 17 -and [int]$Matches[1] -le 24) {
            return $candidate
        }
    }
    throw 'No supported JDK (17-24) found. Run backend/scripts/setup-jdk.ps1, or pass -JdkHome.'
}

function Test-Port([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $null = $client.ConnectAsync('127.0.0.1', $port).Wait(700)
        return $client.Connected
    }
    catch { return $false }
    finally { $client.Close() }
}

function Invoke-Probe([string]$uri, [string]$method = 'GET', [string]$body = $null) {
    try {
        $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 15 }
        if ($body) { $params['ContentType'] = 'application/json'; $params['Body'] = $body }
        $response = Invoke-WebRequest @params
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $response.Content }
    }
    catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ Status = $status; Body = $null }
    }
}

$env:JAVA_HOME = Resolve-BuildJdk
Write-Host "Using JAVA_HOME=$($env:JAVA_HOME)" -ForegroundColor Cyan
Remove-Item $logFile -ErrorAction SilentlyContinue

$server = Start-Process -PassThru -FilePath 'mvn.cmd' `
    -ArgumentList '-B', 'spring-boot:run', '-Dspring-boot.run.useTestClasspath=true', '-Dspring-boot.run.profiles=test' `
    -WorkingDirectory $backendRoot `
    -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" `
    -WindowStyle Hidden

$ready = $false
$missing = 0
$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)

try {
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-Port 8080) { $ready = $true; break }
    }

    if (-not $ready) {
        Write-Host 'BOOT FAILED - last log lines:' -ForegroundColor Red
        Get-Content $logFile -Tail 30 -ErrorAction SilentlyContinue
        Get-Content "$logFile.err" -Tail 15 -ErrorAction SilentlyContinue
        exit 1
    }

    $base = 'http://localhost:8080'
    Write-Host "`n--- endpoint probe (contract AGENT.md sections 4 and 5) ---" -ForegroundColor Cyan

    $probes = @(
        @{ Uri = '/actuator/health';       Label = 'actuator health' },
        @{ Uri = '/api/products';          Label = 'product list (phase 4)' },
        @{ Uri = '/api/inventory';         Label = 'inventory list (phase 4)' },
        @{ Uri = '/api/orders';            Label = 'order list (phase 5)' },
        @{ Uri = '/api/dashboard/summary'; Label = 'dashboard summary (phase 10)' },
        @{ Uri = '/api/dlq';               Label = 'DLQ listing (phase 10)' },
        @{ Uri = '/ws';                    Label = 'STOMP endpoint (phase 11)' }
    )

    foreach ($probe in $probes) {
        $result = Invoke-Probe "$base$($probe.Uri)"
        # A STOMP endpoint answers a plain GET with 400 - that still proves the handler is registered.
        $present = ($result.Status -eq 200) -or ($probe.Uri -eq '/ws' -and $result.Status -eq 400)
        if ($present) {
            Write-Host ("[OK]      {0,-34} HTTP {1}" -f $probe.Label, $result.Status) -ForegroundColor Green
        }
        else {
            $missing++
            Write-Host ("[MISSING] {0,-34} HTTP {1}" -f $probe.Label, $result.Status) -ForegroundColor Red
        }
    }

    Write-Host "`n--- live order flow ---" -ForegroundColor Cyan
    $product = Invoke-Probe "$base/api/products" 'POST' '{"name":"Smoke Test Laptop","price":999.00,"initialQuantity":4}'
    $productId = $null
    if ($product.Status -eq 201) {
        $productId = [regex]::Match($product.Body, '"id":(\d+)').Groups[1].Value
        Write-Host "[OK]      product created (id $productId)" -ForegroundColor Green
    }
    elseif ($product.Status -eq 409) {
        $list = Invoke-Probe "$base/api/products"
        $productId = [regex]::Match($list.Body, '"id":(\d+),"name":"Smoke Test Laptop"').Groups[1].Value
        Write-Host "[OK]      product already present from an earlier run (id $productId)" -ForegroundColor Green
    }

    if ($productId) {
        $order = Invoke-Probe "$base/api/orders" 'POST' ('{"productId":' + $productId + ',"quantity":2}')
        Write-Host ("[status]  POST /api/orders -> HTTP {0} {1}" -f $order.Status, $order.Body) -ForegroundColor Cyan

        if ($order.Status -eq 201 -or $order.Status -eq 200) {
            $orderId = [regex]::Match($order.Body, '"id":(\d+)').Groups[1].Value
            Start-Sleep -Seconds 3

            $settled = Invoke-Probe "$base/api/orders/$orderId"
            $orderStatus = [regex]::Match($settled.Body, '"status":"([A-Z_]+)"').Groups[1].Value
            $colour = 'Red'
            if ($orderStatus -eq 'COMPLETED') { $colour = 'Green' }
            Write-Host ("[settled] order {0} -> {1}" -f $orderId, $orderStatus) -ForegroundColor $colour

            $after = Invoke-Probe "$base/api/inventory/$productId"
            $remaining = [regex]::Match($after.Body, '"quantity":(\d+)').Groups[1].Value
            Write-Host ("[stock]   inventory now {0}" -f $remaining) -ForegroundColor Cyan
        }
    }
    else {
        Write-Host "[FAIL]    could not create a product (HTTP $($product.Status))" -ForegroundColor Red
    }

    if ($missing -eq 0) {
        Write-Host "`nAll required endpoints answered." -ForegroundColor Green
    }
    else {
        Write-Host "`n$missing required endpoint(s) missing - see the MISSING lines above." -ForegroundColor Yellow
    }
}
finally {
    if (-not $KeepRunning) {
        if ($server -and -not $server.HasExited) { taskkill /PID $server.Id /T /F 2>&1 | Out-Null }
        Start-Sleep -Seconds 2
        Write-Host "Server stopped (full log: $logFile)" -ForegroundColor Cyan
    }
}

if ($missing -gt 0) { exit 1 } else { exit 0 }

