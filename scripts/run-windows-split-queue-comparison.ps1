[CmdletBinding()]
param(
    [string]$Game = 'C:\Games\Starsector',
    [string]$PreflightJar = 'C:\Projects\starsector-preflight\preflight-cli\target\preflight.jar',
    [string]$RunRoot = 'C:\Users\Leo\Documents\Starsector Preflight Cohorts',
    [ValidateRange(1, 10)]
    [int]$Rounds = 1,
    [ValidateRange(0, 600)]
    [int]$CooldownSeconds = 0,
    [switch]$DiscoveryProbes
)

$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-windows-startup-cohort.ps1'

for ($round = 1; $round -le $Rounds; $round++) {
    $splitFirst = ($round % 2) -eq 0
    foreach ($splitQueues in @($splitFirst, -not $splitFirst)) {
        $label = if ($splitQueues) { 'split-queues' } else { 'current-main' }
        Write-Host "Windows prepared-prefetch comparison: round $round, $label"
        $arguments = @{
            Game = $Game
            PreflightJar = $PreflightJar
            RunRoot = $RunRoot
            Iterations = 1
            CooldownSeconds = $CooldownSeconds
            Conditions = @('preflight')
            OptimizationPreset = 'recommended'
        }
        if ($splitQueues) {
            $arguments.WindowsPreparedSplitQueueProbe = $true
        }
        if ($DiscoveryProbes) {
            $arguments.StartupPhaseProbe = $true
            $arguments.WindowsPreparedColdProbe = $true
        }
        & $runner @arguments
    }
}
