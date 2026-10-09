param([int]$Port = 8767, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$workbenchRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workbenchOutput = Join-Path $workbenchRoot 'runtime/campus-workbench'
$workbenchUrl = "http://127.0.0.1:$Port"
New-Item -ItemType Directory -Path $workbenchOutput -Force | Out-Null
try {
    $workbenchStatus = Invoke-RestMethod "$workbenchUrl/api/campus/default" -TimeoutSec 2
    if ($workbenchStatus.arrays -and $workbenchStatus.districts) {
        Write-Output "Workbench already running: $workbenchUrl"
        if (-not $NoBrowser) { Start-Process $workbenchUrl }
        return
    }
} catch { }
$workbenchPython = (Get-Command python -ErrorAction Stop).Source
$env:PYTHONIOENCODING = 'utf-8'
Push-Location (Join-Path $workbenchRoot 'tools/structure_studio')
try {
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
Push-Location $workbenchRoot
try {
    & $workbenchPython -c "import sys; sys.path.insert(0,'tools/city_experiments'); import workbench; workbench.prepare()"
    if ($LASTEXITCODE -ne 0) { throw 'Java preparation failed; see runtime/campus-workbench/prepare.log.' }
} finally { Pop-Location }
$workbenchProcess = Start-Process -FilePath $workbenchPython -ArgumentList @('tools/city_experiments/workbench.py','--port',"$Port") -WorkingDirectory $workbenchRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workbenchOutput 'server.out.log') -RedirectStandardError (Join-Path $workbenchOutput 'server.err.log')
$workbenchProcess.Id | Set-Content (Join-Path $workbenchOutput 'server.pid')
$workbenchReady = $false
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ($workbenchProcess.HasExited) { throw 'Server failed; see runtime/campus-workbench/server.err.log.' }
    try { Invoke-RestMethod "$workbenchUrl/api/campus/default" -TimeoutSec 1 | Out-Null; $workbenchReady=$true; break } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $workbenchReady) { throw 'Server is still preparing; check the startup logs.' }
Write-Output "Workbench: $workbenchUrl; background process $($workbenchProcess.Id)"
if (-not $NoBrowser) { Start-Process $workbenchUrl }
