# Hull loader frame preservation follow-up

Base: `a3c59b2df277417672fb1859fb5580ee67e697c1` (PR #1279).
This is a bounded follow-up audit of the same stack-map failure pattern, not a
claim that every bytecode transformation has been audited or that another native
game crash was observed.

The hull JSON cache and hull timing writer still recomputed all class frames
using the application-hierarchy fallback to `Object`. The timing-only plan does
not add control flow, but its writer could nevertheless change unrelated frames.

`HullLoaderFramesTest` adds a valid application Base/Child join to the hull loader
fixture and executes that method through a class loader. The original fixture
verifies. All three conditions on the previous implementation fail with
`VerifyError: Bad type on operand stack`: cache-only, timing-only, and composition.
The failed report is retained locally at
`benchmark-results/hull-frame-audit/before-fix.txt` (command receipt
`20b2297d0582a2ab`).

The fix uses `PreparedJsonCallPlan` for hull cache branching and preserves the
original frames in both writers. Tests cover independent transforms, sequential
composition, and the shared-tree composition used by the registry. Existing
executable hull cache tests still exercise cold capture and warm bypass of the
original JSON loader. No global writer behavior or pinned replacement-hash policy
was changed.

The installed Mac `ShipHullSpecLoader` was inspected with `javap` directly from
`/Applications/Starsector.app/Contents/Resources/Java/starfarer_obf.jar`; the JSON
and specification calls match the reviewed call families. Its bytecode listing
is retained locally under `benchmark-results/hull-frame-audit/`, not committed.
This static inspection is distinct from the executable synthetic regression.

The capability source lock matches all 32 guarded files. No game, browser, VM,
remote desktop session, or benchmark was started for this slice. Full native
FastRendering acceptance (#1269) and Linux fullscreen behavior (#1251) remain
separate open gates. Installed GUI packages are unchanged.
