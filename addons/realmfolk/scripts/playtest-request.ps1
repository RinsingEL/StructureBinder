param(
    [string]$Route='/state',
    [string]$Body='{}',
    [int]$Port=5021,
    [string]$GameDirectory=(Join-Path $PSScriptRoot '../../.realmfolk-playtest')
)
$ErrorActionPreference='Stop'
$gameRoot=[IO.Path]::GetFullPath($GameDirectory)
$token=[IO.File]::ReadAllText((Join-Path $gameRoot 'playtest-token.txt')).Trim()
$logDir=Join-Path $PSScriptRoot '../playtest-evidence'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$entry=[ordered]@{time=[DateTimeOffset]::Now.ToString('o');tool='realmfolk_local_playtest';route=$Route;request=($Body|ConvertFrom-Json);result=$null}
try {
    $result=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$Port+$Route) -Method Post -Headers @{'X-Realmfolk-Token'=$token} -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($Body)) -TimeoutSec 25
    $entry.result=$result
    $result | ConvertTo-Json -Depth 30
} catch { $entry.result=[ordered]@{error=$_.Exception.Message};throw }
finally { [IO.File]::AppendAllText((Join-Path $logDir 'calls.jsonl'),(($entry|ConvertTo-Json -Depth 35 -Compress)+[Environment]::NewLine),[Text.UTF8Encoding]::new($false)) }
