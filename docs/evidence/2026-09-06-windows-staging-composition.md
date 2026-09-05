# Windows prepared staging with typed prestart

Objective: recover consistent sub-20-second ordinary Windows startup and improve the combined
prepared-audio, prestart and Kaleidoscope path. Owner: Codex. Baseline main `7827ecaf`, installed
JAR SHA-256 `9775ae177b9de16cac81f6841acba3a5ddf22c9fd901a2db85c9bbfd8ce4ff4d`.
The VM remains 20 GiB / 14 CPUs, native graphics, 1024x720. Existing stock worker count stays one.

Earlier main produced 17.620 / 18.953 s menus; later baseline reversal produced 21.988 s. The
one-read LZ4 change did not establish an end-to-end timing win. Keep timing claims separate from
operation counts, and remove candidates when the combined-path evidence does not justify them.

## Candidate

The existing 64 MiB staging experiment starts one CPU-only producer before SpecStore. Its
consumer lived only in the stock prefetch image getter. Typed prestart removes eligible stock
jobs and calls synchronous prepared loading instead, so staging flags alone do not compose.

The candidate consumes a ready staged carrier above synchronous loading at the typed admission
seam. It never waits for the producer; all existing identity, scope and main-thread commit gates
remain. Unknown, transformed, existing-handler and unsupported cases keep existing behavior.
Direct buffers and GL are still created/used only during the main-thread commit.

Staging cancellation now uses the existing cancellation flag and queue notification without
interrupting a borrowed FileChannel read. Producer ownership is checked before publication and
telemetry updates so a retired producer cannot publish into a replacement session. Tests cover
the real staged-to-prestart handoff without rereading its blob, cancellation preserving a borrowed
channel, and stale-session publication. The experiment remains off by default until measured.

Current phase: full verification, then identified Windows candidate installation and comparison.
Finish condition: retain only a justified change, integrate main, restore the ordinary launch task,
and retire disposable builds while retaining evidence.
