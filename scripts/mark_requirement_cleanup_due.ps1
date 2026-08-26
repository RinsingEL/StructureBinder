param(
    [string]$Reason = 'requirement_changed',
    [string]$StatePath = 'E:\Mod_Dev\StructureBinder\run\workflow_state\requirement_case_cleanup.json'
)

$ErrorActionPreference = 'Stop'
$stateDirectory = Split-Path -Parent $StatePath
if (-not (Test-Path -LiteralPath $stateDirectory)) {
    New-Item -ItemType Directory -Path $stateDirectory | Out-Null
}

$values = [ordered]@{
    schemaVersion = 'requirement_case_cleanup_state.v0.1'
    intervalDays = 1
    lastCompletedAt = ''
    nextCheckAt = ''
    status = 'DUE_REQUIREMENT_CHANGED'
    dueReason = $Reason
    markedAt = [DateTimeOffset]::Now.ToString('o')
}

if (Test-Path -LiteralPath $StatePath) {
    try {
        $existing = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
        foreach ($property in $existing.PSObject.Properties) {
            $values[$property.Name] = $property.Value
        }
    } catch {
        # Replace invalid state with a valid due state.
    }
    $values.status = 'DUE_REQUIREMENT_CHANGED'
    $values.dueReason = $Reason
    $values.markedAt = [DateTimeOffset]::Now.ToString('o')
}

$values | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
Write-Output "DUE requirement_changed $Reason"
