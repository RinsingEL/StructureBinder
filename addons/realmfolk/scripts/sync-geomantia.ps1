param([string]$SourceMod=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..')))
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$coreVersion=((Get-Content -LiteralPath (Join-Path $SourceMod 'gradle.properties') | Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=','').Trim()
$jar=Join-Path $SourceMod "build/libs/geomantia-$coreVersion.jar"
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the Geomantia core JAR first.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try {
    if (!$zip.GetEntry('com/rinsing/geomantia/api/planning/PlanningExtension.class')) { throw 'Geomantia core does not provide Planning API v1.' }
} finally { $zip.Dispose() }
$libs=Join-Path $repoRoot 'libs'
New-Item -ItemType Directory -Path $libs -Force | Out-Null
$target=Join-Path $libs 'geomantia-0.1.0-planning791e5b71.jar'
Copy-Item -LiteralPath $jar -Destination $target -Force
Write-Output ('Synced core dependency: '+$target)
Write-Output ('SHA256: '+(Get-FileHash -LiteralPath $target).Hash)
