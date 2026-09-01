[CmdletBinding()]
param(
    [string]$Game = 'C:\Games\Starsector',
    [string]$PreflightJar = 'C:\VM-Setup\Preflight\preflight.jar',
    [string]$Cache = 'C:\Users\Leo\AppData\Local\Starsector Preflight\cache',
    [string]$RunRoot = 'C:\Users\Leo\Documents\Starsector Preflight Cohorts',
    [ValidateRange(1, 20)]
    [int]$Iterations = 3,
    [ValidateRange(0, 600)]
    [int]$CooldownSeconds = 20,
    [int]$Seed = 449,
    [string]$Resolution,
    [ValidateSet('starsector', 'preflight', 'fast-rendering', 'preflight-fast-rendering')]
    [string[]]$Conditions = @('starsector', 'preflight', 'fast-rendering', 'preflight-fast-rendering')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$env:GALLIUM_DRIVER = 'llvmpipe'

function Get-Sha256([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-GameProcesses([string]$GamePath) {
    $escaped = [regex]::Escape($GamePath)
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        if ($_.Name -eq 'starsector.exe') { return $true }
        if ($_.Name -eq 'cmd.exe') { return $_.CommandLine -match $escaped }
        if ($_.Name -notin @('java.exe', 'javaw.exe')) { return $false }
        return $_.CommandLine -match $escaped -or
            $_.CommandLine -match 'com\.fs\.starfarer\.StarfarerLauncher' -or
            $_.CommandLine -match '(?:^|[\\/;])starfarer_obf\.jar(?:[;" ]|$)' -or
            $_.CommandLine -match '(?:^|[\\/;])fr\.(?:jar|agent\.jar)(?:[;" ]|$)'
    })
}

function Stop-GameProcesses([string]$GamePath) {
    $processes = Get-GameProcesses $GamePath
    foreach ($candidate in $processes) {
        $windowProcess = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
        if ($windowProcess -and $windowProcess.MainWindowHandle -ne 0) {
            [void]$windowProcess.CloseMainWindow()
        }
    }
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline -and (Get-GameProcesses $GamePath).Count -gt 0) {
        Start-Sleep -Seconds 1
    }
    $remaining = Get-GameProcesses $GamePath
    $graceful = $remaining.Count -eq 0
    foreach ($candidate in $remaining) {
        Stop-Process -Id $candidate.ProcessId -Force -ErrorAction SilentlyContinue
    }
    return $graceful
}

function Quote-Arguments([string[]]$Arguments) {
    return ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
}

