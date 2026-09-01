# Accepted combat plans promoted to Recommended

Date: 2026-08-27

Install: Starsector 0.98a-RC8, current heavily modded profile, macOS on Apple M5,
Java 17 under Rosetta

Status: promoted to the Recommended/`--fast` launch policy after exact tests, stress evidence, and
an ordinary-fixture live safety confirmation

## Decision

Recommended now enables four already accepted combat plans:

- AI Tweaks `splitArcs` capacity sizing;
- AI Tweaks exact affine-vector fusion;
- vanilla combat-listener array walking; and
- the exact-empty combat-listener snapshot shortcut.

The policy lives at the product-preset boundary rather than changing the bare agent. Conservative,
Off, and Custom preserve their prior option strings. A user-supplied per-plan value, including
`false`, wins. Every plan retains its Java version, class hash, archive hash, source, loader, method,
and instruction-shape admission gates; unknown inputs keep original bytecode.

## Ordinary-fixture confirmation

One B-only Preflight run used `campaign-simulation-combat` after the favorable 1,040-DP pair. It
deployed 8 allied and 25 opposing ships, ran at ordinary 1x simulation speed, verified zoom from
1.250 to 4.250, measured exactly 60 seconds, passed all 34 semantic steps, and stopped the exact
owned game process normally.

The clean window recorded 3,349 frames, 55.34 average FPS, 18.42 FPS 1% low, 31.27 ms/s stutter
burden, and 1.82% recurring-slow-frame exposure. These are a B observation, not a paired uplift
claim.

More important for the safety decision, the listener path exercised both branches:

- 12,391,414 exact empty snapshots used the shared private empty array;
- 6,867 non-empty lists delegated to a fresh `toArray()` snapshot; and
- zero unknown list implementations were observed.

This confirms that the mutation-safe fallback is live in ordinary combat rather than merely
present in tests. Collision-query v2 also hit 1,647,196 of 1,647,332 hints and the run had no
gameplay fatal. macOS reported no thermal or performance warning after the run.

## Verification

Full `mvn verify` passed under Java 17 in 45.609 seconds. The packaged CLI dry-run printed
`optimization preset: recommended` and all four final `JAVA_TOOL_OPTIONS` properties. Unit tests
cover Recommended activation, idempotence, explicit `false`, and exact preservation of Custom,
Conservative, and Off.

Compact values and raw-artifact hashes are retained in
[`data/2026-08-27-recommended-combat-promotion.json`](data/2026-08-27-recommended-combat-promotion.json).
The screenshot, JFR, copied log tail, and launch directory remain disposable rather than adding
megabyte binaries to Git.

## Residual frontier

The stress profile still spreads recurring work across vanilla ship/weapon-group advance, AI
Tweaks targeting, fighters, graphics effects, collision construction, and damage-analysis mods.
The retired AI Tweaks `SelectTarget` field snapshot remains retired. Promotion of these exact local
plans is a checkpoint, not a claim that combat is exhausted.
