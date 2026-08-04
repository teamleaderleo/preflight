# Next LLM Implementation Handoff

This is the single living implementation handoff. Archive dated evidence under `docs/evidence/`; do
not create parallel handoffs. Rewritten 2026-08-03 — the prepared-pixel comparison work this file
used to carry is merged, and is described by `docs/evidence/` and `docs/prepared-textures.md`.

## Where the launch is

On 2026-08-04, the intermittent vanilla "resource not found" startup fatal was traced to a shared
one-shot mod-source hint in `com.fs.util.C`: its synchronized setter and resolver are separate calls,
so another loading thread can consume the hint between them and filter out the correct directory.
`SourceHintIsolationPlan` moves only that transaction to a `ThreadLocal`, exact-gated on the shipped
class and archive, and is included for every enabled adapter. Offline concurrency, composition, and
exact installed-archive checks pass. A live campaign smoke then completed with exit 0, ACTIVE health,
17 transforms, 1,222 hints consumed, and no resource fatal; its timing is invalid because the user
browsed concurrently. Evidence:
`docs/evidence/2026-08-04-resource-source-hint-race.md`.

The same campaign recording exposed the next in-game target: MagicLib paintjob unlock checks occupy
101 of 1,055 campaign main-thread samples (9.57%) because every check copies a `LinkedHashSet` into
an `ArrayList` and scans it. An exact MagicLib 1.5.6 adapter now queries the authoritative set with a
preserved-original fallback. A live campaign answered 1,316,681 checks with zero delegation or
failure and removed the entire copied-list stack from JFR. That recording exposed a second MagicLib
list scan for already-notified IDs on 36 samples; a separately exact-gated, mutation-invalidated set
snapshot then served 1,312,748 live checks after 438 mutations with one rebuild and zero fallback.
The notification scan disappeared and the whole manager fell to 9/1,500 samples. Full `mvn verify`
and both exact installed-archive transforms pass. Evidence:
`docs/evidence/2026-08-04-magiclib-paintjob-campaign-hotspot.md`.

The next live profile put Stellar Networks' paused `MarketUpdater` on 92/1,500 campaign samples
(6.13%). The inner `CargoData.sort` is real cargo/fleet/stat synchronization and is not safe to
skip. The safe boundary is the updater's outer policy: it refreshes a random remote market on every
frame while game time is paused. An exact Stellar Networks 3.3.0 adapter now shuffles one market
snapshot per paused interval, refreshes each at most once, and then returns early until unpause or a
reviewed market invalidation. A live stress pilot crossed 12 pause intervals, served 1,320 markets,
stopped 267 exhausted frames, and had zero delegation or failure. Stelnet fell to 66/1,449 samples
(4.55%); repeated zero-sample stretches corroborate queue exhaustion. Full `mvn verify` and the exact
installed-archive transform pass. Evidence:
`docs/evidence/2026-08-04-stelnet-paused-market-refresh.md`.

That pilot also surfaced vanilla Starsector's macOS `Warning: Low system RAM remaining`. The exact
bytecode in `com/fs/starfarer/campaign/C.o00000(CampaignState, boolean)` warns whenever
`OperatingSystemMXBean.getFreePhysicalMemorySize()` is below 1,000 MB. On macOS that is literal free
pages, not the OS memory-pressure model and not reclaimable inactive/speculative/file-cache memory.
An exact adapter now consults `/usr/bin/memory_pressure -Q` only when vanilla's literal-free count
would warn, applies the same threshold to estimated available memory, and preserves the warning on
real pressure or any probe failure. Runtime, weave, exact installed-archive, and full verification
pass. A clean live pilot applied all 21 transformations with zero fallback; this event-driven method
was not invoked in that session (`checks=0`), so live compatibility is verified but a naturally
occurring corrected warning remains to be captured. Evidence:
`docs/evidence/2026-08-05-macos-memory-pressure-warning.md`.

A later controlled combat run hit an impossible `A.J -> A.null` cast in vanilla `Ship.advance`
while ships were destroyed around a full retreat. Static bytecode and a live one-shot probe prove
that the source directly implements the target, both loaded from the same exact archive and app
loader. The reviewed launcher runs x86-64 Zulu 17 under Rosetta with aggressive experimental flags
and C1-only combat directives. Replaying the destruction/retreat overlap with only `Ship.advance`
interpreted completed normally, with no obvious frame-time regression. `CombatJvmSafeguard` now
adds that one compile exclusion only when the exact OS, launcher policy, directives, runtime, and
`Ship.class` hash all match; every drift fails closed and the decision lands in `run.json`. Evidence:
`docs/evidence/2026-08-05-combat-jvm-safeguard.md`.

A subsequent long combat run proved the same impossible cast has a second site in `Ship.render`,
even while HotSpot confirmed the `Ship.advance` exclusion was active. The strict automatic safeguard
now interprets both overloaded `Ship.render` methods as well as `advance`, under the same exact
macOS/runtime/launcher/directives/`Ship.class` fingerprint. A subsequent campaign plus large battle
completed normally with both exclusions accepted, no cast failure, and ACTIVE adapter health.
`Ship.render`'s own bytecode was 0.50% of combat samples interpreted versus 0.52% in the earlier
compiled-render recording; the median combat frame was 16.8ms versus 17.5ms. The battles were not
identical, so their worse tail percentiles are not a controlled A/B, but no method-local interpreter
tax is visible.

