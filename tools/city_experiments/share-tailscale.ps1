param([int]$Port = 8767)
$ErrorActionPreference = 'Stop'
$campusRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$campusOutput = Join-Path $campusRoot 'runtime/campus-workbench'
$campusTailscale = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
if (-not $campusTailscale) { $campusTailscale = 'C:/Program Files/Tailscale/tailscale.exe' }
if (-not (Test-Path -LiteralPath $campusTailscale)) { throw 'Tailscale is not installed.' }
$campusState = & $campusTailscale status --json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $campusState.BackendState -ne 'Running') { throw 'Connect Tailscale first.' }
$campusDns = $campusState.Self.DNSName.TrimEnd('.')
if (-not $campusDns) { throw 'Tailscale node DNS name is unavailable.' }
$campusOrigin = "http://${campusDns}:$Port"
$campusTarget = "http://127.0.0.1:$Port"
$campusServe = & $campusTailscale serve status --json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Tailscale Serve.' }
if ($campusServe.TCP -and $campusServe.TCP.PSObject.Properties.Name -contains "$Port") {
    $campusWeb = $campusServe.Web.PSObject.Properties["${campusDns}:$Port"].Value
    if (-not $campusWeb -or $campusWeb.Handlers.'/'.Proxy -ne $campusTarget) {
        throw 'This Tailscale port is already used by another service.'
    }
}
Invoke-RestMethod "$campusTarget/api/campus/default" -TimeoutSec 3 | Out-Null
& $campusTailscale serve --bg "--http=$Port" $campusTarget
if ($LASTEXITCODE -ne 0) { throw 'Tailscale Serve configuration failed.' }
New-Item -ItemType Directory -Path $campusOutput -Force | Out-Null
$campusAccessPath = Join-Path $campusOutput 'access.json'
$campusOrigins = @($campusOrigin)
if (Test-Path -LiteralPath $campusAccessPath) {
    $campusPrevious = Get-Content -LiteralPath $campusAccessPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $campusOrigins = @($campusPrevious.allowedOrigins) + $campusOrigins
}
$campusAccessJson = @{allowedOrigins=@($campusOrigins | Select-Object -Unique)} | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($campusAccessPath + '.tmp', $campusAccessJson, (New-Object System.Text.UTF8Encoding($false)))
Move-Item -LiteralPath ($campusAccessPath + '.tmp') -Destination $campusAccessPath -Force
Write-Output "Campus workbench on Tailscale: $campusOrigin/"
