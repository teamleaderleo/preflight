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
    [AllowEmptyString()]
    [string]$GalliumDriver = 'llvmpipe',
    [ValidateSet('recommended', 'conservative')]
    [string]$OptimizationPreset = 'recommended',
    [switch]$StartupPhaseProbe,
    [switch]$StartupTextureCpuProbe,
    [switch]$TextureUploadProbe,
    [switch]$TextureUploadCheckpoint,
    [switch]$WindowsPrefetchBypassProbe,
    [switch]$WindowsPreparedResources,
    [switch]$WindowsDisablePreparedResources,
    [switch]$WindowsPreparedByteBarrier,
    [switch]$WindowsDisablePreparedByteBarrier,
    [switch]$WindowsPreparedResourceClaims,
    [switch]$WindowsDisablePreparedResourceClaims,
    [switch]$WindowsPreparedPrefetchProbe,
    [switch]$WindowsPreparedStagingProbe,
    [switch]$WindowsKaleidoscopePrefetchProbe,
    [switch]$WindowsPreparedPriorityOrderProbe,
    [switch]$WindowsPreparedColdProbe,
    [switch]$WindowsPreparedSplitQueueProbe,
    [switch]$WindowsSharedContextTextureProbe,
    [switch]$WindowsDisplayThreadTextureProbe,
    [switch]$WindowsDisplayThreadSpecStoreProbe,
    [switch]$WindowsSpecStoreTextureOverlap,
    [switch]$WindowsBackslashMergedReadKeys,
    [switch]$WindowsDisableBackslashMergedReadKeys,
    [switch]$WindowsFactionPriorityCacheProbe,
    [ValidateRange(1, 8)]
    [int]$WindowsPreparedPrefetchWorkers = 1,
    [ValidateRange(0, 8192)]
    [int]$WindowsUnpaddedMaxDimension = 0,
    [ValidateSet('starsector', 'preflight', 'preflight-prepared-resources', 'preflight-faction-priority', 'preflight-kaleidoscope', 'preflight-spec-store-texture-overlap', 'fast-rendering', 'preflight-fast-rendering', 'preflight-fast-rendering-prepared')]
    [string[]]$Conditions = @('starsector', 'preflight', 'fast-rendering', 'preflight-fast-rendering')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$preparedResourcesRequested = $WindowsPreparedResources -or $WindowsPreparedResourceClaims -or $WindowsPreparedByteBarrier -or
    ($Conditions -contains 'preflight-prepared-resources')
if ($WindowsPreparedByteBarrier -and $WindowsDisablePreparedByteBarrier) {
    throw 'Prepared byte barrier enable and disable requests cannot be combined'
}
if ($WindowsPreparedResourceClaims -and $WindowsDisablePreparedResourceClaims) {
    throw 'Prepared resources claim enable and disable requests cannot be combined'
}
if ($preparedResourcesRequested -and $WindowsDisablePreparedResources) {
    throw 'Prepared resources enable and disable requests cannot be combined'
}
if ($preparedResourcesRequested -and (@($Conditions | Where-Object { $_ -match 'fast-rendering' }).Count -gt 0)) {
    throw 'Prepared resources cannot be combined with Fast Rendering conditions; select -Conditions preflight or preflight-prepared-resources'
}
if ($preparedResourcesRequested -and (
    $WindowsPreparedPrefetchWorkers -ne 1 -or $WindowsPreparedSplitQueueProbe -or
    $WindowsPrefetchBypassProbe -or $WindowsSharedContextTextureProbe -or
    $WindowsDisplayThreadTextureProbe -or $WindowsDisplayThreadSpecStoreProbe -or
    $WindowsSpecStoreTextureOverlap -or
    $Conditions -contains 'preflight-spec-store-texture-overlap')) {
    throw 'Prepared resources requires workers=1 and no split queue, prefetch bypass, shared context, Display-thread or overlap options'
}
if ([string]::IsNullOrWhiteSpace($GalliumDriver) -or $GalliumDriver -eq 'native') {
    Remove-Item Env:GALLIUM_DRIVER -ErrorAction SilentlyContinue
} else {
    $env:GALLIUM_DRIVER = $GalliumDriver
}
$effectivePreparedPrefetchProbe = $WindowsPreparedPrefetchProbe -or $WindowsPreparedSplitQueueProbe
$effectivePreparedPrefetchWorkers = if ($WindowsPreparedSplitQueueProbe) {
    2
} else {
    $WindowsPreparedPrefetchWorkers
}
if ($StartupTextureCpuProbe -and -not $StartupPhaseProbe) {
    throw '-StartupTextureCpuProbe requires -StartupPhaseProbe'
}

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
            $_.CommandLine -match '(?:^|[\\/;])fr\.(?:jar|agent\.jar)(?:[;" ]|$)' -or
            $_.CommandLine -match '(?:^|[\s"])[@]?(?:[^\s"]*[\\/])?fr\.vmparams(?:[\s"]|$)'
    })
}