function Read-GameLog([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    $stream = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
    try {
        $reader = [System.IO.StreamReader]::new($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        if ($stream) { $stream.Dispose() }
    }
}

function Measure-OneRun(
    [string]$Condition,
    [int]$Iteration,
    [string]$RunDirectory,
    [string]$Java,
    [string]$VanillaLauncher,
    [string]$FastRenderingLauncher,
    [string]$GameLog,
    [string]$DirectResolution
) {
    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    if (Test-Path -LiteralPath $GameLog) {
        Move-Item -LiteralPath $GameLog -Destination (Join-Path $RunDirectory 'starsector-before.log') -Force
    }
    if ((Get-GameProcesses $Game).Count -gt 0) {
        throw "A Starsector process is already running under $Game"
    }

    $usesPreflight = $Condition -in @('preflight', 'preflight-fast-rendering')
    $usesFastRendering = $Condition -in @('fast-rendering', 'preflight-fast-rendering')
    $launcher = if ($usesFastRendering) { $FastRenderingLauncher } else { $VanillaLauncher }
    $startedAt = Get-Date
    $directLaunchOptions = "-DlaunchDirect=true -DstartRes=$DirectResolution -DstartFS=false -DstartSound=true"
    $savedPrivateJavaOptions = $env:_JAVA_OPTIONS
    $env:_JAVA_OPTIONS = (($savedPrivateJavaOptions, $directLaunchOptions | Where-Object { $_ }) -join ' ').Trim()
    try {
        if ($usesPreflight) {
            $arguments = @(
                '-jar', $PreflightJar,
                'run', '--game', $Game,
                '--launcher', $launcher,
                '--trace-dir', $RunDirectory,
                '--texture-cache-dir', $Cache,
                '--no-scan', '--fast', '--no-record'
            )
            $process = Start-Process -FilePath $Java -ArgumentList (Quote-Arguments $arguments) `
                -WorkingDirectory $Game -PassThru `
                -RedirectStandardOutput (Join-Path $RunDirectory 'stdout.log') `
                -RedirectStandardError (Join-Path $RunDirectory 'stderr.log')
        } else {
            $savedJavaToolOptions = $env:JAVA_TOOL_OPTIONS
            $env:JAVA_TOOL_OPTIONS = $null
            $commandLine = "/d /s /c call `"$launcher`""
            try {
                $process = Start-Process -FilePath 'cmd.exe' -ArgumentList $commandLine `
                    -WorkingDirectory (Split-Path -Parent $launcher) -PassThru `
                    -RedirectStandardOutput (Join-Path $RunDirectory 'stdout.log') `
                    -RedirectStandardError (Join-Path $RunDirectory 'stderr.log')
            } finally {
                $env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
            }
        }
    } finally {
        $env:_JAVA_OPTIONS = $savedPrivateJavaOptions
    }

    $deadline = (Get-Date).AddMinutes(15)
    $graphicsPreloadObserved = $false
    $log = ''
    $fastRenderingObserved = $false
    $gameJvmObserved = $false
    $launcherFailed = $false
    do {
        Start-Sleep -Seconds 2
        $log = Read-GameLog $GameLog
        $graphicsPreloadObserved = $log -match 'VRAM after unload/preload:'
        $running = Get-GameProcesses $Game
        $gameJvmObserved = $gameJvmObserved -or (@($running | Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe', 'starsector.exe')
        }).Count -gt 0)
        if ($usesFastRendering -and -not $fastRenderingObserved) {
            $fastRenderingObserved = @($running | Where-Object {
                $_.CommandLine -match 'fr\.(?:bat|jar|vmparams)'
            }).Count -gt 0
        }
        $process.Refresh()
        $launcherFailed = $process.HasExited -and -not $gameJvmObserved -and
            ((Get-Date) - $startedAt).TotalSeconds -ge 30
    } while (-not $graphicsPreloadObserved -and -not $launcherFailed -and (Get-Date) -lt $deadline)

    $startMatch = [regex]::Match(
        $log,
        '(?m)^\s*(\d+)\s+\[.*(?:Running with the following mods \(in order of priority\):|Running vanilla game with no mods\.)'
    )
    $readyMatch = [regex]::Match($log, '(?m)^\s*(\d+)\s+\[.*VRAM after unload/preload:')
    $elapsedMs = if ($startMatch.Success -and $readyMatch.Success) {
        [long]$readyMatch.Groups[1].Value - [long]$startMatch.Groups[1].Value
    } else { $null }

    if ($usesPreflight -and $graphicsPreloadObserved) {
        $adapterDeadline = (Get-Date).AddSeconds(20)
        $adapterPath = Join-Path $RunDirectory 'adapter.json'
        while (-not (Test-Path -LiteralPath $adapterPath -PathType Leaf) -and
                (Get-Date) -lt $adapterDeadline -and (Get-GameProcesses $Game).Count -gt 0) {
            Start-Sleep -Seconds 2
        }
    }
    $gracefulShutdown = Stop-GameProcesses $Game
    $adapterPath = Join-Path $RunDirectory 'adapter.json'
    $adapter = if (Test-Path -LiteralPath $adapterPath) {
        Get-Content -LiteralPath $adapterPath -Raw | ConvertFrom-Json
    } else { $null }
    $runPath = Join-Path $RunDirectory 'run.json'
    $run = if (Test-Path -LiteralPath $runPath) {
        Get-Content -LiteralPath $runPath -Raw | ConvertFrom-Json
    } else { $null }
    $expectedOwner = if ($usesFastRendering) { 'FAST_RENDERING' } else { 'STARSECTOR' }
    $runtimeOwner = if ($run -and $run.PSObject.Properties.Name -contains 'runtimeOwner') {
        [string]$run.runtimeOwner
    } else { $null }
    $adapterHealthy = if (-not $usesPreflight) {
        $null
    } else {
        $adapter -and
        $adapter.mode -eq 'ENABLED' -and
        -not $adapter.killSwitchActive -and
        $adapter.transformerInstalled -and
        $runtimeOwner -eq $expectedOwner
    }
    $accepted = $graphicsPreloadObserved -and $elapsedMs -ne $null -and $gracefulShutdown
    if ($usesPreflight) { $accepted = $accepted -and $adapterHealthy }
    if ($usesFastRendering) { $accepted = $accepted -and $fastRenderingObserved }

    if (Test-Path -LiteralPath $GameLog) {
        Copy-Item -LiteralPath $GameLog -Destination (Join-Path $RunDirectory 'starsector.log') -Force
    }
    return [pscustomobject]@{
        condition = $Condition
        iteration = $Iteration
        accepted = [bool]$accepted
        startedAt = $startedAt.ToString('o')
        finishedAt = (Get-Date).ToString('o')
        gameLogStartToGraphicsPreloadMs = $elapsedMs
        graphicsPreloadObserved = [bool]$graphicsPreloadObserved
        gameJvmObserved = [bool]$gameJvmObserved
        launcherFailed = [bool]$launcherFailed
        launcherExitCode = if ($process.HasExited) { $process.ExitCode } else { $null }
        gracefulShutdown = [bool]$gracefulShutdown
        usesPreflight = [bool]$usesPreflight
        usesFastRendering = [bool]$usesFastRendering
        fastRenderingObserved = [bool]$fastRenderingObserved
        adapterReportWritten = [bool]$adapter
        adapterHealthy = $adapterHealthy
        runtimeOwner = $runtimeOwner
        transformationsApplied = if ($adapter) { $adapter.transformationsApplied } else { $null }
        exactMatches = if ($adapter) { $adapter.exactMatches } else { $null }
        transformationDeclined = if ($adapter) { $adapter.transformationDeclined } else { $null }
        launcher = $launcher
        runDirectory = $RunDirectory
    }
}

function Get-Median([double[]]$Values) {
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $middle = [math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

$explorer = Get-Process explorer -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -gt 0 }
if (-not $explorer) {
    throw 'interactive-session-required: sign in to the Windows VM before running a GUI benchmark'
}
Add-Type -AssemblyName System.Windows.Forms
$primaryScreen = [System.Windows.Forms.Screen]::PrimaryScreen
if (-not $primaryScreen) { throw 'primary-display-required: Windows reported no primary screen' }
$displayBounds = $primaryScreen.Bounds
$workingArea = $primaryScreen.WorkingArea
if ([string]::IsNullOrWhiteSpace($Resolution)) {
    $Resolution = '{0}x{1}' -f $workingArea.Width, $workingArea.Height
}
if ($Resolution -notmatch '^(?<width>[1-9]\d*)x(?<height>[1-9]\d*)$' -or
        [int]$Matches.width -lt 800 -or [int]$Matches.height -lt 600) {
    throw "Resolution must be WIDTHxHEIGHT and at least 800x600: $Resolution"
}
if (-not (Test-Path -LiteralPath $Game -PathType Container)) { throw "Game directory is missing: $Game" }
if (-not (Test-Path -LiteralPath $PreflightJar -PathType Leaf)) { throw "Preflight JAR is missing: $PreflightJar" }

$java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (-not $java) { $java = Join-Path $Game 'jre\bin\java.exe' }
$vanillaLauncher = Join-Path $Game 'Play-Starsector-VM.cmd'
if (-not (Test-Path -LiteralPath $vanillaLauncher)) {
    $vanillaLauncher = Join-Path $Game 'starsector-core\starsector.bat'
}
$fastRenderingLauncher = Join-Path $Game 'starsector-core\fr.bat'
$gameLog = Join-Path $Game 'starsector-core\starsector.log'
foreach ($required in @($java, $vanillaLauncher)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required file is missing: $required" }
}
if (@($Conditions | Where-Object { $_ -match 'fast-rendering' }).Count -gt 0 -and
    -not (Test-Path -LiteralPath $fastRenderingLauncher -PathType Leaf)) {
    throw "Fast Rendering is not installed: $fastRenderingLauncher"
}

New-Item -ItemType Directory -Path $Cache, $RunRoot -Force | Out-Null
$sessionDirectory = Join-Path $RunRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-windows-startup-2x2')
New-Item -ItemType Directory -Path $sessionDirectory -Force | Out-Null

$preparationPerformed = $false
$prepareExitCode = $null
if (@($Conditions | Where-Object { $_ -match 'preflight' }).Count -gt 0) {
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $cacheCheckArguments = @(
        '-jar', $PreflightJar, 'run', '--game', $Game,
        '--launcher', $vanillaLauncher,
        '--texture-cache-dir', $Cache, '--no-scan', '--fast', '--dry-run'
    )
    & $java @cacheCheckArguments *> (Join-Path $sessionDirectory 'preflight-cache-check.log')
    $cacheIsCurrent = $LASTEXITCODE -eq 0
    if ($cacheIsCurrent) {
        $prepareExitCode = 0
        'The exact installed profile already has a valid prepared cache.' |
            Set-Content -LiteralPath (Join-Path $sessionDirectory 'preflight-prepare.log') -Encoding UTF8
    } else {
        $preparationPerformed = $true
        $prepareArguments = @('-jar', $PreflightJar, 'prepare', '--game', $Game, '--cache-dir', $Cache, '--deep', '--verify-lookups')
        & $java @prepareArguments 2>&1 | Tee-Object -FilePath (Join-Path $sessionDirectory 'preflight-prepare.log')
        $prepareExitCode = $LASTEXITCODE
    }
    $ErrorActionPreference = $savedErrorPreference
    if ($prepareExitCode -ne 0) { throw "Preflight preparation failed with exit code $prepareExitCode" }
}

$enabledMods = Join-Path $Game 'mods\enabled_mods.json'
$identity = [ordered]@{
    version = 2
    startedAt = (Get-Date).ToString('o')
    os = [System.Environment]::OSVersion.VersionString
    machine = $env:COMPUTERNAME
    processorCount = [System.Environment]::ProcessorCount
    physicalMemoryBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    galliumDriver = $env:GALLIUM_DRIVER
    game = $Game
    preflightJar = $PreflightJar
    preflightJarSha256 = Get-Sha256 $PreflightJar
    java = $java
    javaSha256 = Get-Sha256 $java
    vanillaLauncher = $vanillaLauncher
    vanillaLauncherSha256 = Get-Sha256 $vanillaLauncher
    vanillaBatchSha256 = Get-Sha256 (Join-Path $Game 'starsector-core\starsector.bat')
    vanillaVmparamsSha256 = Get-Sha256 (Join-Path $Game 'starsector-core\vmparams')
    fastRenderingLauncher = $fastRenderingLauncher
    fastRenderingLauncherSha256 = Get-Sha256 $fastRenderingLauncher
    fastRenderingVmparamsSha256 = Get-Sha256 (Join-Path $Game 'starsector-core\fr.vmparams')
    fastRenderingJarSha256 = Get-Sha256 (Join-Path $Game 'starsector-core\fr.jar')
    fastRenderingAgentSha256 = Get-Sha256 (Join-Path $Game 'starsector-core\fr.agent.jar')
    enabledModsSha256 = Get-Sha256 $enabledMods
    iterations = $Iterations
    cooldownSeconds = $CooldownSeconds
    seed = $Seed
    conditions = $Conditions
    displayBounds = [ordered]@{
        width = $displayBounds.Width
        height = $displayBounds.Height
    }
    displayWorkingArea = [ordered]@{
        width = $workingArea.Width
        height = $workingArea.Height
    }
    resolutionSource = if ($PSBoundParameters.ContainsKey('Resolution')) { 'explicit' } else { 'primary-display-working-area' }
    directLaunchOptions = "-DlaunchDirect=true -DstartRes=$Resolution -DstartFS=false -DstartSound=true"
    preparationPerformed = $preparationPerformed
    preparationExitCode = $prepareExitCode
}
$identity | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $sessionDirectory 'identity.json') -Encoding UTF8

$random = [System.Random]::new($Seed)
$schedule = [System.Collections.Generic.List[object]]::new()
for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    $leg = @($Conditions | Sort-Object { $random.Next() })
    foreach ($condition in $leg) {
        $schedule.Add([pscustomobject]@{ condition = $condition; iteration = $iteration })
    }
}
$schedule | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $sessionDirectory 'schedule.json') -Encoding UTF8

$results = [System.Collections.Generic.List[object]]::new()
$runNumber = 0
foreach ($entry in $schedule) {
    $runNumber++
    $runDirectory = Join-Path $sessionDirectory ('{0:D2}-{1}-r{2}' -f $runNumber, $entry.condition, $entry.iteration)
    Write-Host ("[{0}/{1}] {2} iteration {3}" -f $runNumber, $schedule.Count, $entry.condition, $entry.iteration)
    $result = Measure-OneRun $entry.condition $entry.iteration $runDirectory $java $vanillaLauncher $fastRenderingLauncher $gameLog $Resolution
    $results.Add($result)
    $result | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $sessionDirectory 'results.jsonl') -Encoding UTF8
    $result | Format-List condition, iteration, accepted, gameLogStartToGraphicsPreloadMs, gracefulShutdown, fastRenderingObserved, adapterHealthy, runtimeOwner
    if ($CooldownSeconds -gt 0 -and $runNumber -lt $schedule.Count) { Start-Sleep -Seconds $CooldownSeconds }
}

$conditionSummaries = [ordered]@{}
foreach ($condition in $Conditions) {
    $matching = @($results | Where-Object { $_.condition -eq $condition })
    $accepted = @($matching | Where-Object accepted)
    $seconds = @($accepted | ForEach-Object { [double]$_.gameLogStartToGraphicsPreloadMs / 1000.0 })
    $conditionSummaries[$condition] = [ordered]@{
        acceptedRuns = $accepted.Count
        totalRuns = $matching.Count
        medianSeconds = Get-Median $seconds
        minimumSeconds = if ($seconds.Count) { ($seconds | Measure-Object -Minimum).Minimum } else { $null }
        maximumSeconds = if ($seconds.Count) { ($seconds | Measure-Object -Maximum).Maximum } else { $null }
        samplesSeconds = $seconds
    }
}
$summary = [ordered]@{
    accepted = @($results | Where-Object { -not $_.accepted }).Count -eq 0
    sessionDirectory = $sessionDirectory
    identity = $identity
    conditions = $conditionSummaries
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $sessionDirectory 'summary.json') -Encoding UTF8
$summary | ConvertTo-Json -Depth 8
if (-not $summary.accepted) { exit 1 }
