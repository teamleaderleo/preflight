# macOS system-RAM warning uses literal free pages

## Finding

Vanilla Starsector's `com/fs/starfarer/campaign/C.o00000(CampaignState, boolean)` reads
`OperatingSystemMXBean.getFreePhysicalMemorySize()`, converts it to MiB, and emits
`Warning: Low system RAM remaining` whenever the result is below 1,000 MiB.

That measurement is not the macOS memory-pressure model. It excludes reclaimable inactive,
speculative, and file-cache pages, so a healthy machine can cross the literal-free threshold while
macOS still has ample immediately available memory. On the development Mac, Java reported roughly
4.4 GiB literally free while `/usr/bin/memory_pressure` reported 84% system-wide memory available;
`vm_stat` simultaneously showed substantial inactive and speculative memory.

## Exact correction

`MacMemoryWarningPlan` supports only the shipped Starsector class and archive:

- `starfarer_obf.jar` SHA-256
  `a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149`;
- `com/fs/starfarer/campaign/C.class` SHA-256
  `3fbb94794496debd8401c8b2643d3cd58d0834d7d26694cad998cc08a65c3c99`;
- Java 17, the exact method descriptor, and the exact final free-memory comparison and warning
  branch;
- the normal source-archive and application-class-loader gates in `AdapterTargetRegistry`.

The adapter changes only the final `< 1000 MiB` decision. The original warning text and
`CampaignState.addMessage` call remain vanilla. During agent startup, before Starsector creates its
audio system, `MacMemoryWarningRuntime` runs `/usr/bin/memory_pressure -Q` once with a C locale and
a two-second hard timeout. Later warning checks are pure in-memory reads of that session snapshot;
they launch no process. If literal free memory is already at least 1,000 MiB, the vanilla fast path
still returns without consulting the snapshot. Otherwise, the adapter applies the same 1,000 MiB
threshold to the captured available percentage multiplied by total physical memory.

Real pressure still warns. A missing tool, timeout, nonzero exit, malformed output, interruption, or
any other ordinary probe failure also preserves the vanilla warning. Non-macOS systems preserve the
vanilla decision without probing. Fatal VM errors are not swallowed.

## Verification

Focused tests cover a healthy fast path, an 84% macOS correction, a 2% real-pressure warning,
single-capture reuse across repeated checks, non-macOS behavior, probe failure, parser bounds,
wrong hashes and shapes, a second-transform
decline, and the exact installed class from `starfarer_obf.jar`. Full `mvn verify` passed afterward:
zero failures across every reactor module.

The live pilot `mac-memory-pressure-v1-20260805-001605` exited normally with adapter health `ACTIVE`,
21 exact transformations applied, zero declines, and zero contained failures. The RAM-warning plan
installed, but its `checks` counter remained zero: vanilla did not invoke that event-driven method
during this particular session. This is therefore live compatibility evidence, not a claim that a
false warning was corrected in that run. The actual branch behavior is established by the runtime
tests and the exact installed-bytecode integration test above; a future naturally occurring warning
will provide the remaining live counter evidence.

## Transition-audio regression and correction

A later pilot exposed a sharp CoreAudio pop while loading a campaign and leaving a combat
simulation. A controlled bisection established the following:

- fully vanilla (`vanilla-audio-control-20260805-045104`) produced only Starsector's ordinary soft
  transition noise;
- omitting only the streaming-audio transform still popped;
- disabling profiling and frame probes still popped;
- disabling all runtime plans while retaining startup caches produced clean audio;
- progressively reducing that set to only `macos-pressure-aware-system-ram-warning-v1` produced
  clean audio with every other optimization active (`audio-bisect-mac-memory-off-20260805-050721`).

The prior runtime launched `memory_pressure` synchronously on each low-literal-free warning check.
Starsector's bundled JRE has a non-executable `jspawnhelper`, so its supported fallback is `FORK`;
forking the multi-gigabyte x86-64 JVM under Rosetta at an audio transition caused the audible stall.
Capturing once before audio initialization retains the pressure-aware correction without any
transition-time process creation.

The all-optimizations confirmation `mac-pressure-preaudio-confirm-20260805-051217` then completed
with clean campaign-load and simulation-exit audio. Adapter health was `ACTIVE`: 29 transformations
applied, zero declines, and zero contained failures. The pressure telemetry recorded exactly one
session probe, four later in-memory warning checks/corrections, 67% available memory, and zero probe
failures. This closes both the behavioral regression and the live instrumentation gate.