The retreat recording also supplied the first clean post-startup campaign hotspot after the earlier
MagicLib and Stelnet work. Vanilla `CommodityOnMarket.reapplyEventMod` occupied 84/781 campaign
samples (10.76%) by removing and recreating the same event modifier every frame. An exact 0.98a-RC8
memo now fingerprints the four inputs and post-vanilla output in transient per-commodity fields;
first calls and every changed input use the retained original. Synthetic and installed-class
execution tests cover zero and non-zero trade quantities and external mutations. It is enabled with
the other campaign caches by `--fast`/`--campaign-entity-index`. The same clean mixed-state pilot
served 15,970,331 unchanged calls and delegated 197,095 changed/first calls: a 98.78% hit rate with
no reported failure. `reapplyEventMod` fell from 10.76% to 7.07% of campaign samples across
non-identical runs; an identical-save A/B is still needed for precise frame-time attribution.
That recording showed the v1 hit path itself still spending 5.17% of campaign samples in the
four `MutableStat` getters and combined-quantity arithmetic. An offline-validated v2 exact-gates a
read-only dirty accessor on the shipped `MutableStat`, then checks clean flags, backing-object
identity, and authoritative public float bits directly. Hits skip all four getters and the quantity
calculation; every dirty/direct-write/object/description/econ-unit change still delegates. Missing
accessor linkage disables the memo and falls through to vanilla. Exact installed-class execution and
full `mvn verify` pass; a short campaign pilot is the remaining live gate.
Evidence: `docs/evidence/2026-08-05-commodity-event-mod-campaign-hotspot.md`.

Measured on the 83-mod profile, macOS, M5 MacBook Air, `--fast`, game log start to main menu:

| | seconds |
| --- | ---: |
| baseline before this project | 62.6 |
| 2026-08-03 morning | 40.52 |
| 2026-08-03 `main` @ `d29ba17` | 34.66 / 35.54 |
| **2026-08-04 merged-read cache warm** | **33.42 / 34.15** |
| 2026-08-04 Janino warm pilot | **29.46** |
| 2026-08-04 controlled v3 warm probe | **31.90** |

Two runs, because single-launch variance on this profile is about **±1.4s**. Anything worth less than
that cannot be measured by launching the game and has to be measured by replay instead.

**The goal is a repeated measured 33.0s or below.** A later controlled warm probe reproduced the
sub-32 result at 31.90s: adapter health was ACTIVE, all 16 exact transformations applied, Janino
served 228/228 calls, the merged-read cache served 1,469/1,469 keyed calls, and GraphicsLib compact
replay applied once. It is still one probe rather than a controlled pair. The separate quiet-log
smoke reached 32.279s. A 34.706s interactive launch is not comparable: a UI-controller attach
mistake launched a second Starsector instance during startup and the run streamed the full
unbuffered console through a PTY.

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

1. Quiet-log PR #315 merged as `eb008e8` with every Linux, macOS, and Windows check green.
2. Tagged-spec PR #316 merged as `f63303d`. It was fidelity-replayed, learned, and measured in two
   warm launches; evidence is in
   `docs/evidence/2026-08-04-tagged-spec-json.md`. Its real migration also proved the merged cache's
   12,584 shadowed spec entries are pruned transactionally (17MB back to 8.0MB).
3. Adapter-health PR #317 is open, with GraphicsLib compact-replay PR #318 stacked on it. Both are
   mergeable and their Linux, macOS, and Windows checks are green.
4. Janino complete-map cache PR #319 is stacked on #318. Its clean cold/warm pilot moved the direct
   aggregate from 18.014s to 2.364s and the whole launch from 34.83s to 29.46s; it is included by
   `--fast` after that clean live gate.
5. GraphicsLib insignia manager-cache PR #320 is launch-free verified and now records passive
   hit/miss path timings; it awaits a controlled combat visual/counter/frame-time pilot before any
   speed claim or default enablement. Campaign entity-index activity reporting is open as PR #321.

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
| `--quiet-logs` | **0.403s** | merged in #315; replay + real smoke pass |
| tagged-tree rehydration for the four spec caches | **0.261s** | merged in #316; 394ms -> 132/134ms exact seam |
| persisted Janino complete maps | **15.650s direct aggregate / 5.37s whole launch** | exact full-profile identity; clean cold/warm live pilot; included by `--fast` |
| GraphicsLib compact startup replay | **3.038s exact callback** | clean live adapter application; PR #318 |
| GraphicsLib insignia manager cache | 4.40% of long-session game-thread samples is all GraphicsLib | exact per-render adapter built; combat pilot pending |

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

**GraphicsLib.** The exact 1.12.1 compact startup replay is live-gated and open as #318. A separate
long-session JFR review found GraphicsLib on 1,141 of 25,951 game-thread samples (4.40%). Its largest
Java-owned frame is `InsigniaPlugin.renderInUICoords`: 105 samples land in
`CombatFleetManager.<init>` because the plugin asks for the same absent owner once per ship and the
game constructs a fresh unattached manager on every miss. `codex/graphicslib-insignia-cache-pr`
caches that accessor only within one render invocation, changes no render math, passed exact
installed-JAR, dry-run, and woven-execution gates, and awaits a controlled combat visual/frame-time
pilot. Evidence:
`docs/evidence/2026-08-04-graphicslib-insignia-manager-cache.md`.

**Janino.** `codex/janino-profile-cache` wraps the exact complete-map `generateBytecodes` seam and
leaves Janino definition intact. The context content-hashes all ordered mod archives, loose Java and
class providers, core JARs, the game/Janino JARs, and bundled JVM modules, plus compiler/loader/
protection policy. Hashes overlap through `ProfileIdentityContext`; archive order is recovered from
the in-memory resource index rather than decoding a full class-entry index. Fast Rendering's custom
system classloader is detected and owns this seam, so Janino preparation is suppressed there while
independent Preflight caches remain available.

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
