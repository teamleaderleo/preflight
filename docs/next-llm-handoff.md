# Next LLM Implementation Handoff

This is the single living implementation handoff. Archive dated evidence under `docs/evidence/`; do
not create parallel handoffs. Rewritten 2026-08-03 — the prepared-pixel comparison work this file
used to carry is merged, and is described by `docs/evidence/` and `docs/prepared-textures.md`.

## Where the launch is

Measured on the 83-mod profile, macOS, M5 MacBook Air, `--fast`, game log start to main menu:

| | seconds |
| --- | ---: |
| baseline before this project | 62.6 |
| 2026-08-03 morning | 40.52 |
| 2026-08-03 `main` @ `d29ba17` | 34.66 / 35.54 |
| **2026-08-04 merged-read cache warm** | **33.42 / 34.15** |

Two runs, because single-launch variance on this profile is about **±1.4s**. Anything worth less than
that cannot be measured by launching the game and has to be measured by replay instead.

**The goal is a measured 33.0s or below.** The merged-read cache pair averages 33.79s, so the target
is not met yet. Its direct seam timer confirms the predicted win; whole-launch noise hides part of it.

Preparation before the JVM starts is a further **1.19s** and is *not* inside any number above:
`run.json`'s `started` is captured immediately before the child process is spawned, so preparation
sits outside every figure the harness records. See
`docs/evidence/2026-08-03-four-seconds-before-the-jvm-logs-anything.md`.

## Verified in flight: the general merged-read cache

Branch `perf/merged-read-cache`; implementation commit `752e94f`, handoff commit `4c85ec4`, followed
by the completion work on this branch. **It compiles, is wired end to end, has unit/runtime/weave
coverage, passed offline fidelity against the game's real JSON implementation, and passed one clean
learning plus two clean warm launches.** Launch evidence is in
`docs/evidence/2026-08-04-merged-read-cache-launch.md`.

### Why it exists

The five caches before it each pinned one loader and cached that loader's reads. `MergedReadProbePlan`
was built to answer the question those five cannot — *how much merged reading is left anywhere* — and
the answer, after the `abs:` key fix, is **2,142ms across 1,471 calls**: factions 259ms, ship systems
200ms, `descriptions.csv` 175ms, skins 174ms, `hull_mods.csv` 159ms, `ship_data.csv` 128ms,
`weapon_data.csv` 125ms, plus `settings.json`, `strings.json`, skills, missions, and whatever mod
callbacks read. A sixth pinned loader takes the largest of those and leaves the rest.

So this one is not a loader's cache. It sits at the two methods every merged read funnels through and
keys on the *request*, which is what makes it cover callers nobody enumerated. That is the shape Leo
asked for: *"I generally just don't trust mod authors to have particularly optimized code so if we
could just do work for them on our end and just be compatible on any given config that'd be great."*

### What was verified before writing it

From `javap -p -c` on the installed `com.fs.starfarer.loading.LoadingUtils`:

- `super(List, String, boolean, boolean) -> JSONArray` is the merged CSV reader. Argument 0 is the
  key-column list used for duplicate detection, 2 selects whether the last root's rows go to index 0,
  3 selects a `SpecStore` preprocessing pass over the raw text.
- `super(String, Set) -> JSONObject` is the merged JSON reader. The `Set` reaches
  `super(JSONObject, JSONObject, Set, String)` and is used **only** via `contains`, so sorting it for
  the cache key is safe and folds two requests differing only in iteration order into one entry.
- `Ó00000(String) -> JSONObject` — the single-file reader the four spec caches intercept — is
  literally `super(path, null)`. This cache therefore sits *underneath* those four and catches their
  misses rather than competing with them.
- Both merged readers resolve roots through `com.fs.util.C.Object().Ò00000(path)`, which opens an
  `InputStream` per providing root. A hit skips that entirely, so there is nothing left unclosed.
- The bundled `org.json.JSONObject` is backed by a plain `HashMap`, and `JSONException extends
  Exception` (checked).

### What was built

`preflight-core`:

- `JsonTree` / `JsonTreeSink` — a tagged tree instead of text. One type byte per value, varint lengths
  ahead of contents, and a per-entry string table so a merged CSV decodes its thirty column names once
  rather than ten thousand times. The sink lets the decoder write straight into the game's own
  containers, so a hit allocates nothing it does not return. The earlier offline benchmark put
  decoding at **6.3x** faster than parsing the equivalent text over the same corpus.
- `MergedReadKey` — names a request, not a file. Only `data/`; absolute paths keep a distinct `abs:`
  shape so they never fold onto the relative key; a path containing a backslash gets no key.
