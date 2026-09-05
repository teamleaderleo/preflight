param(
    [string]$RunnerPath = '',
    [string]$RunnerSource = '',
    [string]$BaselineRunnerSource = ''
)

# Only import the process helpers, never dot-source the operator entry point.
# All process, time, and output effects below are mocked; CIM instances are client-only.
$ErrorActionPreference = 'Stop'
if (-not $RunnerSource) {
    if (-not $RunnerPath) { $RunnerPath = Join-Path $PSScriptRoot 'run-windows-startup-cohort.ps1' }
    $RunnerSource = Get-Content -LiteralPath $RunnerPath -Raw
}
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput(
    $RunnerSource, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
foreach ($name in @('Get-GameProcesses', 'Get-WindowOwnerProcessId', 'Stop-GameProcesses')) {
    $functions = @($ast.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $name
    }, $true))
    if ($functions.Count -ne 1) { throw "Expected one $name" }
    . ([scriptblock]::Create($functions[0].Extent.Text))
}

function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
# Exercise the real user32 binding without sending any window messages.
Assert ((Get-WindowOwnerProcessId ([IntPtr]::Zero)) -eq 0) 'Invalid window has no owner'
function Get-WindowOwnerProcessId {
    param($Handle)
    if ($script:ownerFails) { throw 'Mock ownership lookup failure' }
    if ($script:foreignWindow) { return 999 }
    if ($script:unknownOwner) { return 0 }
    return $script:windowPid
}
function Get-CimInstance { $script:alive }
function Get-Date { $script:clock }
function Start-Sleep([int]$Seconds) {
    $script:slept += $Seconds
    $script:clock = $script:clock.AddSeconds($Seconds)
    if ($script:exitAfter -ge 0 -and $script:slept -ge $script:exitAfter) {
        $script:alive = @()
    }
}
function Get-Process {
    param($Id, $ErrorAction)
    $script:windowPid = $Id
    $window = [pscustomobject]@{
        MainWindowHandle = $script:handle
        MainWindowTitle = ('t' * 300)
    }
    $window | Add-Member ScriptMethod CloseMainWindow {
        $script:closeCalls++
        return $script:sendResult
    }
    return $window
}
function Stop-Process {
    param($Id, [switch]$Force, $ErrorAction)
    Assert $Force 'Cleanup must retain Force'
    if ($script:telemetry -and -not $script:writeFails) {
        Assert ($script:reports[-1].phase -eq 'forcing') 'Capture before forced cleanup'
    }
    $script:killed += $Id
}
function Set-Content {
    param([Parameter(ValueFromPipeline=$true)]$Value, $LiteralPath, $Encoding)
    process {
        if ($script:writeFails) { throw 'Mock inaccessible telemetry destination' }
        Assert ($LiteralPath -eq 'C:\mock-run\shutdown.json') 'Telemetry must stay in run folder'
        $script:reports += ($Value | ConvertFrom-Json)
    }
}

