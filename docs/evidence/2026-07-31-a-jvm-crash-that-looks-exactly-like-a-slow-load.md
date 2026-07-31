# A JVM crash that looks exactly like a slow load

Date: 2026-07-31

## What happened

A settling launch appeared to freeze with the loading bar at 100%. The game process was
alive at 6% CPU, `starsector.log` had been silent for eight minutes, and the last line was
an ordinary `Loading JSON from [data/config/hull_styles.json]`.

The game had not frozen. Its JVM had died eight minutes earlier:

```text
Internal Error at sharedRuntime.cpp:561, pid=44307, tid=146947
guarantee(cb != NULL && cb->is_compiled()) failed: safepoint polling: pc must refer to an nmethod

Do you want to debug the problem?
...
Otherwise, press RETURN to abort...
```

This is a HotSpot assertion in the safepoint-polling path, not a mod fault and not a
Preflight logic error.

## Why it hangs instead of exiting

Starsector's launcher passes `-XX:+ShowMessageBoxOnError`. On a fatal error HotSpot prints
its report and **blocks on stdin for a RETURN that never arrives**. The process stays
alive, the window stays up, and every signal an observer watches — process liveness, CPU
above zero, a loading bar at 100% — says "still working".

The crash text went to the wrapper's captured stdout within seconds. Nothing was reading it.

## The configuration

```text
JVM:      OpenJDK 17.0.10, Zulu17.48+15-CA
Binary:   Mach-O 64-bit x86_64, so Rosetta 2 on this Apple Silicon host
Flags:    -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions
          -XX:UseAVX=3 -XX:AVX3Threshold=0 -XX:+UseBMI1Instructions -XX:+UseBMI2Instructions
          -XX:+EnableVectorSupport -XX:+EnableVectorReboxing -XX:+EnableVectorAggressiveReboxing
          -XX:+UseFPUForSpilling -XX:-AlignVector -noverify
          -XX:CompilerDirectivesFile=../../MacOS/compiler_directives.txt
Agent:    Preflight, with jdk.ExecutionSample every 10ms and stack-traced class loads
```

This assertion is documented as arising under exactly two conditions, and **both are
present here**: dynamic binary translation
([DynamoRIO #3892](https://github.com/DynamoRIO/dynamorio/issues/3892), the same class of
tool as Rosetta 2) and an attached profiler
([corretto-21 #53](https://github.com/corretto/corretto-21/issues/53)). Execution sampling
walks thread stacks at arbitrary points, which is the machinery that asserted.

Preflight's recorder is therefore a **likely contributor and not a proven cause**. The
failure is intermittent: five recorded `enabled` runs earlier the same day completed
normally. One crash cannot separate "sampling caused this" from "sampling made an existing
fragility more likely to fire".

## What this changes

**`--no-record` is the mitigation that already exists.** It removes execution sampling
entirely, so the `fast` benchmark condition and any ordinary launch made that way avoid the
mechanism most directly implicated. This is a second, independent reason to prefer it for
launching, alongside the 24% it saves.

**The harness now detects it.** A watchdog scans the wrapper output for HotSpot's fatal
banner, the `Internal Error at <file>.cpp:<line>` form, and the message-box prompt, then
kills the process tree so the run fails through the ordinary path in seconds instead of
consuming a 600-second timeout. The run is excluded as `jvm-crash` rather than
`main-menu-not-detected`, because those are different problems and only one of them is
about the game being slow.

The patterns have to be specific. The game's log is interleaved into the same stream and
contains arbitrary mod text — a mod named *Guarantee Rare Items* matches a naive search for
HotSpot's `guarantee()` failures, and `ERROR` appears in routine GraphicsLib output.

**Termination now covers the process tree.** The game is a grandchild: the harness starts
the Preflight wrapper, the wrapper starts `starsector_mac.sh`, and that shell starts the
JVM. Signalling only the wrapper left the crashed JVM running after its own timeout, still
holding the screen and the next run's page cache.

## Upstream

Nothing here is worth filing against OpenJDK. The configuration combines an emulated x86
JVM, experimental vectorization flags, a custom compiler-directives file, disabled bytecode
verification, and an attached profiler; the assertion is already known to appear under
binary translation and under profilers. A report would be closed as unsupported, correctly.

The reportable item belongs to Fractal Softworks, and it is not the crash:

> `-XX:+ShowMessageBoxOnError` converts every fatal JVM error into an indefinite hang with
> no diagnostic. A player sees a loading bar stopped at 100% and has no way to learn that
> the JVM died, because the report is written to a console they never see. Removing the
> flag, or pairing it with `-XX:ErrorFile=`, would turn a silent freeze into a crash log.

That likely explains an unknown share of community "stuck at 100%" reports, which are
ordinarily attributed to mods.
