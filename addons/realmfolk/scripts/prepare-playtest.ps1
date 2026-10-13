param(
    [string]$SourceMod=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..')),
    [string]$GameDirectory=(Join-Path $PSScriptRoot '../../.realmfolk-playtest')
)
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gameRoot=[IO.Path]::GetFullPath($GameDirectory)
if ($gameRoot -eq [IO.Path]::GetPathRoot($gameRoot) -or $gameRoot -eq [IO.Path]::GetFullPath($SourceMod)) { throw 'Choose a dedicated test directory.' }
$buildRoot=Join-Path (Split-Path $repoRoot -Parent) '.realmfolk-build'
$addonJar=Join-Path $buildRoot 'libs/realmfolk-0.4.0.jar'
$baseJar=Join-Path $SourceMod 'build/libs/geomantia-0.1.0.jar'
foreach($required in @($addonJar,$baseJar)) { if (!(Test-Path -LiteralPath $required)) { throw "Build first: $required" } }
foreach($directory in @('mods','config/geomantia','config/structureTemplate/terrasense','screenshots')) { New-Item -ItemType Directory -Path (Join-Path $gameRoot $directory) -Force | Out-Null }
# Keep a previous addon outside Forge's mods directory, so upgrades cannot load two versions.
$modsRoot=[IO.Path]::GetFullPath((Join-Path $gameRoot 'mods'))
$backups=Join-Path $gameRoot 'mod-backups'
Get-ChildItem -LiteralPath $modsRoot -Filter 'realmfolk-*.jar' -File | Where-Object { $_.Name -ne [IO.Path]::GetFileName($addonJar) } | ForEach-Object {
    if ([IO.Path]::GetDirectoryName($_.FullName) -ne $modsRoot) { throw 'Unexpected addon path.' }
    New-Item -ItemType Directory -Path $backups -Force | Out-Null
    Move-Item -LiteralPath $_.FullName -Destination (Join-Path $backups ($_.BaseName+'-'+[DateTime]::Now.ToString('yyyyMMddHHmmss')+'.jar'))
}
Copy-Item -LiteralPath $addonJar,$baseJar -Destination (Join-Path $gameRoot 'mods')
$sourceConfig=Join-Path $SourceMod 'run/config/geomantia'
Get-ChildItem -LiteralPath $sourceConfig -Filter '*.json' -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $gameRoot 'config/geomantia') }
$templates=Join-Path $SourceMod 'run/config/structureTemplate/terrasense/pcl_validation_02'
if(Test-Path -LiteralPath $templates) { Copy-Item -LiteralPath $templates -Destination (Join-Path $gameRoot 'config/structureTemplate/terrasense') -Recurse -Force }
foreach($dependency in @('reterraforged','trek','t_and_t','cristellib','libraryferret')) {
    $folder=Join-Path $SourceMod "libs/local/$dependency"
    if(Test-Path -LiteralPath $folder) { Get-ChildItem -LiteralPath $folder -Filter '*.jar' -File -Recurse | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $gameRoot 'mods') } }
}
# This local ReTerraForged build lacks pack metadata and otherwise stops at Forge's warning screen.
# Repair only the copied test JAR; leave the user's source dependency untouched.
Add-Type -AssemblyName System.IO.Compression.FileSystem
Get-ChildItem -LiteralPath (Join-Path $gameRoot 'mods') -Filter 'reterraforged*.jar' | ForEach-Object {
    $archive=[IO.Compression.ZipFile]::Open($_.FullName,[IO.Compression.ZipArchiveMode]::Update)
    try {
        if (!$archive.GetEntry('pack.mcmeta')) {
            $entry=$archive.CreateEntry('pack.mcmeta');$writer=[IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false))
            try {$writer.Write('{"pack":{"pack_format":15,"description":"ReTerraForged local playtest resources"}}')} finally {$writer.Dispose()}
        }
    } finally {$archive.Dispose()}
}
function Write-TestJson([string]$name,[scriptblock]$edit) {
    $path=Join-Path $gameRoot "config/geomantia/$name"
    $value=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    & $edit $value
    [IO.File]::WriteAllText($path,($value | ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
}
Write-TestJson 'provider.json' { param($value) $value.enabled=$false }
Write-TestJson 'city_design_queue.json' { param($value) $value.enabled=$false }
Write-TestJson 'planning_area_access.json' { param($value) $value.enabled=$false }
Write-TestJson 'mcp_server.json' { param($value) $value.port=5011 }
$options=@('lang:zh_cn','guiScale:2','renderDistance:8','simulationDistance:5','enableVsync:true','maxFps:60','pauseOnLostFocus:false','onboardAccessibility:false','showSubtitles:true')
$optionPath=Join-Path $gameRoot 'options.txt'
if(!(Test-Path -LiteralPath $optionPath)) { [IO.File]::WriteAllLines($optionPath,$options,[Text.UTF8Encoding]::new($false)) }
$provenance=[ordered]@{preparedAt=[DateTimeOffset]::Now.ToString('o');sourceMod=$SourceMod;gameDirectory=$gameRoot;geomantiaSha256=(Get-FileHash -LiteralPath $baseJar).Hash;realmfolkSha256=(Get-FileHash -LiteralPath $addonJar).Hash;providerEnabled=$false;copiedCredentialFiles=$false}
[IO.File]::WriteAllText((Join-Path $gameRoot 'dependency-manifest.json'),($provenance|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output "Prepared isolated playtest: $gameRoot"
