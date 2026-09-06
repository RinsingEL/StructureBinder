param([string]$GameDirectory=(Join-Path $PSScriptRoot '../../build/playtests/packaged-20260906'),
      [string]$World='RTF_packaged_fresh_20260906',
      [string]$VersionDirectory='D:/PCL2-2.8.12/.minecraft/versions/1.20.1-Forge_47.4.23')
$ErrorActionPreference='Stop'
$gameRoot=(Resolve-Path -LiteralPath $GameDirectory).Path
if ([IO.Path]::GetFileName($World) -ne $World -or $World -in @('.', '..')) {
    throw 'World must be a save folder name, not a path.'
}
if (!(Test-Path -LiteralPath (Join-Path $gameRoot "saves/$World/level.dat") -PathType Leaf)) {
    throw "Quick Play save does not exist: $World"
}
$versionRoot=(Resolve-Path -LiteralPath $VersionDirectory).Path
$minecraftRoot=Split-Path (Split-Path $versionRoot -Parent) -Parent
$versionId=Split-Path $versionRoot -Leaf
$metadata=Get-Content -LiteralPath (Join-Path $versionRoot "$versionId.json") -Raw | ConvertFrom-Json
$libraryRoot=Join-Path $minecraftRoot 'libraries'
$nativeRoot=Join-Path $gameRoot 'natives'
New-Item -ItemType Directory -Path $nativeRoot -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Allowed($rules) {
    if (!$rules) { return $true }
    $allow=$false
    foreach ($rule in $rules) {
        if ($rule.features) { continue }
        if ($rule.os.name -and $rule.os.name -ne 'windows') { continue }
        if ($rule.os.arch -and $rule.os.arch -ne 'x86_64') { continue }
        $allow=$rule.action -eq 'allow'
    }
    return $allow
}
$classpath=[System.Collections.Generic.List[string]]::new()
foreach ($library in $metadata.libraries) {
    if (!(Allowed $library.rules)) { continue }
    $artifact=$library.downloads.artifact.path
    if (!$artifact) {
        $parts=$library.name.Split(':')
        $suffix=if ($parts.Length -gt 3) { '-'+$parts[3] } else { '' }
        $artifact=$parts[0].Replace('.','/')+'/'+$parts[1]+'/'+$parts[2]+'/'+$parts[1]+'-'+$parts[2]+$suffix+'.jar'
    }
    $file=Join-Path $libraryRoot $artifact
    if (!(Test-Path -LiteralPath $file)) { throw "Missing library: $artifact" }
    $classpath.Add($file)
    if ($artifact -match 'natives-windows') {
        $zip=[IO.Compression.ZipFile]::OpenRead($file)
        try { foreach ($entry in $zip.Entries) {
            if ($entry.Name -like '*.dll') { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $nativeRoot $entry.Name),$true) }
        }} finally { $zip.Dispose() }
    }
}
$classpath.Add((Join-Path $versionRoot "$versionId.jar"))
$values=@{
    natives_directory=$nativeRoot; classpath=($classpath -join ';'); library_directory=$libraryRoot
    classpath_separator=';'; version_name=$versionId; launcher_name='PackagedAcceptance'; launcher_version='1'
    game_directory=$gameRoot; assets_root=(Join-Path $minecraftRoot 'assets'); assets_index_name=$metadata.assetIndex.id
    auth_player_name='RTFAcceptance'; auth_uuid='a41b73e65df34a1c984fb0ab90e8fc11'; auth_access_token='0'
    clientid='0'; auth_xuid='0'; user_type='legacy'; version_type='release'
}
function Expand-Argument([string]$argument) {
    foreach ($key in $values.Keys) { $argument=$argument.Replace(('${'+$key+'}'),[string]$values[$key]) }
    return $argument
}
$argsList=[System.Collections.Generic.List[string]]::new()
$argsList.Add('-Xmx6G'); $argsList.Add('-Dgeomantia.apiPort=5001')
$argsList.Add('-Dgeomantia.landUseFailureCaptureDir='+(Join-Path $gameRoot 'landuse-failure-captures'))
$argsList.Add('-Dgeomantia.providerPlanningSourceDir=D:/PCL2-2.8.12/.minecraft/versions/1.20.1-Forge_47.4.23/config/structureTemplate/terrasense/template_cfg')
foreach ($argument in $metadata.arguments.jvm) {
    if ($argument -is [string]) { $argsList.Add((Expand-Argument $argument)) }
    elseif (Allowed $argument.rules) { foreach ($value in @($argument.value)) { $argsList.Add((Expand-Argument $value)) } }
}
$argsList.Add($metadata.mainClass)
foreach ($argument in $metadata.arguments.game) {
    if ($argument -is [string]) { $argsList.Add((Expand-Argument $argument)) }
}
$argsList.Add('--quickPlaySingleplayer'); $argsList.Add($World)
$argsList.Add('--width'); $argsList.Add('1280'); $argsList.Add('--height'); $argsList.Add('800')
$argFile=Join-Path $gameRoot 'launch-arguments.txt'
$encoded=$argsList | ForEach-Object { '"'+$_.Replace('\','/').Replace('"','\"')+'"' }
[IO.File]::WriteAllLines($argFile,$encoded,[Text.UTF8Encoding]::new($false))
$process=Start-Process -FilePath 'C:/Program Files/Java/jdk-17/bin/java.exe' -ArgumentList ('@"'+$argFile+'"') -WorkingDirectory $gameRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $gameRoot 'launch.stdout.log') -RedirectStandardError (Join-Path $gameRoot 'launch.stderr.log')
Write-Output "Packaged Forge PID=$($process.Id); gameDir=$gameRoot; world=$World; offline local test identity"
