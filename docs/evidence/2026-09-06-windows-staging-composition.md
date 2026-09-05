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

## Initial observations

Candidate source `d5ff8bc24b598a14ffc23bbeba8541e645158c48`, JAR SHA-256
`27916fc8547db99e92612ee76eb501e4948c1c1238d1edccf261fe64f2fb701e`.
Full Java verification passed in 47.531 s with the installed common-JAR checks enabled. Startup
operator tests pass (122 tests, five environment skips); focused Java composition/lifecycle tests
also pass (two installed multi-JAR tests require fixtures not supplied to this focused run).

Session `20260906-011010`, Recommended plus only staging, native graphics, 1024x720:
graphics 21.373 / 18.096 s, interactive menus 23.178 / 20.447 s. This does not establish an
end-to-end win. Staged hits are 14,988 / 14,986; main claim-read time is 150 / 142 ms compared
with roughly 3–4 s without staging. Queued bytes stay within 64 MiB, peak loading is 16 MiB,
and both producers finish with zero queued or in-progress images and zero staging failures.
Both runs commit 15,002 resources, serve 2,049 prepared audio effects and consume all 102 late
Kaleidoscope images, with zero pack failures/fallbacks. There are 10 / 5 extra pack hits from
producer/main races and the unused staged entry; misses remain nonblocking.

The composition mechanism works, but phase attribution is needed to explain the smaller and
variable whole-launch effect before changing defaults. Host observation at 01:09 has about
8 GiB available memory and load average below 1; there is no evidence for another RAM increase.

Current phase: phase diagnostic on the combined candidate, then same-build comparison.
Diagnostic `20260906-011245`: resource batches complete in 7.148 s (previous diagnostic 10.228 s),
but the post-batch ScriptStore call takes 3.325 s rather than 35 ms. Installed bytecode verifies
that this call sets the completion flag and joins `ScriptStore$3`, then rethrows its stored error.
The worker loads/constructs queued script classes and logs almost 29,000 already-loaded messages.
The new critical dependency masks the texture-stage saving.

The next combined change removes only that exact reviewed INFO message through the existing
asset-progress suppression control. It retains class loading, every constructor, plugin repository
registration, compilation logging, warnings, errors, queue order and thread lifecycle. Exact
Windows worker SHA-256: `5a3c77574db4dc789609d87baaf281e5f4160649db1a0055a95b860330e05699`;
installed core JAR SHA-256: `5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8`.
An installed structural test confirms that all non-progress method calls and exception handlers
are retained. Unknown bytecode or suffix drift declines the rewrite.
Finish condition: retain only a justified change, integrate main, restore the ordinary launch task,
and retire disposable builds while retaining evidence.
