# Four spec caches now rebuild tagged trees in 133 milliseconds

**Branch:** `codex/tagged-spec-json`, initially stacked on quiet-log PR #315

**Result:** the variant, weapon, projectile, and hull caches now store typed JSON trees instead of
`JSONObject.toString()`. Their exact in-game rehydration seam fell from 394ms to 132/134ms on two
warm launches: a **261ms pair-mean win**.

## What changed

The four prepared records and their `.spvj`, `.spwj`, `.sppj`, and `.sphj` persistence formats now
carry `byte[]` values produced by the existing production `GameJson`/`JsonTree` codec. A hit decodes
straight into fresh objects from the installed `org.json` implementation. It no longer scans JSON
text or infers types from token spelling.

Each artifact format moved from V1 to V2. An existing V1 artifact is rejected as an unsupported
version, so it can never be mistaken for tagged data. The next launch captures the exact objects
vanilla produced and transactionally overwrites the artifact only after that loader completes. A
malformed V2 entry is quarantined and falls back to vanilla without disabling other entries.

This reuse is intentionally below the four bytecode plans: their targets and call-site contract did
not change. It also composes with the general merged-read cache. On the V2 learning launch, the four
spec caches rejected V1 and missed while the lower merged-read cache supplied the merged objects;
the spec caches then captured those exact objects into V2.

That learning launch also exposed a storage inefficiency: the lower cache published 12,584 copies
that warm launches would always resolve in the four upper caches, growing its artifact from 8MB to
17MB. The final implementation recognizes only those four exact JSON domains, declines to capture
new lower copies, and transactionally prunes existing copies at startup completion. Entries already
loaded remain available as same-run fallback until then; CSV and every other JSON domain are
unchanged.

## Installed-json fidelity and replay

`docs/evidence/2026-08-04-tagged-spec-json-fidelity.java` reads the checksummed V1 corpus directly,
then exercises the exact production bridge and codec through Starsector's JVM and `json.jar`.
Recursive comparison requires identical object keys, arrays, scalar Java classes and values, null
presence, and raw `double` bits.

```text
PASS: 12,584 entries, 990,602 values
text 12.9 MB; tagged trees 9.0 MB
JVM: 17.0.10 (x86_64)
json.jar: file:/Applications/Starsector.app/Contents/Resources/Java/json.jar
```

Five replay rounds put text parsing at 160-179ms and tagged decode at 28-47ms. Four rounds were
5.2-6.0x faster; one decode round was 3.4x. The tagged artifacts are also about 30% smaller by value
bytes.

## Real-game learning launch

Run `tagged-spec-learn-20260804-003619` explicitly rejected all four V1 artifacts, captured all
12,584 entries, and wrote every V2 artifact once:

| cache | misses | captures | writes | collisions |
| --- | ---: | ---: | ---: | ---: |
| variant | 5,573 | 5,573 | 1 | 0 |
| weapon | 3,077 | 3,077 | 1 | 0 |
| projectile | 1,263 | 1,263 | 1 | 0 |
| hull | 2,671 | 2,671 | 1 | 0 |

All four files begin with their expected magic followed by big-endian format version 2. The run
reached the transactional `resource-init-complete` phase and exited cleanly. The optional
GraphicsLib log marker was absent on this learning run, so it is not used as menu evidence.

## Two warm launches

`SeamTimer.rehydrateInsideMillis` brackets only `GameJson.decode`, just as the 394ms baseline
bracketed only the old string constructor. It excludes lookup, time between calls, and the loader's
other work.

| cache | text baseline | tagged warm 1 | tagged warm 2 |
| --- | ---: | ---: | ---: |
| variant | 76ms | 20ms | 18ms |
| weapon | 74ms | 32ms | 38ms |
| projectile | 76ms | 38ms | 42ms |
| hull | 168ms | 42ms | 36ms |
| **total** | **394ms** | **132ms** | **134ms** |

Both warm runs served all 12,584 entries with zero misses, captures, or collisions. Both reached the
ordinary GraphicsLib main-menu marker after quiet-log shutdown flush, produced newline-terminated
logs, reported run/launcher exit code 0, and left no game JVM alive. Their log deltas were 32.838s
and 32.556s, but that whole-launch number is not the performance claim: ±1.4s launch noise is much
larger than a 261ms effect.

Run `tagged-spec-prune-20260804-004550` then exercised the cleanup against the real polluted merged
artifact. It identified and removed exactly 12,584 dedicated-spec entries, wrote once at normal
startup completion, and shrank the file from 17MB to 8.0MB. The four upper caches still served all
12,584 reads with zero misses and 125ms total rehydration. The run reached
`resource-init-complete`, exited 0, and left no game JVM alive; as on the learning run, its optional
GraphicsLib text marker was absent and is not used as menu evidence.

## Verification

After the real launches and final review, full `mvn verify` passed: core 194, CLI unit 352, failsafe 36, and
synthetic 22 with one expected skip. The core/agent tests cover deterministic persistence, V1
rejection, fresh-object reconstruction, bad-tree fallback, and all four path domains.
