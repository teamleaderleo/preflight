# Windows startup refinement after prepared audio

Objective: reduce ordinary Windows Recommended startup beyond the established 18.953 / 17.620
second interactive-menu observations while retaining resource identity, lifecycle, worker and
main-thread GL contracts. Owner: Codex. Baseline main: `37e6b49c`.

Installed baseline executable source: `d7501b2e8682b2a168a75bba074cc3b028959370`.
JAR SHA-256: `1c7405a1adc71849a9c010aaba6eb78bb8a72b4457257baf19bc3ed652121fbc`.
VM remains 20 GiB / 14 CPUs, native graphics, 1024x720, one resource worker.

## Working notes

- Start with a phase/texture-CPU/prepared-load diagnostic on the accepted ordinary configuration.
  Its timings are diagnostic, not an ordinary-launch performance claim.
- Hypothesis: prepared-pack read/parse work is now significant on main. Compare the existing
  bounded exact-entry read-ahead candidate only if the diagnostic supports that direction.
- Hypothesis: the Windows audio cache still hashes/copies encoded bytes at serving time even
  though launch already validates its path manifest. A reviewed filename-level binding could
  avoid that duplicate work, but requires exact caller and stream-lifecycle validation.
- Do not promote an isolated lower sample. Check resource/audio/faction/pack counters and compare
  ordinary launches on identified artifacts. Record rejected approaches as well as accepted ones.

## Observations

- Diagnostic `20260905-234523`: SpecStore 3.581 s; resource batches 10.228 s; texture calls
  9.766 s; pack lookup 3.971 s including pack parsing 3.884 s. Carrier construction 69 ms and
  layout classification 5 ms are small. Audio completes four milliseconds after texture progress;
  it is no longer the trailing dependency. Mod callbacks take 2.829 s, led by GraphicsLib 823 ms.
- Exact-entry read-ahead comparison `20260905-235440`: first launch reaches the menu in 21.316 s;
  the second stalls in native `GL11.nglTexImage2D` on main. Two snapshots at 312.40 and 415.81 s
  show unchanged main CPU (7281.25 ms) and the same native call. This is a failed comparison,
  not a fast result. It does not prove the parser caused the driver stall.
- Retired only the game PID/creation identity verified in both snapshots. The operator archived
  the failed cohort and restored the normal task. No implementation or installed JAR changed.
  Read-ahead remains off. Private stacks are in `Diagnostics/windows-post-audio/`.
- Next verify the accepted ordinary configuration again, then pursue a different bounded change.
  The audio path-manifest binding remains a possible way to eliminate redundant source hashing;
  its likely magnitude is smaller because audio is no longer the final dependency.

- Ordinary recovery `20260906-000432` reaches graphics in 19.978 s and the menu in 21.034 s.
  This unchanged-build observation shows fixture variation; the 21.316 s read-ahead observation
  alone cannot establish a regression. The failed second run still prevents promotion.
- Direct-buffer pooling was deferred because stock cleanup currently frees the buffer before
  Preflight releases its accounting. Reuse would require a new verified cleanup contract.
- Production memory mapping was considered and deferred: explicit unmapping, Windows replacement,
  and truncation behavior add lifecycle risk. A smaller opportunity exists in the current parser.
- Candidate: include the 32-byte embedded SHA trailer in the existing bounded LZ4 scratch read.
  Keep parser bounds at the content length; the pack integrity wrapper verifies the CRC over
  the complete entry and only reads a separate trailer when the parser has not consumed it.
  RAW and general parser paths retain their existing reads. No worker, GL, cache, or handler
  contracts change. This removes one positioned read and a 32-byte allocation per fast LZ4 entry.
- Focused validation: 19 tests pass, including exact one-read coverage, short reads, neighboring
  range exclusion, corrupted trailer rejection, truncation, existing RAW mutation and pack-close
  tests. Timing benefit remains unmeasured at this point.

Current phase: full verification and ordinary Windows candidate measurement.
Full Java verification passed in 56.486 s; all nine installed Windows loader-contract tests pass.
An explicit `singleReadLz4=false` check exposed an existing test assumption: its wrong-suffix RAW
fixture expected rejection by the specialized reader even when that reader was disabled. The test
now checks the general reader's valid RAW result in that mode; production fallback behavior is
unchanged. The two fast-path read-count tests explicitly require that path to be enabled.
Finish condition: integrate a verified improvement or document a bounded unsuccessful comparison,
restore the normal Windows task and retire disposable outputs.