function Stop-GameProcesses([string]$GamePath, [string]$RunDirectory = '') {
    $shutdownPath = if ($RunDirectory) { Join-Path $RunDirectory 'shutdown.json' } else { $null }
    $report = [ordered]@{
        format = 'starsector-preflight-shutdown-v1'
        startedAt = (Get-Date).ToString('o')
        phase = 'closing'
        waitSeconds = 45
        initialCount = 0
        closeRequests = @()
        remainingCount = $null
        remaining = @()
        forcedAt = $null
        finishedAt = $null
        gracefulShutdown = $null
        sampleLimit = 32
    }
    function Write-ShutdownReport {
        if ($shutdownPath) {
            try {
                $report | ConvertTo-Json -Depth 5 |
                    Set-Content -LiteralPath $shutdownPath -Encoding UTF8 -ErrorAction Stop
            } catch {
                Write-Warning 'Unable to write shutdown.json; continuing process cleanup'
            }
        }
    }
    try {
        $processes = @(Get-GameProcesses $GamePath)
        $report.initialCount = $processes.Count
        Write-ShutdownReport
        foreach ($candidate in $processes) {
            $windowProcess = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
            $request = [ordered]@{
                pid = $candidate.ProcessId
                parentPid = $candidate.ParentProcessId
                processStartedAt = $candidate.CreationDate
                name = $candidate.Name
                observedAt = (Get-Date).ToString('o')
                windowHandle = $null
                windowTitle = $null
                closeRequestedAt = $null
                closeMessageSent = $null
            }
            if ($windowProcess) {
                $request.windowHandle = [string]$windowProcess.MainWindowHandle
                $title = [string]$windowProcess.MainWindowTitle
                $request.windowTitle = $title.Substring(0, [Math]::Min(256, $title.Length))
            }
            if ($report.closeRequests.Count -lt $report.sampleLimit) {
                $report.closeRequests += $request
            }
            if ($windowProcess -and $windowProcess.MainWindowHandle -ne 0) {
                $request.closeRequestedAt = (Get-Date).ToString('o')
                $request.closeMessageSent = $windowProcess.CloseMainWindow()
            }
        }
        $report.phase = 'waiting'
        Write-ShutdownReport
        $deadline = (Get-Date).AddSeconds(45)
        while ((Get-Date) -lt $deadline -and @(Get-GameProcesses $GamePath).Count -gt 0) {
            Start-Sleep -Seconds 1
        }
        $remaining = @(Get-GameProcesses $GamePath)
        $graceful = $remaining.Count -eq 0
        $report.gracefulShutdown = $graceful
        $report.remainingCount = $remaining.Count
        $report.remaining = @($remaining | Select-Object -First 32 -Property `
            ProcessId, ParentProcessId, Name, CreationDate)
        if (-not $graceful) {
            $report.phase = 'forcing'
            $report.forcedAt = (Get-Date).ToString('o')
            Write-ShutdownReport
        }
        foreach ($candidate in $remaining) {
            Stop-Process -Id $candidate.ProcessId -Force -ErrorAction SilentlyContinue
        }
        $report.phase = 'complete'
        return $graceful
    } finally {
        $report.finishedAt = (Get-Date).ToString('o')
        Write-ShutdownReport
    }
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

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Get-IsoElapsedMillis([object]$Start, [object]$End) {
    if (-not $Start -or -not $End) { return $null }
    try {
        $culture = [System.Globalization.CultureInfo]::InvariantCulture
        $started = [DateTimeOffset]::Parse([string]$Start, $culture)
        $finished = [DateTimeOffset]::Parse([string]$End, $culture)
        return [long][Math]::Round(($finished - $started).TotalMilliseconds)
    } catch {
        return $null
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
    if (@(Get-GameProcesses $Game).Count -gt 0) {
        throw "A Starsector process is already running under $Game"
    }

    $usesPreflight = $Condition -in @(
        'preflight',
        'preflight-prepared-resources',
        'preflight-faction-priority',
        'preflight-kaleidoscope',
        'preflight-spec-store-texture-overlap',
        'preflight-fast-rendering',
        'preflight-fast-rendering-prepared'
    )
    $usesFastRendering = $Condition -in @(
        'fast-rendering',
        'preflight-fast-rendering',
        'preflight-fast-rendering-prepared'
    )
    # Null means the runner leaves the opt-in property to its default; false is explicit.
    $requestedPreparedResources = if (-not $usesPreflight) {
        $null
    } elseif ($WindowsPreparedResources -or $WindowsPreparedResourceClaims -or $WindowsPreparedByteBarrier -or ($Condition -eq 'preflight-prepared-resources')) {
        $true
    } elseif ($WindowsDisablePreparedResources -or ($Conditions -contains 'preflight-prepared-resources')) {
        $false
    } else {
        $null
    }
    $requestedPreparedResourceClaims = if (-not $usesPreflight) {
        $null
    } elseif ($WindowsPreparedResourceClaims) {
        $true
    } elseif ($WindowsDisablePreparedResourceClaims) {
        $false
    } else {
        $null
    }
    $requestedPreparedByteBarrier = if (-not $usesPreflight) {
        $null
    } elseif ($WindowsPreparedByteBarrier) {
        $true
    } elseif ($WindowsDisablePreparedByteBarrier) {
        $false
    } else {
        $null
    }
    $usesKaleidoscopePrefetch = $WindowsKaleidoscopePrefetchProbe -or
        $Condition -eq 'preflight-kaleidoscope'
    $usesFactionPriorityCache = $WindowsFactionPriorityCacheProbe -or
        $Condition -eq 'preflight-faction-priority'
    $usesSpecStoreTextureOverlap = $WindowsSpecStoreTextureOverlap -or
        $Condition -eq 'preflight-spec-store-texture-overlap'
    $launcher = if ($usesFastRendering) { $FastRenderingLauncher } else { $VanillaLauncher }
    $startedAt = Get-Date
    $directLaunchOptions = "-DlaunchDirect=true -DstartRes=$DirectResolution -DstartFS=false -DstartSound=true"
    $savedPrivateJavaOptions = $env:_JAVA_OPTIONS
    $savedJavaToolOptions = $env:JAVA_TOOL_OPTIONS
    $logConfiguration = Join-Path $RunDirectory 'log4j-file-only.properties'
    @'
log4j.rootLogger=INFO, file
log4j.appender.file=org.apache.log4j.RollingFileAppender
log4j.appender.file.File=${com.fs.starfarer.settings.paths.logs}/starsector.log
log4j.appender.file.layout=org.apache.log4j.PatternLayout
log4j.appender.file.layout.ConversionPattern=%-4r [%t] %-5p %c %x - %m%n
log4j.appender.file.MaxFileSize=50000KB
log4j.appender.file.MaxBackupIndex=3
'@ | Set-Content -LiteralPath $logConfiguration -Encoding ASCII
    $logConfigurationUri = 'file:///' + ($logConfiguration -replace '\\', '/')
    $quietLogOptions = "-Dlog4j.configuration=$logConfigurationUri -Dpreflight.assetProgressLogs=off"
    $env:_JAVA_OPTIONS = (($savedPrivateJavaOptions, $directLaunchOptions | Where-Object { $_ }) -join ' ').Trim()
    $env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
    if (-not $usesPreflight) {
        $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, $quietLogOptions |
            Where-Object { $_ }) -join ' ').Trim()
    }
    try {
        if ($usesPreflight) {
            if ($null -ne $requestedPreparedResources) {
                $preparedResourcesValue = ([bool]$requestedPreparedResources).ToString().ToLowerInvariant()
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    "-Dpreflight.texture.windowsPreparedResources=$preparedResourcesValue" |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($null -ne $requestedPreparedResourceClaims) {
                $claimValue = ([bool]$requestedPreparedResourceClaims).ToString().ToLowerInvariant()
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    "-Dpreflight.texture.windowsPreparedResourceClaims=$claimValue" |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($null -ne $requestedPreparedByteBarrier) {
                $barrierValue = ([bool]$requestedPreparedByteBarrier).ToString().ToLowerInvariant()
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    "-Dpreflight.texture.windowsPreparedByteBarrier=$barrierValue" |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($Condition -eq 'preflight-fast-rendering-prepared') {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.fastRenderingPrepared=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            } elseif ($Conditions -contains 'preflight-fast-rendering-prepared') {
                # Preserve a true baseline if this candidate later graduates into a preset.
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.fastRenderingPrepared=false' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($TextureUploadCheckpoint) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.uploadCheckpoint=true' | Where-Object { $_ }) -join ' ').Trim()
            }
            if ($TextureUploadProbe -or $TextureUploadCheckpoint) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.uploadProbe=true' | Where-Object { $_ }) -join ' ').Trim()
            }
            if ($StartupTextureCpuProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.textureThreadCpu=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsPrefetchBypassProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsPrefetchBypassProbe=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($effectivePreparedPrefetchProbe) {
                $preparedPrefetchOptions = '-Dpreflight.texture.windowsPreparedPrefetchProbe=true ' +
                    "-Dpreflight.texture.windowsPreparedPrefetchWorkers=$effectivePreparedPrefetchWorkers"
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, $preparedPrefetchOptions |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsPreparedSplitQueueProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsPreparedSplitQueues=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsSharedContextTextureProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.sharedContextTextureProbe=on' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsDisplayThreadTextureProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.displayThreadTextureProbe=on' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsDisplayThreadSpecStoreProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.displayThreadSpecStoreProbe=on' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($usesSpecStoreTextureOverlap) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.windowsSpecStoreTextureOverlap=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            } elseif ($Conditions -contains 'preflight-spec-store-texture-overlap') {
                # Preserve a real baseline arm if the candidate later becomes a preset default.
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.windowsSpecStoreTextureOverlap=false' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsBackslashMergedReadKeys) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.mergedReads.windowsBackslashKeys=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsDisableBackslashMergedReadKeys) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.mergedReads.windowsBackslashKeys=false' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($usesFactionPriorityCache) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.windowsFactionPriorityCache=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            } elseif ($Conditions -contains 'preflight-faction-priority') {
                # Preserve a true baseline if this candidate later graduates into Recommended.
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.startup.windowsFactionPriorityCache=false' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsPreparedStagingProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.preparedStaging=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsPreparedPriorityOrderProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsPreparedPriorityOrder=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsPreparedColdProbe) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsPreparedColdProbe=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsPreparedSplitQueueProbe) {
                # The split-queue transform and the learned late-image rewrite are intentionally
                # non-composable. Preserve a real split-queue candidate instead of letting the
                # Recommended Windows policy re-enable the neighboring default.
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsKaleidoscopePrefetch=false' |
                    Where-Object { $_ }) -join ' ').Trim()
            } elseif ($usesKaleidoscopePrefetch) {
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsKaleidoscopePrefetch=true' |
                    Where-Object { $_ }) -join ' ').Trim()
            } elseif ($Conditions -contains 'preflight-kaleidoscope') {
                # Preserve a real A leg after the candidate graduates into Recommended.
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS,
                    '-Dpreflight.texture.windowsKaleidoscopePrefetch=false' |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            if ($WindowsUnpaddedMaxDimension -gt 0) {
                $unpaddedOptions = '-Dpreflight.padding.unpadded=true ' +
                    "-Dpreflight.padding.maxUnpaddedDimension=$WindowsUnpaddedMaxDimension"
                $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, $unpaddedOptions |
                    Where-Object { $_ }) -join ' ').Trim()
            }
            $arguments = @(
                '-jar', $PreflightJar,
                'run', '--game', $Game,
                '--launcher', $launcher,
                '--trace-dir', $RunDirectory,
                '--texture-cache-dir', $Cache,
                '--no-scan', '--optimization-preset', $OptimizationPreset, '--no-record'
            )
            if ($StartupPhaseProbe) {
                $arguments += '--startup-phase-probe'
            }
            $process = Start-Process -FilePath $Java -ArgumentList (Quote-Arguments $arguments) `
                -WorkingDirectory $Game -PassThru `
                -RedirectStandardOutput (Join-Path $RunDirectory 'stdout.log') `
                -RedirectStandardError (Join-Path $RunDirectory 'stderr.log')
        } else {
            $commandLine = "/d /s /c call `"$launcher`""
            $process = Start-Process -FilePath 'cmd.exe' -ArgumentList $commandLine `
                -WorkingDirectory (Split-Path -Parent $launcher) -PassThru `
                -RedirectStandardOutput (Join-Path $RunDirectory 'stdout.log') `
                -RedirectStandardError (Join-Path $RunDirectory 'stderr.log')
        }
    } finally {
        $env:_JAVA_OPTIONS = $savedPrivateJavaOptions
        $env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
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
        $running = @(Get-GameProcesses $Game)
        $gameProcesses = @($running | Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe', 'starsector.exe')
        })
        $gameJvmObserved = $gameJvmObserved -or ($gameProcesses.Count -gt 0)
        if ($usesFastRendering -and -not $fastRenderingObserved) {
            $fastRenderingObserved = @($running | Where-Object {
                $_.CommandLine -match 'fr\.(?:bat|jar|vmparams)'
            }).Count -gt 0
        }
        $process.Refresh()
        $launcherFailed = $process.HasExited -and -not $gameJvmObserved -and
            ((Get-Date) - $startedAt).TotalSeconds -ge 30
        $gameExitedBeforeGraphics = $gameJvmObserved -and $gameProcesses.Count -eq 0
    } while (-not $graphicsPreloadObserved -and -not $launcherFailed -and
        -not $gameExitedBeforeGraphics -and (Get-Date) -lt $deadline)

    $startMatch = [regex]::Match(
        $log,
        '(?m)^\s*(\d+)\s+\[.*(?:Running with the following mods \(in order of priority\):|Running vanilla game with no mods\.)'
    )
    $readyMatch = [regex]::Match($log, '(?m)^\s*(\d+)\s+\[.*VRAM after unload/preload:')
    $elapsedMs = if ($startMatch.Success -and $readyMatch.Success) {
        [long]$readyMatch.Groups[1].Value - [long]$startMatch.Groups[1].Value
    } else { $null }

    $adapterPath = Join-Path $RunDirectory 'adapter.json'
    $runtimeStatePath = Join-Path $RunDirectory 'runtime-state.json'
    $runtimeState = $null
    $mainMenuInteractiveObserved = $false
    if ($usesPreflight -and $graphicsPreloadObserved) {
        # The graphics-preload marker is intentionally retained as the historical clock, but Fast
        # Rendering can emit it while worker texture loads are still active. Wait for the exact
        # transformed title boundary as well so the report cannot mistake an early renderer marker
        # for time-to-play.
        $interactiveDeadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $interactiveDeadline -and @(Get-GameProcesses $Game).Count -gt 0) {
            $runtimeState = Read-JsonFile $runtimeStatePath
            $mainMenuInteractiveObserved = $runtimeState -and $runtimeState.mainMenuInteractiveAt
            $adapterWritten = Test-Path -LiteralPath $adapterPath -PathType Leaf
            if ($mainMenuInteractiveObserved -and $adapterWritten) { break }
            Start-Sleep -Seconds 2
        }
        $runtimeState = Read-JsonFile $runtimeStatePath
        $mainMenuInteractiveObserved = [bool]($runtimeState -and $runtimeState.mainMenuInteractiveAt)
    }
    $gracefulShutdown = Stop-GameProcesses $Game $RunDirectory
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
    $mainMenuReadyElapsedMs = Get-IsoElapsedMillis `
        $runtimeState.processStartedAt $runtimeState.mainMenuReadyAt
    $mainMenuInteractiveElapsedMs = Get-IsoElapsedMillis `
        $runtimeState.processStartedAt $runtimeState.mainMenuInteractiveAt
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
    if ($usesPreflight) {
        $accepted = $accepted -and $adapterHealthy -and $mainMenuInteractiveObserved
    }
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
        mainMenuReadyObserved = [bool]($runtimeState -and $runtimeState.mainMenuReadyAt)
        processStartToMainMenuReadyMs = $mainMenuReadyElapsedMs
        mainMenuInteractiveObserved = [bool]$mainMenuInteractiveObserved
        processStartToMainMenuInteractiveMs = $mainMenuInteractiveElapsedMs
        usesPreflight = [bool]$usesPreflight
        usesFastRendering = [bool]$usesFastRendering
        windowsPreparedResourcesRequested = $requestedPreparedResources
        windowsPreparedByteBarrierRequested = $requestedPreparedByteBarrier
        windowsPreparedResourceClaimsRequested = $requestedPreparedResourceClaims
        windowsKaleidoscopePrefetchEnabled = [bool]$usesKaleidoscopePrefetch
        windowsFactionPriorityCacheEnabled = [bool]$usesFactionPriorityCache
        windowsSpecStoreTextureOverlapEnabled = [bool]$usesSpecStoreTextureOverlap
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
        '--texture-cache-dir', $Cache, '--no-scan',
        '--optimization-preset', $OptimizationPreset, '--dry-run'
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
$powerScheme = (& powercfg.exe /getactivescheme | Out-String)
$powerSchemeGuid = if ($powerScheme -match '(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}') {
    $Matches[0].ToLowerInvariant()
} else { $null }
$defenderPreference = try { Get-MpPreference -ErrorAction Stop } catch { $null }
$defenderExclusions = if ($defenderPreference) { @($defenderPreference.ExclusionPath) } else { @() }
$gameDefenderExcluded = @($defenderExclusions | Where-Object {
    [string]::Equals($_, $Game, [StringComparison]::OrdinalIgnoreCase)
}).Count -gt 0
$cacheDefenderExcluded = @($defenderExclusions | Where-Object {
    [string]::Equals($_, $Cache, [StringComparison]::OrdinalIgnoreCase)
}).Count -gt 0
$sysMainStatus = try { [string](Get-Service -Name 'SysMain' -ErrorAction Stop).Status } catch { $null }
$identity = [ordered]@{
    version = 2
    startedAt = (Get-Date).ToString('o')
    os = [System.Environment]::OSVersion.VersionString
    machine = $env:COMPUTERNAME
    processorCount = [System.Environment]::ProcessorCount
    physicalMemoryBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    activePowerSchemeGuid = $powerSchemeGuid
    sysMainStatus = $sysMainStatus
    defenderRealtimeMonitoringDisabled = if ($defenderPreference) {
        [bool]$defenderPreference.DisableRealtimeMonitoring
    } else { $null }
    gameDefenderExcluded = $gameDefenderExcluded
    cacheDefenderExcluded = $cacheDefenderExcluded
    galliumDriver = $env:GALLIUM_DRIVER
    startupPhaseProbe = [bool]$StartupPhaseProbe
    startupTextureCpuProbe = [bool]$StartupTextureCpuProbe
    fileOnlyLogging = $true
    textureUploadProbe = [bool]($TextureUploadProbe -or $TextureUploadCheckpoint)
    textureUploadCheckpoint = [bool]$TextureUploadCheckpoint
    windowsPrefetchBypassProbe = [bool]$WindowsPrefetchBypassProbe
    windowsPreparedResources = [bool]$WindowsPreparedResources
    windowsDisablePreparedResources = [bool]$WindowsDisablePreparedResources
    windowsPreparedByteBarrier = [bool]$WindowsPreparedByteBarrier
    windowsDisablePreparedByteBarrier = [bool]$WindowsDisablePreparedByteBarrier
    windowsPreparedResourceClaims = [bool]$WindowsPreparedResourceClaims
    windowsDisablePreparedResourceClaims = [bool]$WindowsDisablePreparedResourceClaims
    windowsPreparedResourcesCondition = [bool]($Conditions -contains 'preflight-prepared-resources')
    windowsPreparedPrefetchProbe = [bool]$effectivePreparedPrefetchProbe
    windowsPreparedStagingProbe = [bool]$WindowsPreparedStagingProbe
    windowsKaleidoscopePrefetchProbe = [bool]$WindowsKaleidoscopePrefetchProbe
    windowsPreparedPriorityOrderProbe = [bool]$WindowsPreparedPriorityOrderProbe
    windowsPreparedColdProbe = [bool]$WindowsPreparedColdProbe
    fastRenderingPreparedTextureCondition = [bool](
        $Conditions -contains 'preflight-fast-rendering-prepared')
    windowsKaleidoscopePrefetchCondition = [bool]($Conditions -contains 'preflight-kaleidoscope')
    windowsPreparedPrefetchWorkers = $effectivePreparedPrefetchWorkers
    windowsPreparedSplitQueueProbe = [bool]$WindowsPreparedSplitQueueProbe
    windowsSharedContextTextureProbe = [bool]$WindowsSharedContextTextureProbe
    windowsDisplayThreadTextureProbe = [bool]$WindowsDisplayThreadTextureProbe
    windowsDisplayThreadSpecStoreProbe = [bool]$WindowsDisplayThreadSpecStoreProbe
    windowsSpecStoreTextureOverlap = [bool]$WindowsSpecStoreTextureOverlap
    windowsBackslashMergedReadKeys = [bool]$WindowsBackslashMergedReadKeys
    windowsDisableBackslashMergedReadKeys = [bool]$WindowsDisableBackslashMergedReadKeys
    windowsSpecStoreTextureOverlapCondition = [bool](
        $Conditions -contains 'preflight-spec-store-texture-overlap')
    windowsFactionPriorityCacheProbe = [bool]$WindowsFactionPriorityCacheProbe
    windowsUnpaddedMaxDimension = $WindowsUnpaddedMaxDimension
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
    optimizationPreset = $OptimizationPreset
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
    $result | Format-List condition, iteration, accepted, gameLogStartToGraphicsPreloadMs, processStartToMainMenuInteractiveMs, gracefulShutdown, fastRenderingObserved, adapterHealthy, runtimeOwner
    if ($CooldownSeconds -gt 0 -and $runNumber -lt $schedule.Count) { Start-Sleep -Seconds $CooldownSeconds }
}

$conditionSummaries = [ordered]@{}
foreach ($condition in $Conditions) {
    $matching = @($results | Where-Object { $_.condition -eq $condition })
    $accepted = @($matching | Where-Object accepted)
    $seconds = @($accepted | ForEach-Object { [double]$_.gameLogStartToGraphicsPreloadMs / 1000.0 })
    $interactiveSeconds = @($accepted | Where-Object {
        $_.processStartToMainMenuInteractiveMs -ne $null
    } | ForEach-Object { [double]$_.processStartToMainMenuInteractiveMs / 1000.0 })
    $conditionSummaries[$condition] = [ordered]@{
        acceptedRuns = $accepted.Count
        totalRuns = $matching.Count
        medianSeconds = Get-Median $seconds
        minimumSeconds = if ($seconds.Count) { ($seconds | Measure-Object -Minimum).Minimum } else { $null }
        maximumSeconds = if ($seconds.Count) { ($seconds | Measure-Object -Maximum).Maximum } else { $null }
        samplesSeconds = $seconds
        mainMenuInteractive = [ordered]@{
            observedRuns = $interactiveSeconds.Count
            medianSeconds = Get-Median $interactiveSeconds
            samplesSeconds = $interactiveSeconds
        }
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
