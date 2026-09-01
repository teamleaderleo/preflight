[CmdletBinding()]
param(
    [string]$Game = 'C:\Games\Starsector',
    [string]$Launcher = 'C:\Games\Starsector\Play-Starsector-VM.cmd',
    [string]$PreflightJar,
    [string]$Scenario,
    [string]$RunDirectory,
    [string]$RunRoot = "$env:USERPROFILE\Documents\Starsector Preflight Runs",
    [string]$Label = 'windows-gameplay',
    [string]$GalliumDriver = 'llvmpipe'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repository = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($PreflightJar)) {
    $PreflightJar = Join-Path $repository 'preflight-cli\target\preflight.jar'
}
if ([string]::IsNullOrWhiteSpace($Scenario)) {
    $Scenario = Join-Path $PSScriptRoot 'scenarios\campaign-paused-unpaused-optimized.json'
}
if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $RunDirectory = Join-Path $RunRoot "$stamp-$Label"
}

foreach ($inputPath in @($Game, $Launcher, $PreflightJar, $Scenario)) {
    if (-not (Test-Path -LiteralPath $inputPath)) {
        throw "Required path does not exist: $inputPath"
    }
}

$java = $null
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $java = $candidate }
}
if ($null -eq $java) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) { $java = $javaCommand.Source }
}
if ($null -eq $java) { throw 'Java was not found through JAVA_HOME or PATH' }

if ([string]::IsNullOrWhiteSpace($GalliumDriver)) {
    Remove-Item Env:GALLIUM_DRIVER -ErrorAction SilentlyContinue
} else {
    $env:GALLIUM_DRIVER = $GalliumDriver
}

New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
Write-Host "Preflight JAR: $PreflightJar"
Write-Host "Scenario: $Scenario"
Write-Host "Run directory: $RunDirectory"

& $java -jar $PreflightJar desktop smoke launch $Scenario $RunDirectory `
    --game $Game --launcher $Launcher
exit $LASTEXITCODE