$cases = @(
    @{count=0; exitAfter=0},
    @{count=1; exitAfter=2},
    @{count=3; exitAfter=2},
    @{count=1; exitAfter=-1},
    @{count=3; exitAfter=-1},
    @{count=36; exitAfter=-1},
    @{count=1; exitAfter=2; noTelemetry=$true},
    @{count=1; exitAfter=-1; writeFails=$true},
    @{count=1; exitAfter=-1; sendFalse=$true},
    @{count=1; exitAfter=-1; noWindow=$true},
    @{count=1; exitAfter=-1; foreignWindow=$true},
    @{count=1; exitAfter=-1; unknownOwner=$true},
    @{count=1; exitAfter=-1; ownerFails=$true}
)
foreach ($case in $cases) {
    $script:clock = [datetime]'2026-09-05T00:00:00Z'
    $script:slept = 0
    $script:closeCalls = 0
    $script:killed = @()
    $script:reports = @()
    $script:exitAfter = $case.exitAfter
    $script:telemetry = -not $case.noTelemetry
    $script:writeFails = [bool]$case.writeFails
    $script:sendResult = -not $case.sendFalse
    $script:handle = if ($case.noWindow) { 0 } else { 123 }
    $script:foreignWindow = [bool]$case.foreignWindow
    $script:unknownOwner = [bool]$case.unknownOwner
    $script:ownerFails = [bool]$case.ownerFails
    $script:alive = @(for ($i = 0; $i -lt $case.count; $i++) {
        New-CimInstance -ClassName Win32_Process -ClientOnly -Property @{
            ProcessId = [uint32](1000 + $i)
            ParentProcessId = [uint32]999
            Name = 'java.exe'
            CommandLine = 'java.exe -cp C:\mock-game\starfarer_obf.jar'
            CreationDate = $script:clock
        }
    })
    Assert (@(Get-GameProcesses 'C:\mock-game').Count -eq $case.count) 'CIM fixture cardinality'
    $directory = if ($script:telemetry) { 'C:\mock-run' } else { '' }
    $output = @(Stop-GameProcesses 'C:\mock-game' $directory 3>$null)
    $skipClose = $case.noWindow -or $case.foreignWindow -or $case.unknownOwner -or $case.ownerFails
    $expectedCloseCalls = if ($skipClose) { 0 } else { $case.count }
    Assert ($script:closeCalls -eq $expectedCloseCalls) 'Only close windows owned by the selected process'
    $expectedGraceful = $case.exitAfter -ge 0
    Assert ($output.Count -eq 1 -and $output[0] -is [bool]) 'Return exactly one boolean'
    Assert ($output[0] -eq $expectedGraceful) 'Preserve graceful semantics'
    $expectedWait = if ($expectedGraceful) { $case.exitAfter } else { 45 }
    Assert ($script:slept -eq $expectedWait) 'Wait for singleton/multiple exit or full deadline'
    $expectedKills = if ($expectedGraceful) { 0 } else { $case.count }
    Assert ($script:killed.Count -eq $expectedKills) 'Only force survivors after timeout'
    if ($script:telemetry -and -not $script:writeFails) {
        $last = $script:reports[-1]
        Assert ($last.phase -eq 'complete') 'Final telemetry checkpoint'
        Assert ($last.initialCount -eq $case.count) 'Initial total'
        Assert ($last.remainingCount -eq $expectedKills) 'Survivor total'
        Assert ($last.gracefulShutdown -eq $expectedGraceful) 'Telemetry matches result'
        Assert (@($last.closeRequests).Count -eq [Math]::Min(32, $case.count)) 'Bound close samples'
        Assert (@($last.remaining).Count -eq [Math]::Min(32, $expectedKills)) 'Bound survivor samples'
        Assert ($script:reports.Count -le 4) 'Bound checkpoint count'
        if ($case.count) {
            Assert ($last.closeRequests[0].windowTitle.Length -eq 256) 'Bound title length'
            if (-not $skipClose) {
                Assert ($last.closeRequests[0].closeMessageSent -eq $script:sendResult) 'Record close result'
            } else {
                Assert ($null -eq $last.closeRequests[0].closeMessageSent) 'No close message for unverified window'
                Assert ($null -ne $last.closeRequests[0].closeSkippedReason) 'Record why close was skipped'
            }
        }
    } else {
        Assert ($script:reports.Count -eq 0) 'Optional/failed telemetry cannot alter cleanup'
    }
}
# Evaluate only the real monitoring filter, with inert process records. The CLI stays alive
# after its game exits (for example when a batch pauses), and must not mask that exit.
$filters = @($ast.FindAll({ param($node)
    $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left.Extent.Text -eq '$gameProcesses'
}, $true))
Assert ($filters.Count -eq 1) 'Expected one game JVM monitoring filter'
$filter = [scriptblock]::Create($filters[0].Right.Extent.Text)
$process = [pscustomobject]@{ Id = 100 }
$usesPreflight = $true
$launcherRecord = [pscustomobject]@{ Name = 'java.exe'; ProcessId = 100 }
$gameRecord = [pscustomobject]@{ Name = 'java.exe'; ProcessId = 200 }
$running = @($launcherRecord, $gameRecord)
$actual = @(& $filter)
Assert ($actual.Count -eq 1 -and $actual[0].ProcessId -eq 200) 'Exclude CLI while preserving real game'
$running = @($launcherRecord)
Assert (@(& $filter).Count -eq 0) 'A surviving CLI must not mask game exit'
$usesPreflight = $false
Assert (@(& $filter).Count -eq 1) 'A directly launched vanilla JVM remains a game'
'PASS: launcher/game PID classification'

"PASS: $($cases.Count) mocked shutdown cases; PowerShell $($PSVersionTable.PSVersion)"

# Optional diagnosis-only comparison with the exact unchanged runner. Do not assume
# its failure: print observed cardinality, elapsed mock time, return and forced PIDs.
if ($BaselineRunnerSource) {
    foreach ($variant in @('new', 'old')) {
        $source = if ($variant -eq 'new') { $RunnerSource } else { $BaselineRunnerSource }
        $tree = [System.Management.Automation.Language.Parser]::ParseInput(
            $source, [ref]$tokens, [ref]$parseErrors)
        if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
        foreach ($name in @('Get-GameProcesses', 'Stop-GameProcesses')) {
            $definition = @($tree.FindAll({ param($node)
                $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                    $node.Name -eq $name
            }, $true))
            Assert ($definition.Count -eq 1) "Expected one $name in $variant"
            . ([scriptblock]::Create($definition[0].Extent.Text))
        }
        foreach ($count in @(0, 1, 3)) {
            $script:clock = [datetime]'2026-09-05T00:00:00Z'
            $script:slept = 0
            $script:closeCalls = 0
            $script:killed = @()
            $script:exitAfter = -1
            $script:telemetry = $false
            $script:sendResult = $true
            $script:handle = 123
            $script:foreignWindow = $false
            $script:unknownOwner = $false
            $script:ownerFails = $false
            $script:alive = @(for ($i = 0; $i -lt $count; $i++) {
                New-CimInstance -ClassName Win32_Process -ClientOnly -Property @{
                    ProcessId = [uint32](1000 + $i)
                    ParentProcessId = [uint32]999
                    Name = 'java.exe'
                    CommandLine = 'java.exe -cp C:\mock-game\starfarer_obf.jar'
                    CreationDate = $script:clock
                }
            })
            $unwrapped = Get-GameProcesses 'C:\mock-game'
            $result = Stop-GameProcesses 'C:\mock-game'
            [ordered]@{
                variant = $variant
                cimObjects = $count
                unwrappedCount = $unwrapped.Count
                waitedSeconds = $script:slept
                graceful = $result
                forcedPids = @($script:killed)
            } | ConvertTo-Json -Compress
        }
    }
}
