param(
    [string]$StatePath = 'E:\Mod_Dev\StructureBinder\run\workflow_state\requirement_case_cleanup.json'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $StatePath)) {
    Write-Output 'DUE state_missing'
    exit 0
}

try {
    $state = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
    if ([string]$state.status -ne 'CLEAN') {
        Write-Output "DUE status_$($state.status)"
        exit 0
    }
    if ([string]::IsNullOrWhiteSpace([string]$state.lastCompletedAt)) {
        Write-Output 'DUE last_completed_missing'
        exit 0
    }
    if ([string]::IsNullOrWhiteSpace([string]$state.nextCheckAt)) {
        Write-Output 'DUE next_check_missing'
        exit 0
    }
    $nextCheck = [DateTimeOffset]::Parse([string]$state.nextCheckAt)
    if ([DateTimeOffset]::Now -ge $nextCheck) {
        Write-Output 'DUE timestamp_elapsed'
        exit 0
    }
    Write-Output "OK $($nextCheck.ToString('o'))"
} catch {
    Write-Output 'DUE state_invalid'
}
