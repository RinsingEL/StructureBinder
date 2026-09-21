param([string]$ClientJar, [int]$Port = 8765)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
    }
    if (-not (Test-Path -LiteralPath '.cache/registry.json')) {
        node scripts/export_registry.mjs
        if ($LASTEXITCODE -ne 0) { throw 'Block registry preparation failed' }
    }
    if (-not (Test-Path -LiteralPath '.cache/resources.json')) {
        if (-not $ClientJar) { throw 'First launch needs -ClientJar pointing to a local Minecraft 1.20.1 client.jar' }
        python cli.py prepare --jar $ClientJar
        if ($LASTEXITCODE -ne 0) { throw 'Resource preparation failed' }
    }
    if (-not (Test-Path -LiteralPath 'dist/index.html')) {
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    }
    python cli.py serve --port $Port
    if ($LASTEXITCODE -ne 0) { throw 'Studio server stopped with an error' }
} finally { Pop-Location }
