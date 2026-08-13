# File-only logging saves 0.249 seconds safely; buffering saves 0.403 seconds

**Branch:** `codex/quiet-logs`, stacked on merged-read PR #314

**Result:** removing the duplicate console appender while leaving the rolling file synchronous fell
from 0.491s to 0.242s (**-0.249s**) without creating a crash-tail risk. This file-only mode is now
part of `--fast`. The explicit `--quiet-logs` upgrade also buffers the file and reaches 0.088s
(**-0.403s**) while retaining the complete rolling `starsector.log` on an orderly exit.

## What ships

`--file-only-logs` writes `log4j-file-only.properties` into the run directory and passes its
percent-encoded file URI through `JAVA_TOOL_OPTIONS`. The override keeps the installed
configuration's INFO level, pattern, 50,000KB rotation threshold, three backups, synchronous
immediate writes, and `starsector.log` destination. It removes only the console appender, which
duplicates every line already sent to the file. `--fast` includes this crash-safe mode.

`--quiet-logs` writes `log4j-quiet.properties`, makes the same console removal, and additionally
enables a 65,536-byte file buffer. It is therefore an explicit upgrade from file-only mode rather
than a separate logging path.

The flag also sends `quietLogs=on` to the existing Preflight agent shutdown hook. After recording and
adapter finalization, that hook reflectively invokes the installed log4j 1.2 `LogManager.shutdown()`.
This keeps log4j out of Preflight's compile/runtime dependencies and guarantees a normal JVM exit
flushes the final buffer.

Only the buffered upgrade is deliberately not implied by `--fast`. `SIGTERM` and ordinary exits
flush it; a JVM crash, `SIGKILL`, or power loss can still lose the final partial buffer. The default
fast path keeps unbuffered file writes, so choosing ordinary startup speed does not trade away the
diagnostic tail.

## Replay measurement

The replay emits the current launch's five main-thread message populations beside the ScriptStore
and sound background populations, using Starsector's own JVM and `log4j-1.2.9.jar`:

| configuration | loading thread | versus shipped |
| --- | ---: | ---: |
| shipped console + unbuffered file | 0.491s | — |
| **no console, unbuffered file (`--fast`)** | **0.242s** | **-0.249s** |
| **no console, buffered file** | **0.088s** | **-0.403s** |
| console + buffered file | 0.259s | -0.232s |
| level raised, nothing emitted | 0.007s | -0.485s |

This reproduces the prior 0.488s / 0.085s measurement within 3ms. Raising the level would buy only
another 81ms and would discard diagnostics, so it remains rejected.

Source: `docs/evidence/2026-08-03-logging-two-thread-benchmark.java.txt`.

## Installed-log4j fidelity

`docs/evidence/2026-08-04-quiet-log-fidelity.java` uses the production configuration writer, omits
any manual log4j shutdown, and relies solely on the production javaagent shutdown path. Through the
installed JVM and jar it produced:

```text
stdout_bytes=0
log_lines=10001
final_line=fidelity-final-line
```

The 79 stderr bytes were Preflight's own statement that the shutdown flush was active. No log4j line
reached console, all 10,001 reached the file, and the final line proves the shutdown hook flushed the
tail.

## Real-game smoke

Run `file-only-fast-gate-20260805-163904` exercised the new crash-safe default on the current
83-mod profile. The receipt recorded `fileOnlyLogs=true`, `quietLogs=false`, and the generated
configuration contained neither `BufferedIO` nor `BufferSize`. The ordinary main-menu marker
arrived at **25.32s**; all 38 exact transformations applied with zero decline, source-binding
rejection, contained failure, or kill switch. The wrapper exited cleanly after its owned SIGTERM,
the final `starsector.log` byte was a newline, lifecycle inspection found no fatal evidence, and no
game JVM survived. The launch's remaining direct/JVM console output was 194KB; log4j's 6.3MB rolling
file remained complete and synchronous.

That launch is a compatibility gate, not a 0.249s timing proof. The effect remains below whole-launch
noise and is priced by the installed-log4j replay above.

Run `quiet-logs-final-smoke-20260804-002142` used `--direct --fast --quiet-logs` on the 83-mod
profile. The startup probe reached `resource-init-complete`; after a five-second UI grace period the
helper sent `SIGTERM`, the shutdown hook flushed log4j, and the flushed delta contained the ordinary
GraphicsLib main-menu marker:

```text
gameStartLogMillis=408
mainMenuReadyLogMillis=32687
gameLogMillisDelta=32279
outcome=COMPLETED  exitCode=0  launcherExitCode=0
```

The final byte of `starsector.log` was decimal 10, proving the real-game tail ended on a complete
newline. No Starsector JVM survived cleanup.

This 32.279s run is a compatibility smoke, not the performance claim. A 0.403s effect is below the
profile's ±1.4s launch noise; replay is the instrument that can price it.

The first smoke exposed why the helper also needed a quiet-log path: the main-menu marker may remain
inside the very buffer being tested, so waiting for that marker before sending the shutdown signal
deadlocks the test. `probe-launch.sh` now waits for the startup probe's transactional
`resource-init-complete`, allows five seconds for UI completion, shuts down, then verifies the
ordinary marker in the flushed log without treating its post-shutdown observation timestamp as a
benchmark.