- `PreparedMergedReadCache` / `PreparedMergedReadCacheIO` — `.spmr`, magic `SPMR`, the same
  checksummed transactional shape as the other five artifacts. Entries are written sorted so an
  artifact is a function of what it holds, not of the order a launch happened to learn it in.

`preflight-agent`:

- `GameJson` — the reflective bridge to `org.json`, resolved once.
- `MergedReadCachePlan` — `MergedReadProbePlan`'s rename-and-delegate rewrite with a cache behind it.
- `MergedReadCacheRuntime` — serve, learn, refuse collisions, publish.
- `AdapterTransformationRegistry.loadingUtilsPlans` — three plans now share `LoadingUtils` (cache,
  probe, single-file memo). The cache goes first; it reports the same per-path timing the probe does,
  so preferring it costs no measurement. Cache and probe each decline a class already carrying the
  other's renamed methods.
- Publishing is hooked to `StartupPhaseRuntime.mark("resource-init-complete")` — the first moment at
  which everything learnable has been learned *and* vanilla is known to have got through it. Mod
  callbacks finish immediately before it.

`preflight-cli`: `MergedReadProfileIdentityBuilder` (schema
`starsector-preflight-merged-read-profile-v1`, selects everything under `data/`, reusing
`MergedJsonProfileIdentity`'s digest layout unchanged), the artifact path, the `AgentInjection`
parameter `mergedReadCache64`, and `.spmr` in `CachePrune`.

### Pre-launch gates completed after the handoff

- `JsonTreeTest`, `MergedReadKeyTest`, `PreparedMergedReadCacheIOTest`,
  `MergedReadCachePlanTest`, and `MergedReadCacheRuntimeTest` now cover the format, request keys,
  persistence, both bytecode rewrites, composition with `LoadJsonMemoPlan`, learning, publishing,
  warm hits, collision refusal, and unstorable fallback. The malformed-input work found and closed an
  overflowing tenth-varint-byte acceptance bug in `JsonTree`.
- The offline fidelity replay passed all **12,584 entries / 990,602 recursively compared values** on
  Starsector's x86_64 JVM and exact installed `json.jar`. Evidence and the reusable harness are in
  `docs/evidence/2026-08-03-merged-read-json-fidelity.md` and its adjacent `.java` source.
- `ProfileIdentityContext` now memoises provider resolution and SHA-256 for one preparation, with
  tests proving overlapping corpora reuse both while a new preparation revalidates paths and sees
  changed bytes. `RunCommand` also reuses the checksummed `ResourceIndex` already decoded by
  `CurrentTextureCache` instead of reading the 8 MB artifact again. Five alternating fresh-process
  runs kept all seven identities exact and reduced median incremental identity preparation from
  **632.543ms to 357.934ms (-274.609ms)**; the merged-read phase itself fell from 208.409ms to
  86.311ms. Evidence: `docs/evidence/2026-08-03-profile-hash-memo-benchmark.md`.
- Full `mvn verify` passed after these changes, including failsafe and synthetic cross-process tests.
- Full `mvn verify` passed again after the real-launch settings safety fix: core 192 tests, CLI unit
  348, failsafe 35, and synthetic 22 with one expected skip.

### Real-launch result

- The first exploratory learning launch caught a real collision: `data/config/settings.json` is read
  before and after mod resource roots come online, so identical request arguments produce different
  overlays. `MergedReadKey` now leaves that one dynamic relative path vanilla. The corrected learning
  launch captured 1,469 calls, wrote one 8.0MB artifact, deliberately left two settings reads
  unkeyed, and had zero collisions or unstorable values.
- Two warm launches served 1,469 calls from 1,468 entries with zero misses, collisions, writes, or
  unstorable values. Tagged-tree rebuilding cost 188ms / 215ms.
- Direct seam cost fell from a 2,171.5ms baseline mean to 300.0ms warm (**-1,871.5ms**). SpecStore
  fell 1,415.5ms. Whole launch moved from 34.66 / 35.54s to 33.42 / 34.15s, a paired-mean win of
  **1.314s**. The 33.0s target is still 0.42s below the faster run and 0.79s below the warm mean.
- The full cache is currently 6.4GB. Spec-store is 33MB, indexes 15MB, manifests 15MB, and this new
  artifact is 8.0MB.

### What is left, in order

1. Merge quiet-log PR #315 when its CI finishes.
2. Rebase the stacked `codex/tagged-spec-json` branch onto that merge, then open its PR. It is
   implemented, fidelity-replayed, learned, and measured in two warm launches; evidence is in
   `docs/evidence/2026-08-04-tagged-spec-json.md`. Its real migration also proved the merged cache's
   12,584 shadowed spec entries are pruned transactionally (17MB back to 8.0MB).
3. Re-price the remaining profile. Quiet logs and tagged spec trees together remove 0.664s at their
   direct seams, leaving only about 0.12s of the prior pair-mean gap to 33.0s; launch noise is ±1.4s.

### The traps, from the ones already hit

- **Log-gap attribution is unsound.** Charging the gap between consecutive `[main]` log lines to
  whoever logged last attributes silent work to the wrong seam. It produced a wrong answer three
  separate times in one session (a 17.72s texture block that was really 1.15s; a 1.50s "rehydration"
  that was really 0.394s). Use `SeamTimer`, not the log.
- **A subphase label measures its whole call site, not the thing it is named after.** On a hit a
  `*-json-merge-parse` label measures lookup plus rehydration; on a miss it measures the full vanilla
  read. Two instruments with no shared code are how this gets caught — the `abs:` fix was confirmed by
  two independent measurements landing 37ms apart.
- **Fail closed, and read the counters.** The first `abs:` learning launch wrote zero entries because
  the core records rejected the new key shape. That was the design working. Read `writes` and
  `captures`, not only timings.
- **Run `mvn verify`, never `mvn test`** — surefire skips the failsafe ITs.
- **Never `git reset --hard` in this repo**, and stage files explicitly rather than `git add -A`.

## Backlog after that

| | worth | notes |
| --- | ---: | --- |
| general merged-read cache | **1.87s direct / 1.31s whole launch** | verified in flight, above |
| `--quiet-logs` | **0.403s** | implemented on stacked branch; replay + real smoke pass |
| tagged-tree rehydration for the four spec caches | **0.261s** | implemented; 394ms -> 132/134ms exact seam |
| GraphicsLib `ShaderModPlugin` | 3.97s, unpriced | see below |

**`--quiet-logs` (implemented).** The launch emits 122,437 lines, 28,963 of them from `ScriptStore` on `Thread-4`
contending for log4j 1.2's per-append lock. Replayed from two threads on the game's own JVM and log4j
jar, the loading thread pays 0.488s as shipped and **0.085s** with the console appender dropped and
the file appender buffered — and that loses no line, because the file appender already receives
everything the console does. Route: write a `log4j.properties` override and pass
`-Dlog4j.configuration=file:...`; the game's config is a classpath resource inside
`starfarer_obf.jar`, so log4j 1.2's `LogManager` honours the property without touching the jar.
Buffering costs the tail on a hard crash, so it is an explicit flag and is not folded into `--fast`.
Normal exit and SIGTERM flush through the existing agent shutdown hook. Current replay measured
0.491s shipped versus 0.088s quiet, the installed-log4j fidelity probe retained all 10,001 lines,
and a real 83-mod smoke reached the main-menu marker with a complete newline-terminated log. See
`docs/evidence/2026-08-04-quiet-logs.md`.

**GraphicsLib.** Deliberately deferred by Leo ("but later"). Grounding already gathered: its
`loadJSON` path prices at 0.85s with a 0.22s memo ceiling, already taken by the live memo. The probe
covering that callback recorded 7,759 JSON-load lines against 6,191 texture-buffer cleanups, so it
loads textures in `onApplicationLoad` and those already go through the texture cache. What it does
with them *afterwards* is unmeasured, and that is where the next probe goes.

## Environment notes that cost time to rediscover

- Launch the game with `--direct`; without it the run stalls on the launcher's Play button and
  measures nothing. Leo runs the launches; keep other CPU off the machine while one is measuring.
- `ls` is aliased to `eza` — use `command ls`. `grep --include=*.java` fails under this zsh.
  `mvn -pl X test` needs `-am`; `-Dtest=` needs `-Dsurefire.failIfNoSpecifiedTests=false`.
- The game's JRE has no compiler module, so single-file source launch fails with
  `InternalError: Module jdk.compiler not in boot Layer`. Compile with `javac --release 17` on the
  system JDK, then run the `.class` on the game's `java`.
- SHA-256 under Rosetta is **10x** slower (~285 MB/s). The CLI runs native arm64; the agent runs
  inside the game under Rosetta. That is why hashing stays in the CLI.
- `gh pr merge --admin` is blocked by the harness classifier; plain `--squash --delete-branch` works,
  and fails if the working tree is dirty.

## Known harness bug

The benchmark reporter reports "No comparison yet: no pair of conditions both have a successful run"
despite accepted runs; the per-round condition shuffle degenerates with two conditions. Unfixed.
