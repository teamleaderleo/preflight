# AI Tweaks boxed search-range correction

**Status:** corrected; the risky boxed-field rewrite was removed and the exact v4 target completed
the live 1,040-DP route cleanly

A focusless 1,040-DP combat run completed its 30-second measurement window, then Starsector stopped
with a Preflight-owned `NullPointerException` in AI Tweaks 2.2.10:

```text
Cannot read field "preflight$targetSearchRangeBoxed" because "this" is null
at com.genir.aitweaks.core.shipai.autofire.SelectTarget.selectTarget(SelectTarget.kt:274)
```

The exact v3 transform had replaced one reviewed
`targetSearchRange -> Float.valueOf` pair with a read of a private final synthetic boxed field. The
receiver should not be null under the source-level instance-method contract, but the real
Rosetta/HotSpot combat workload is authoritative: the transformed read failed after 64,526 target
selection snapshots. Prior fixture, installed-class, and live runs did not make that failure safe to
ignore.

The v4 plan removes `preflight$targetSearchRangeBoxed` completely and preserves AI Tweaks' original
primitive field read and `Float.valueOf` call at that candidate-loop boundary. It retains the safer
per-selection primitive engagement-range snapshot, the boxed engagement-range snapshot used at two
other exact sites, and the weapon-location snapshot. Class hash, archive hash, Java 17 bytecode,
loader, constructor, field, call-count, and receiver-shape gates remain fail closed.

Focused fixture and exact installed-AI-Tweaks tests passed. The installed-class test now requires
that the synthetic target-search field is absent and that the original boxed target-search read is
still present. Full Java 17 `mvn verify` also passed.

The follow-up active 1,040-DP run applied `aitweaks-select-target-snapshot-v4`, executed 61,413
selection snapshots, completed the clean 30-second combat window, and exited with launcher and
scenario status `passed`. Its captured log contains no recurrence of the null dereference. The run
also validates the focusless scenario and collision-capacity candidate described separately; it is
not an FPS A/B for this correctness correction.

The failing and passing log-tail SHA-256 values are
`ba8fbe46dad3c1881d853819ce36f266c19d04d1a29516d4854db561c6d5bd14` and
`d7a366a2805d908cd64250d7e3d6f51e203b56e978fe829236918789e757c86f`. Raw logs, recordings, and
transformed classes remain disposable local artifacts.
