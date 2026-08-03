# Quiet logs keeps every line and saves 0.403 seconds

**Branch:** `codex/quiet-logs`, stacked on merged-read PR #314

**Result:** the opt-in configuration removes the duplicate console appender and buffers the rolling
file appender. The current two-thread replay fell from 0.491s to 0.088s (**-0.403s**) while retaining
the complete rolling `starsector.log`.

## What ships

`--quiet-logs` writes `log4j-quiet.properties` into the run directory and passes its percent-encoded
file URI through `JAVA_TOOL_OPTIONS`. The override keeps the installed configuration's INFO level,
pattern, 50,000KB rotation threshold, three backups, and `starsector.log` destination. It changes
only two things:

- the console appender is omitted because it duplicates every line already sent to the file;
- the file appender uses a 65,536-byte buffer.

The flag also sends `quietLogs=on` to the existing Preflight agent shutdown hook. After recording and
adapter finalization, that hook reflectively invokes the installed log4j 1.2 `LogManager.shutdown()`.
This keeps log4j out of Preflight's compile/runtime dependencies and guarantees a normal JVM exit
flushes the final buffer.

The flag is deliberately not implied by `--fast`. `SIGTERM` and ordinary exits flush; a JVM crash,
`SIGKILL`, or power loss can still lose the final partial buffer. A user choosing startup speed can
accept that diagnostic tradeoff explicitly.

## Replay measurement

The replay emits the current launch's five main-thread message populations beside the ScriptStore
and sound background populations, using Starsector's own JVM and `log4j-1.2.9.jar`:

| configuration | loading thread | versus shipped |
| --- | ---: | ---: |
| shipped console + unbuffered file | 0.491s | — |
| no console, unbuffered file | 0.242s | -0.250s |
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
