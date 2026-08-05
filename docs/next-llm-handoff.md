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
An exact adapter now captures `/usr/bin/memory_pressure -Q` once during agent startup, applies the
same threshold to estimated available memory, and preserves the warning on real pressure or any
probe failure. A later live run naturally corrected two false warnings, but synchronously forking
the bundled x86-64 JVM at those transition points caused sharp CoreAudio pops. A plan-level live
bisection isolated this adapter alone; moving the pressure capture before audio initialization
keeps later checks process-free. Runtime, weave, bundled-JVM, exact installed-archive, and full
verification pass. The final all-optimizations pilot applied 29 transformations with zero fallback,
captured pressure once, reused it for four corrected warnings, and produced no campaign-load or
simulation-exit pop. Evidence:
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
full `mvn verify` pass. The live v2 campaign pilot then served 128,803,184/129,026,515 calls
(99.8269%), delegated 223,331 real changes, reported zero fast-validation fallback, and completed
with ACTIVE health. `getCombinedTradeModQuantity` disappeared completely (0/1,677 campaign samples,
versus 31/580 under v1). The next exposed layer is the retained exact `eMod` map lookup:
`MutableStat.getFlatStatMod` now occupies 212/1,677 campaign samples (12.64%). Do not remove it
without an equally exact way to detect direct mutation through the publicly exposed flat-mod map.
An offline-green v3 now retains the exact backing map, entry node, and `HashMap.modCount`; it catches
same-key replacement through node value identity, structural edits through the generation, map
replacement through identity, and still compares public `StatMod` fields. The capability uses
Starsector's existing `--add-opens java.base/java.util=ALL-UNNAMED`; without it, the exact old lookup
remains. Missing accessor linkage disables the memo after vanilla returns. Closed-module, open-module,
real installed-class, and full `mvn verify` checks pass. The live v3 pilot then served 24,241,238
hits, delegated/captured 223,219 exact post-vanilla states, and reported snapshot capability active
with zero unavailable capture or accessor fallback. `getFlatStatMod` fell from 12.64% to 0.51% of
campaign samples; all five survivors were legitimate vanilla delegations. The wrapper itself is now
a 6.82% compiled leaf. A later 99.72%-hit profile localized 131/1,474 campaign samples (8.89%) to
the v3 VarHandle `modCount` validation itself. On Starsector's exact x86-64 Zulu 17/Rosetta JVM, five
fresh 100-million-iteration, 1,024-map runs measured direct exact-key lookup at 2.835-2.849ns/op
versus 4.506-4.725ns/op for the retained-entry/VarHandle check. An `Unsafe` generation-read variant
managed only 2.356-2.371ns/op, too small a gain to justify internal-JVM coupling. V4 validates the current
`eMod` mapping directly through the exact flat-map accessor. This preserves every relevant mutation
boundary—unrelated map structure is not an input—while removing the slow validation seam and its
extra transient snapshot field. Synthetic, fail-open, exact installed-class execution, and full
`mvn verify` pass. The live v4 profile exited normally with ACTIVE health, served 117,907,677 hits
and 223,330 delegations with no fallback, and removed the complete VarHandle validation stack. Its
14.73% memo sample share versus 15.67% in the non-identical prior run is directional only; most
remaining work is now an inlined compiled wrapper leaf.
Evidence: `docs/evidence/2026-08-05-commodity-event-mod-campaign-hotspot.md`.

The same campaign profile put vanilla's `com.fs.starfarer.coreui.A.oOoO.renderStuff` at 22/174
campaign main-thread leaf samples (12.64%). Exact installed bytecode showed three separable kinds of
work: live per-frame visibility/fader updates, a full entity-list copy, and construction plus filling
of the same seven-class `HashSet` every frame. The first must remain live, and the list snapshot has
concurrent-iteration semantics that need a separate mutation-boundary proof. An offline-green exact
adapter now handles only the safe first slice: it initializes the reviewed seven-class set once and
reuses it while the existing exact gameplay-cache gate is active; gate-off executes the untouched
vanilla allocation block. The shipped class hash, exact seven literals/order, archive, loader, and
single construction block are pinned. Synthetic shape/gate tests, an exact installed-class
transform, and full `mvn verify` pass. A live non-JFR campaign/combat roam completed normally with
ACTIVE adapter health (30 applied, zero declines/failures) and served **6,199** radar frames through
the cached set. The preceding JFR attempt hit HotSpot's own `SharedRuntime::get_poll_stub` safepoint
assertion in the x86-64 Zulu 17 VM under Rosetta before the renderer loaded, so passive frame/radar
telemetry is now available independently of profiling. The live gate proves use and compatibility;
whether the allocation removal survives frame-time sampling noise still requires controlled A/B.
Evidence:
`docs/evidence/2026-08-05-campaign-radar-type-set.md`.

Frame reports now expose direct throughput alongside frame-time percentiles: average FPS, median
FPS, 1% low, 0.1% low, and the percentages meeting 60- and 30-FPS budgets. These are derived during
report serialization from existing counters, so they add no per-frame work. The first mixed live
campaign readout was 53.40 average FPS, 59.17 median, 15.04 1% low, and 6.78 0.1% low; 45.64% of
frames met 60 FPS and 96.32% met 30 FPS. That points at tail latency rather than broad throughput as
the next target. Its short combat slice included a load transition and is not a steady-battle
result. Evidence: `docs/evidence/2026-08-05-frame-time-fps-reporting.md`.

The same mixed-state recording put GraphicsLib's `LightShader` on 48/1,010 combat samples (4.75%).
Exact installed bytecode shows its render loop rereads three stable float settings through LunaLib
on every call. An offline-green exact GraphicsLib 1.12.1 adapter now retains each original getter,
caches its existing static field after the first read, and invalidates all three values at the start
of GraphicsLib's own `load()` and `applyChanges()` settings boundaries. Executed synthetic behavior,
real installed-archive structure, fail-closed drift, and full `mvn verify` pass. A live combat pilot
matched the exact target but correctly retained the original because its separate plan-availability
registry entry was missing. That plumbing entry and a regression assertion were added. The second
pilot exited ACTIVE with zero fallback/failure and served 7,621 hits after the expected three field
misses and one invalidation. The prior Luna float stack disappeared from JFR while LightShader
remained active; non-identical battles prevent a frame-time A/B claim. Evidence:
`docs/evidence/2026-08-05-graphicslib-hot-settings-cache.md`.

That failed-closed pilot also captured the operator's startup audio transient as a recoverable
`AL_INVALID_VALUE`: the first music-source construction failed, then succeeded 516ms later. Exact
bundled bytecode proves vanilla reads and stores `alGetError()` before `alGenSources`, then treats
that stale earlier error as the generation result without reading the real result. An exact adapter
now clears and records the old error, reads the actual error immediately after generation, and lets
vanilla's existing branches use it. Executable tests prove stale-error recovery and preservation of
real generation failures; the installed-archive transform passes. The combined live pilot then saw
202 constructions, one stale error, zero real generation errors, and one recovered false failure.
Main-menu music played on the first attempt with no initialization error, and adapter health was
ACTIVE with zero fallback/failure.
Evidence: `docs/evidence/2026-08-05-openal-stream-source-stale-error.md`.

Measured on the 83-mod profile, macOS, M5 MacBook Air, `--fast`, game log start to main menu:

| | seconds |
| --- | ---: |
| baseline before this project | 62.6 |
| 2026-08-03 morning | 40.52 |
| 2026-08-03 `main` @ `d29ba17` | 34.66 / 35.54 |
| **2026-08-04 merged-read cache warm** | **33.42 / 34.15** |
| 2026-08-04 Janino warm pilot | **29.46** |
| 2026-08-04 controlled v3 warm probe | **31.90** |
| **2026-08-05 corrected `--fast` pair** | **31.76 / 32.64** |
| 2026-08-05 immediately before early JSON restore | **33.22 / 32.98** |
| **2026-08-05 profile-stable JSON warm cohort (5 runs)** | **29.61 median (29.25--30.16)** |
| 2026-08-05 GraphicsLib lazy-normal diagnostic | **27.23** |
| **2026-08-05 deduplicated-Janino-pack cohort (5 runs)** | **25.58 median (25.08--25.80)** |
| **2026-08-05 prepared-audio path-index cohort (3 runs)** | **24.76 median (24.61--24.81)** |
| **2026-08-05 exact-target transformer cohort (5 runs)** | **24.12 median (23.93--24.43)** |
| **2026-08-05 resource-priority index cohort (5 runs)** | **23.68 median (23.39--24.35)** |
| **2026-08-05 WebP prefetch-tail cohort (5 runs)** | **23.03 median (22.90--23.34)** |
| **2026-08-06 direct trusted-texture read cohort (5 runs)** | **23.08 median (22.54--23.19)** |
| **2026-08-06 balanced LZ4 texture-storage cohort (5 runs)** | **23.15 median (22.59--23.21)** |

Earlier comparisons use two runs because single-launch variance on this profile is about **±1.4s**;
the new 29-second result uses five. Anything worth less than that noise cannot be measured by a
single game launch and has to be measured by replay instead.

**The repeated measured 33.0s goal is now met.** The corrected unattended pair reached 31.76s and
32.64s (32.20s median, 0.88s range). Both runs served 21,652 prepared textures, bypassed all 21,652
pixel conversions, hit all 228 Janino calls and all 1,469 keyed merged reads, and applied 22 exact
transformations with zero decline or contained failure. The benchmark had previously used `fast`
as a label for its old compatibility-texture subset without passing the CLI's `--fast` preset; that
semantic drift produced 54.23s and 58.13s diagnostic launches. The old subset is now named
`compatibility`, while `fast` invokes the exact installed-launcher preset and has the stricter
prepared-pixel acceptance gate. Evidence:
`docs/evidence/2026-08-05-startup-benchmark-fast-preset.md`.

**The 29-second startup goal is now met as a five-run cohort.** Exact startup callback probes found
that AshLib and GraphicsLib were rereading thousands of single JSON files before the persistent
single-JSON cache became eligible. The full profile identity and enabled roots are already fixed at
resource-loader entry, so eligibility now begins there while one-shot restricted reads still bypass
and consume their resolver state. A learning launch added 6,799 exact-profile trees; the next five
accepted warm launches measured 29.30, 29.61, 29.25, 30.16, and 29.92 seconds (29.61s median,
0.91s range), versus the immediately preceding 33.22/32.98 pair. Representative warm telemetry had
7,356 prepared single-JSON hits, 8,825 total tree restores in 217ms, zero cache failures or
collisions, 228/228 Janino hits, and 30 exact transforms with zero decline/failure. The next startup
target is GraphicsLib's remaining `autoGenNormalMap` path, about 1.7--1.8s across 6,184 calls; the
broader core-spec phase remains roughly six seconds. Evidence:
`docs/evidence/2026-08-05-profile-stable-startup-json-cache.md`.

That next GraphicsLib seam is now implemented and live-verified at the main menu. Exact bytecode
showed GraphicsLib decoding/uploading 6,184 current generated-normal PNGs and immediately unloading
and deleting every GL texture when `preloadAllMaps=false`. The exact compact replacement now fully
validates each contained PNG and installs GraphicsLib's own unloaded-entry state; any missing,
corrupt, unresolvable, or changed input executes the untouched load/regenerate path. The diagnostic
served 6,184/6,184 lazy hits with zero fallback, removed the 1.97-second texture-load seam, spent
1.13 seconds validating 215.6MB, and reached the menu in 27.23 seconds with ACTIVE health and 33
transforms. Whole-launch timing is not yet a cooled cohort. A following profiled pilot hit a native
HotSpot/Rosetta SIGSEGV during projectile loading, before GraphicsLib's callback, and is excluded;
the clean non-JFR retry then loaded a campaign, entered/exited a 500-opponent simulation, crossed
four GraphicsLib preload passes with live VRAM changes, and exited 0 with ACTIVE health, 50
transforms, and zero decline/failure. No GraphicsLib load/fatal error appeared. Evidence:
`docs/evidence/2026-08-05-graphicslib-lazy-generated-normals.md`.

The next core-spec probe split the 0.6--0.8s faction loader. Its 683,270 spec lookups cost only
39ms; the dominant 334ms is a tight candidate/tag expansion. Two exact caches were live-tested and
deleted because this corpus has far more distinct ids, tag combinations, and individual tags than
expected: they regressed the block to 806ms and 381--471ms. The observation-only faction split is
retained. Separately, the rules loader's five fixed `replaceAll` and five fixed `split` sites execute
205,686 times. Reusing five compiled patterns reduced that exact label 257 -> 202ms and the whole
rules loader 1.743 -> 1.682s. The equivalent `Matcher.replaceAll`/`Pattern.split(input, 0)` path is
exact-gated, composed into ordinary adapter launches, live ACTIVE, and full-verify green. Evidence:
`docs/evidence/2026-08-05-core-spec-faction-and-rules.md`.

Do not retry a generic quoted-number memo in bundled `JSONObject`: an exact live version reached
224,406/2,357 hits/misses but regressed weapon hydration, even after removing contended counters.
It and all plumbing were deleted; the same evidence note records both measured attempts.

The next startup recording exposed a new Rosetta-specific residual in Preflight itself: 452 sampled
ticks recomputed the payload checksum over 1.21 GB of prepared PCM on the game's two audio loader
threads, after the encoded input had already been hashed to select an exact content-addressed blob.
On Starsector's bundled x86 JVM, verified reads over a 268.6 MB real-corpus slice took
1.06--1.12s versus 0.132--0.135s for the same structural reader without the redundant payload hash,
an 8.0--8.4x difference. The runtime now uses the trusted reader by default, still matches embedded
source/decoder/policy identities to the lookup, and retains every bounded shape/EOF check;
`-Dpreflight.audio.verifyBlobChecksum=true` restores the hash. The same audit removed redundant
per-class hashes inside an already-checksummed Janino bundle. Focused tests and the exact-runtime
replay pass; a controlled live pair remains to establish wall time. Evidence:
`docs/evidence/2026-08-05-prepared-audio-trusted-read-benchmark.md`.

A prior controlled warm probe reproduced the
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
| crash-safe file-only logs | **0.249s** | included by `--fast`; unbuffered rolling file retained |
| `--quiet-logs` | **0.403s total / 0.154s beyond file-only** | merged in #315; replay + real smoke pass |
| tagged-tree rehydration for the four spec caches | **0.261s** | merged in #316; 394ms -> 132/134ms exact seam |
| persisted Janino complete maps | **15.650s direct aggregate / 5.37s whole launch** | exact full-profile identity; clean cold/warm live pilot; included by `--fast` |
| GraphicsLib compact startup replay | **3.038s exact callback** | clean live adapter application; PR #318 |
| GraphicsLib insignia manager cache | 4.40% of long-session game-thread samples is all GraphicsLib | exact per-render adapter built; combat pilot pending |
| GraphicsLib hot-settings cache | 7,621 hits / 3 misses / 1 invalidation | live exact adapter; Luna float stack removed |
| OpenAL streaming-source error order | 1 stale error recovered / 0 real generation errors | live exact vanilla repair; false 516ms retry removed |
| AI Tweaks target-selection range snapshot | 13,405 live selections; four derived calls removed; v2 removes per-candidate range boxing | v1 live; v2 offline/exact green, live FPS/allocation follow-up pending |

**File-only and quiet logs (implemented).** The launch emits 122,437 lines, 28,963 of them from `ScriptStore` on `Thread-4`
contending for log4j 1.2's per-append lock. Replayed from two threads on the game's own JVM and log4j
jar, the loading thread pays 0.491s as shipped, **0.242s** with the duplicate console appender
dropped, and **0.088s** when the file appender is buffered too. The crash-safe unbuffered mode is
included by `--fast`; it retains every synchronous rolling-file write and saves 0.249s. Route:
write a `log4j.properties` override and pass
`-Dlog4j.configuration=file:...`; the game's config is a classpath resource inside
`starfarer_obf.jar`, so log4j 1.2's `LogManager` honours the property without touching the jar.
Buffering can cost the tail on a hard crash, so only that extra 0.154s remains an explicit flag.
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

The latest combat profile exposes a separate GraphicsLib cost: three `LightShader` settings getters
perform LunaLib map/type lookups every render even though GraphicsLib already provides event-driven
`load()` and `applyChanges()` boundaries. The exact hot-settings adapter caches only those three
floats between those boundaries. Offline, exact installed-archive, and live gates pass; the live
follow-up served 7,621 hits with the expected three initial misses. The
preceding pilot also produced an audible pop at process startup and shutdown and now
has a concrete startup cause: vanilla checked a stale OpenAL error after source generation, logged a
false failure, and retried successfully. The exact error-order repair recovered that same stale
error in the live follow-up and main-menu music started on the first attempt; the shutdown sound is
not yet independently attributed. The latest complete log records orderly cleanup of both campaign
and main-menu streams with no OpenAL error or underrun at exit, so the remaining boundary pop is
tracked as an unlogged device-lifecycle event rather than folded into the stale-error repair.

The operator then localized that remaining pop to in-process music transitions: campaign load,
combat-simulation entry/exit, and leaving refit. Exact bytecode shows Starsector does compute a fade
to zero before deleting an old OpenAL source and starts new streams from zero gain. The bundled
macOS library is OpenAL Soft 1.23.1; upstream already added premature-stop click prevention in
1.21.1, so a blind native-library swap is not justified. A passive exact-class transition probe now
records fade requests, final scalar, and create/cleanup ordering without touching OpenAL. Its
offline and exact installed-archive gates pass; fold it into the next AI Tweaks live follow-up.

**AI Tweaks.** The v1 range snapshot is now live: one combat pilot applied the exact target and
recorded 13,405 selection objects. Its JFR allocation samples then exposed the next layer:
`SelectTarget.selectTarget` boxes its fixed search range through Kotlin's `Function2` boundary once
for every candidate ship or missile. Twelve samples at those exact boxing sites carried 27.3MB of
sampled allocation weight. `aitweaks-select-target-range-snapshot-v2` stores boxed engagement and
search ranges once per short-lived selection object and substitutes field reads at the two
primary/current sites and the candidate loop. Values, ordering, predicates, and changes between
selection events remain unchanged. The transform requires the exact three boxing shapes in addition
to all prior class/archive/loader/call-count gates. Executable behavior and exact installed-archive
verification pass. A non-JFR live pilot then installed and exercised v2 for **30,989** target
selections, completed normally, and reported all 33 transforms with zero decline or contained
failure. That proves live linkage/use compatibility, but not the disappearance of the sampled
allocation stack. See
`docs/evidence/2026-08-05-aitweaks-engagement-range.md`.

**Frame pacing.** The same clean pilot fixed the earlier access-control defect and produced the
first valid post-startup readout: campaign averaged 50.09 FPS with a 59.17 median, 12.30 FPS 1% low,
and 81.3ms p99. The user's observation that campaign play jitters just after save load and then
smooths out is present in the retained tail timestamps: bad-frame clusters are front-loaded and
nearly vanish later. Save completion at 46.219s is followed by deferred Combat Chatter data reads;
later clusters are near Nexerelin event/economy activity, but log adjacency is not causation.
Telemetry now splits the first 30 campaign seconds from later play. It also adds a combat-after-
campaign distribution because Starsector's title screen runs a background `CombatEngine` that had
contaminated the raw combat bucket. See
`docs/evidence/2026-08-05-frame-time-fps-reporting.md`.

**Post-startup JSON.** The cold/warm live gate is complete. The learning run captured 746 eligible
single-file JSON trees; the warm run served 746 of 748 eligible reads from the prepared artifact
(99.73%), with zero failures, collisions, or unstorable values. The full-data artifact is only
9,055,392 bytes. This removed repeat parsing and cut early post-save `LoadingUtils` lines, but did
not improve campaign warm-up: first-30-second average FPS was 47.34 learning versus 46.72 warm and
p95 was 45.0 versus 50.3ms. Those small operator-driven differences are noise, not a regression,
but they decisively provide no speedup claim. Retain the narrow fail-closed cache as repeat-I/O
avoidance; investigate campaign catch-up simulation next. The 116-line `RepTrackerEvent` burst
occupied only about 17ms, while Nex fleet/route/economy/mission activity continued throughout the
bad interval. The opt-in frame pilot now also exact-times six initial inclusive seams: Nex fleet-
pool advance, route spawn/despawn, resource-pool update, diplomacy advance, and vanilla reputation
and economy-fleet advances. It retains threshold counts and 32 slowest end timestamps using fixed
primitive arrays; normal and exceptional exits are covered. Synthetic execution and all six exact
installed-archive transforms pass. The live run completed normally: route
spawn/despawn reached 35.155ms inside a 50.834ms frame and diplomacy advance reached 36.253ms
inside a 53.250ms frame. The other four seams were smaller, and most frames over 100ms contained
none of the six timed calls. Route and diplomacy own two real medium hitches, not the general tail.
The next opt-in layer now hash-pins vanilla `CampaignEngine.advance` and exact-times its managers,
memory/factions, locations, and campaign help. Both persistent and transient engine-script loops
also group inclusive time by concrete `EveryFrameScript` class through session-scoped `ClassValue`
state. Its first live pilot completed normally and measured the campaign at 52.76 average FPS,
59.52 median, and 15.06 FPS 1% low. The first 30 seconds were materially worse (46.10 average,
9.15 FPS 1% low) than later play (55.47 average, 20.45 FPS 1% low). Location advancement consumed
19.04s of inclusive CPU and economy 11.49s. Stellar Networks' updater had a 131.03ms call inside a
143.26ms frame; MagicLib's bounty board had a 50.87ms call inside a 66.43ms frame. A deeper opt-in
probe attributed location entity/script calls by concrete class and split economy into its
location-map, stepper, and market-advance seams. Its live run completed normally: 2.12 million
market advances consumed 15.11s, about 89% of the 16.99s economy total, while 232,195 vanilla
`CampaignFleet` advances consumed 10.56s. The broad entity timers themselves covered tens of
millions of tiny calls, so those FPS numbers are diagnostic-only. Active/paused entity timing is
now sampled 1-in-64, and an exact Market/CampaignFleet drill-down samples market plugin seams
1-in-32 while measuring fleet AI on every call. Its live pilot completed normally with 43 applied
transforms and zero declines/failures. It counted 483.77 million commodity-stat accesses and 120.94
million event-mod accesses; their approximately 43ns sampled means are below trustworthy timer
resolution, so their extrapolated totals are instrumentation-inflated and are not speed claims.
Reliable enclosing totals put `ModularFleetAI` at 2.783s, inherited `BaseCampaignEntity` work at
1.462s, and `CampaignFleetView` at 772ms. The first two exact behavior shortcuts now skip
`BaseCampaignEntity.runScripts`' defensive `ArrayList` allocation only for an empty authoritative
list and reuse `CampaignFleetView.advance`'s already-live sorted-member snapshot instead of asking
for the identical snapshot twice. Non-empty script lists enter the renamed unchanged vanilla body;
both transforms exact-pin the shipped owners and archive and have a kill switch. Synthetic woven
execution, exact installed-archive checks, and full `mvn verify` pass. The first live gate exited
normally and installed the fleet-view shortcut, but telemetry caught `BaseCampaignEntity` being
claimed first by the existing entity-index target. The production entity-index path now composes
both disjoint rewrites under the original exact identity, and the installed-archive test proves both
hooks coexist. The corrected live gate exited normally with ACTIVE health and both hooks installed:
15,402,921 empty script-list calls skipped the defensive snapshot while 286,218 non-empty calls kept
the unchanged vanilla path, a 98.176% fast-path rate. Campaign averaged 51.03 FPS with a 59.17 median
and 14.03 FPS 1% low; first-30-second versus later averages were 45.11 and 53.28 FPS. This proves the
allocation-volume reduction, not an FPS delta, because the route was not a controlled A/B. The run
also caught the disabled location timer composing behind the entity index; filtering a campaign
timing plan now shuts its runtime gate as well as removing its targets. The next exact market
candidate wrapped both defensive snapshots in `Market.advance`. Its clean live gate exited normally
with ACTIVE health and all four heavy campaign timers disabled: conditions were empty only 416 /
1,368,227 times (0.0304%), while industries were empty 205,888 times (15.0478%). That rejects an
empty-only condition branch. The final form instead preserves each stable snapshot as the source
`toArray()` plus a private array iterator, omitting vanilla's otherwise unused `ArrayList` wrapper
for every non-empty traversal and all three objects when empty. The observed route implies about
3,149,062 avoided heap objects. Cross-frame caching is unsafe because `getIndustries()` exposes the
mutable backing list directly. The exact installed class composes with the opt-in timer and full
`mvn verify` passes. This is allocation evidence, not an FPS claim.
The latest short campaign gate still counted 640,354 `Market.advance` calls and 61,473,984
commodity event-mod passes. Exact bytecode passes the original frame amount to plugins but advances
the four temporary commodity stats with converted days and reapplies every event mod unconditionally.
Before considering a zero-delta shortcut, the opt-in market attribution plan now reports exact zero
versus nonzero market-advance amounts. This counter-only probe changes no behavior, exact-transforms
the installed class, and passes full `mvn verify`. **Next step: spend time both paused and unpaused in
one live profile and inspect `zeroMarketAdvances`/`nonZeroMarketAdvances`.** That live run rejected
the shortcut: just 218/737,211 market advances were exact zero (0.030%), so no market behavior was
changed. The same profile exposed `HyperspaceAutomaton.getLiveCountAround` at 16/1,325 campaign
samples. An offline-green exact API-class rewrite hoists its four clamped bounds and column lookup
while preserving every cell/edge rule. Exhaustive rectangular-grid equivalence and the installed
class pass. On five fresh game JVMs the exact copied operation fell from 9.224-11.808ns to
5.605-6.558ns (37-52%), and full `mvn verify` passes. **Next step: live-profile the automaton
stack and confirm normal hyperspace behavior plus removal of the old leaf.** The live profile has
now completed normally: ACTIVE health, 47/47 transforms, zero fallback/failure, and the automaton
hook installed and exercised beneath vanilla and More Planetary Conditions terrain. JFR cannot
separate the inlined replacement from its caller, so the operation benchmark remains the speed
evidence rather than a live FPS claim.

That load displayed a false Logistics Notifications 1.7.1 fuel alarm at 0.0 light-years despite a
full tank. Local source proves a mod race: `_fuelLYRemaining` starts at zero, its tracker does not
run while paused, and the paused alarm's first 0.9-second path skips its normal pause guard. A new
exact `LogNot.jar` repair calls the mod's own `updateFuelLY()` once before the tracker constructor
returns, preserving all of its fuel math and later updates. Synthetic execution and the exact
installed-jar transform pass. **Next step: run one ordinary load and confirm the adapter installs
and the false notification is absent.** The same-save live acceptance run then remained in campaign
play for over two minutes, briefly entered combat state, and exited normally with no false warning.
Health was ACTIVE, 48/48 transforms applied, no fallback/failure, and the exact Log Not repair
reported installed.
The next exact `ModularFleetAI` candidate removes another disabled-observer cost. Vanilla builds a
dynamic `Ability [id]` profiler label on every ability advance even though its profiler is normally
off and immediately returns. Plan `vanilla-fleet-ai-profiler-label-v1` substitutes an interned
constant only while the profiler is disabled. It exact-transforms the profiler's real toggle to
publish state, requires both exact owners before taking the shortcut, preserves the complete label
when profiling is enabled, and delegates on every partial-install or drift case. Synthetic
off/on/off execution, kill-switch and exact installed-archive tests pass, as does full
`mvn verify`. Its first live attempt matched both exact owners but retained vanilla because the
separate plan-availability registry entry was missing; this correctly failed closed and explains
the run's two unavailable plans. The entry now exists with a regression test. A later clean combined
gate installed both owners and avoided 100,354 dynamic labels with zero delegation while profiling
remained disabled. The earlier attempt also live-gated the final compact market iterator: all three
maintenance hooks installed, 5,680,328 total market snapshots exercised, zero contained failures,
and normal exit.
Offline allocation analysis then exposed `Memory.advance(float)` allocating an expiry-list iterator
and requirement-map values view/iterator even when those private collections are empty. A prior
campaign JFR attributed 50 allocation samples / about 114.3MB of sampled weight to that method,
mostly the targeted `ArrayList$Itr`; this is statistical weight, not literal allocated bytes. A
smaller linked-key iterator belongs to active nested requirement scanning and remains. The exact
maintenance adapter now guards each iterator site after vanilla's restoration, pause, clock and day
conversion work. Empty expiry lists jump to the requirement gate and empty requirement maps return;
every non-empty loop, including `Iterator.remove`, remains byte-for-byte in place. Synthetic
empty/non-empty execution, exact installed-class transformation, the existing kill switch, and full
`mvn verify` pass. The combined live gate skipped 4,526,048 empty expiration iterators and 4,604,109
empty requirement iterators while retaining 343,913 and 265,852 non-empty paths respectively.
The later v4 allocation profile found a separate per-`Memory` `replaceIdsWithEntities` campaign-
restore seam: 24/1,887 campaign execution samples and 23.49MB of sampled allocation weight. Exact
bytecode takes a stable `new ArrayList(map.keySet())` snapshot, then compiles literal regexes after
already proving `enRef_` or `mRef_` prefixes. An offline-green extension preserves the stable key
snapshot with `toArray()` plus the existing private iterator, removes only the unused wrapper, and
replaces those anchored literal removals with `substring(6)`/`substring(5)`. Synthetic execution and
mutation-during-traversal, exact installed-class, and full `mvn verify` tests pass; any class or
shape drift retains vanilla. The live gate exited ACTIVE with 45/45 transformations and exercised
69,937 empty plus 9,313 non-empty traversals. At the exact helper stack, sampled allocation fell
from 11 events / 25.49MB to one event / 1.97MB; every regex and `ArrayList$Itr` event disappeared.
The survivor was a required vanilla `HashMap.resize` during a real update. This is allocation-stack
evidence, not an identical-workload load-time claim.
The adjacent paused-economy path also constructs `new ArrayList(market.getConditions())` for every
market. Existing JFR samples assign nine events / about 25.2MB of sampled weight to that unused
wrapper, while the separate 45.0MB source-array site is necessary for callback-mutation isolation.
The exact `Economy` adapter now uses the same stable `toArray()` plus private iterator as ordinary
market snapshots, omitting only the wrapper. It deliberately retains the outer virtual
`getMarketsCopy()` call. Synthetic and installed-class shapes, composition with the opt-in economy
timer, full `mvn verify`, kill switch, and separate telemetry pass. The combined live gate compacted
270,072 non-empty paused-condition snapshots. It exited normally with ACTIVE health: 40 applied
transformations and zero decline, unavailable plans, or contained failure.
The adjacent exact `BaseLocation.advanceEvenIfPaused` path makes one defensive entity copy used for
two passes and one defensive script copy used for one pass. A new maintenance composition retains
the two stable source arrays and three independent traversal cursors but omits both unused
`ArrayList` wrappers; empty lists use shared array/iterator objects. It pins the existing exact
`BaseLocation` identity and reviewed producer/local/iterator shapes, composes after the entity index
and before optional location timing, and shares the maintenance kill switch. The same transform now
also removes the wrappers from the ordinary active method's three stable snapshots: campaign
entities, location tokens, and the conditional per-eligible-fleet engagement scan. Every source
array, callback boundary, iterator count, and conditional remains. Synthetic snapshot semantics and
exact installed-class composition pass, as does full `mvn verify`. It is launch-free verified and
reports separate empty/non-empty counts for all five snapshot kinds. The subsequent live gate exited
normally with ACTIVE health, 37 applied transforms, and zero unavailable plans, declines, or failures.
It exercised 25,529 paused entity, 25,529 paused script, 17,820 active entity, 17,820 active token,
and 902 conditional engagement captures. Empty scripts (16,766) and tokens (12,495) made the shared
empty path material. The route avoided 87,600 wrappers plus 58,522 empty-path arrays/iterators,
about 146,122 heap objects under the shipped `ArrayList` implementation. This is allocation-volume
evidence rather than an FPS A/B.
The earlier run also logged
28 caught Industrial
Evolution Codex NPEs from
synthetic markets with null location/system; treat that as a separate exact compatibility-guard
candidate. Do not add overlapping totals to a speed claim.
See
`docs/evidence/2026-08-05-campaign-engine-call-times.md`.

**Core sound resource fallback.** The same live run's final 1 MiB console ring contained 731
repeated core-sound load errors, including 403 for `laser_loop.ogg` and 244 for
`maneuvering_jets_loop.ogg`; prepared-audio telemetry counted 6,242 null-input failures. The files
exist. Exact bytecode shows all three sound-store readers passing raw `sounds/...` paths to
`Class.getResourceAsStream`, which resolves them under package `sound` and misses
`sound/sounds/...`. A hash-pinned adapter now preserves the original relative lookup and, only
after a null result, retries the same relative path from the classpath root. Exact installed-JAR
transformation and runtime fallback tests pass. The next normal live run completed two simulations with
zero prepared-audio failures and none of the retry errors, but the intermittent recovery path did
not recur (`lookups=0`), so that is a no-regression result rather than causal proof. The exact
installed-class test now deterministically invokes the real recovery method and proves one root
fallback hit without initializing OpenAL. See
`docs/evidence/2026-08-05-audio-classpath-root-fallback.md`.

**Janino.** `codex/janino-profile-cache` wraps the exact complete-map `generateBytecodes` seam and
leaves Janino definition intact. The context content-hashes all ordered mod archives, loose Java and
class providers, core JARs, the game/Janino JARs, and bundled JVM modules, plus compiler/loader/
protection policy. Hashes overlap through `ProfileIdentityContext`; archive order is recovered from
the in-memory resource index rather than decoding a full class-entry index. Fast Rendering's custom
system classloader is detected and owns this seam, so Janino preparation is suppressed there while
independent Preflight caches remain available.

**Latest startup work.** Persistent rule-token shapes reduced the exact warm tokenizer from 600ms
to 89ms and the whole rules loader from 1.688s to 1.262s. GraphicsLib's generated-normal validation
journal reduced complete PNG checking from 1.196s to 197ms while retaining all 6,184 lazy hits.
The warm journal path then dropped its redundant preliminary regular-file stat: the authoritative
attribute capture still proves non-symlink regular-file identity, size, and nanosecond mtime. A
clean unattended menu gate retained 6,184/6,184 hits with zero fallback and exactly 6,184 metadata
probes; the exact validation seam fell from 197ms to 131ms and the menu marker arrived at 25.40s.
Adapter source binding had another Rosetta-only repetition: the game JVM rehashed 11 exact source
JARs (16.9MB) on every launch even when their filesystem identity, size, and nanosecond mtime were
unchanged. A fail-open advisory journal now retains those complete SHA-256 answers. The population
gate measured 98ms hashing; the adjacent warm gate served all 11, hashed zero bytes, and retained
all 33 transformations with zero decline/failure. Their menu markers were 24.96s/25.07s. Any
metadata mismatch or malformed journal rehashes content, and the independent target-class/source/
loader gates remain.
The next exact callback profile found AshLib scanning all 8,622 variant ids for each of 547 modular
or station hull lookups. A callback-scoped index now preserves first-match and fallback ordering;
the live gate served all 547 lookups with zero failure and reduced that exact seam from 160ms to
19--20ms. The same scope now reuses the pinned render-info class's private, non-escaping, read-only
hull JSON objects: 17,051 hits removed 17,433 `loadJSON` calls and reduced that exact seam by 65ms.
MagicLib's paintjob loader was the next callback reviewed. Its 437 rows made 874 restricted
`loadJSON` attempts for optional `.paintjob` files, and every one was absent. An exact, path-confined
shortcut now proves those misses from the current mod root and otherwise invokes the original API
unchanged. Clean live gates kept the same 437/775 ship/weapon paintjob counts, reported 874 proven
misses with zero delegation/failure, and reached the menu normally. Nearby callback timings support
only a modest tens-of-ms claim because the 0.7--0.95s callback is noisy.
The remaining MagicLib boundary is now exactly attributed too. `MagicPaintjobManager` accounts for
about 0.47--0.56s, including five weapon loaders at 0.24s; one first weapon call alone is 0.22s.
CSV is 0.04s, 4,374 JSON field accesses are below 0.01s, and 6,578 Kotlin string operations are
about 0.01s. Bytecode and that one-large-first-call shape point to required Magic/Kotlin class
definition and initialization. Do not add another JSON/string cache here. Deferring the manager
would move the same work to the first refit/paintjob interaction and was not retained.
The rules regex follow-up retained a narrower fixed-operation fast path. Of the 205,686 regex
operations, 105,295 are only CR/LF removal or trailing default-regex whitespace removal with an
empty replacement. Equivalent character scans reduced the exact regex block from 207ms to 130ms
and the complete rules loader from 1.282s to 1.214s. A custom literal splitter was tested first,
raised the block to 267ms, and was deleted; keep Java's reused `Pattern.split`.
The fast preset now also drops log4j's duplicate console appender while retaining unbuffered,
synchronous rolling-file writes. The installed-log4j replay prices that crash-safe change at
0.249s (0.491s to 0.242s). An unattended 83-mod gate reached the menu marker at 25.32s, applied all
38 exact transformations without fallback, ended the 6.3MB log on a complete newline, and left no
JVM. Buffered `--quiet-logs` remains explicit because only it can lose the final 64KiB on a hard
crash.
The fast preset's two rule-expression caches shared one exact class, and ordinary target selection
was installing only the token memo even though the report said the command artifact was loaded.
The token-target branch now composes the command shortcut too. An adjacent exact control reduced
the 25,762-call command phase from 524ms to 376ms and its containing rules loader from 1.302s to
1.122s. The live gate matched all 47 ordered packages, served all 671 prepared winners, retained
all 62,340 token hits, and had zero miss/disagreement/failure. A pre-fix five-run real `--fast`
cohort was 25.71/25.72/26.57/26.06/26.77s; the rising tail is fanless thermal drift, so do not use
its median as a clean before/after. See
`docs/evidence/2026-08-05-rule-command-cache-composition.md`.
The original Janino cache stored the complete generated-class map independently for each request.
The live 228-bundle corpus contained 149,732,372 expanded bytecode bytes but only 1,006,460 unique
bytes across 280 classes: 148.77x duplication with no same-name conflict. A context-bound SPJP pack
now stores each validated classfile once and each request as class indexes while still returning an
independent mutable map and byte arrays. Corrupt, mismatched, incomplete, or unwritable packs fall
through to the existing exact bundles/vanilla; partial packs expand atomically at shutdown and
all-hit launches do not rewrite them. The artifact is 1,183,935 bytes versus 145.96MiB of bundles.
One adjacent live gate reduced the exact warm Janino seam from 1,501ms to 29ms. A following ordinary
five-launch fast cohort measured 25.08/25.58/25.45/25.79/25.80s (25.58s median); every run served all
228 requests from the pack in 31--38ms with zero miss, fallback, error, or rewrite. Its rising thermal
tail and the intervening rule-command repair prevent a clean whole-launch attribution. The 25.08s
cool run makes sub-25 plausible, not yet established. Full verification and a third-fresh-loader
installed-Janino outer/nested replay pass. See
`docs/evidence/2026-08-05-janino-deduplicated-pack.md`.
The last text-backed spec artifact was then removed. The prepared rules CSV still stored a 12MiB
`JSONArray.toString()` result and paid `new JSONArray(text)` on its one warm hit. SPRC v2 now carries
the same production tagged tree used by the other spec caches and rebuilds a fresh independent game
array through `GameJson`; malformed data or a non-array root falls through to vanilla. The first
live launch rejected v1, retained the untouched loader, captured its authoritative result, and wrote
v2 atomically. The adjacent warm gate hit once with zero fallback/rewrite and reduced the exact
rules reconstruction from 194ms to **6ms (-188ms, -96.9%)**. The artifact is 8,810,607 bytes. Both
launches applied all 38 transforms with zero decline/failure and stopped normally; full verification
passes. The prior coolest ordinary launch was 25.08s, making 24.89s theoretically supported after
this exact reduction. A three-minute-cooled non-probed follow-up reached 25.092602s with the rules
hit in 9ms, exit 0, and zero transform failure: just 93ms short, but still not a sub-25 claim. See
`docs/evidence/2026-08-05-tagged-rules-csv.md`.
Prepared audio's final Rosetta-side corpus pass is now gone too. The bake persists a checksummed
logical-path-to-source-hash manifest; before launch, native Preflight verifies the exact resource
profile, game/decoder identities, winning providers, metadata, and all 133.3MB of source content in
55--73ms wall. The exact sound-store callsite then uses its existing filename to select the same
content-addressed blob without hashing the input under Rosetta. Every mismatch retains the untouched
stream and falls through to the prior content-hash/vanilla path. A live gate served 2,049/2,050
decodes by path with one hash fallback and zero failure. The following ordinary cohort measured
**24.81/24.61/24.76s (24.76s median, 0.20s range)**, establishing repeatable sub-25 startup for the
first time. Full verification and the exact installed sound-store rewrite pass. See
`docs/evidence/2026-08-05-prepared-audio-path-index.md`.
Cache space has now been measured rather than treated as one undifferentiated number. The active
cache is 6.9 GB: 5.0 GB exact raw prepared pixels, 1.1 GB effectively incompressible PCM, and 438 MB
generated bytecode. A 40.0 MB SPFT sample fell to 8.9 MB with zstd-1 while a 44.8 MB SPAU sample
remained 44.4 MB, so lossless compact textures are plausible and compressed prepared audio is not.
Separately, `cache prune` now identifies the exact live Janino context and proves individual bundles
byte-identical to its deduplicated pack before planning their removal. The real dry run finds **505
MB / 693 files** reclaimable without losing a current cache hit; it remains preview-only without
`--yes`. See `docs/evidence/2026-08-05-cache-space-budget.md`.
Ordinary enabled launches also no longer build the adapter probe's broad game-class inventory.
Exact registry targets and specialized compatibility observers are sufficient for normal use;
`--adapter-probe` retains the broad scan for discovering renamed targets after patches. The live
gate reduced parsed classes from 2,612 to 38 (**98.5%**) while applying all 38 exact transformations
with zero decline/failure. A following five-run, one-minute-cooled cohort measured
**24.41/24.12/23.93/24.43/23.98s (24.12s median, 0.50s range)**; all launches stopped normally and
all exact transforms applied. This was not a shuffled A/B against the 24.76s cohort, so attribute
the eliminated parsing/CPU work, not the full median difference, to the change. See
`docs/evidence/2026-08-05-exact-target-transformer.md`.
The next current JFR hotspot was vanilla resource reprioritization, not another read. The exact
`ResourceLoaderState.init` bytecode collected 4,479 priority entries from 55,359 resources, then
used `ArrayList.removeAll`, performing a linear priority-list membership scan for every resource
before prepending the unchanged priority list. An exact remove-then-prepend rewrite indexes only
that membership test. A diagnostic same-launch control measured **558.257ms vanilla versus
4.148ms indexed (-554.109ms, -99.3%)** and compared the complete ordered outputs with zero mismatch.
The following one-minute-cooled ordinary cohort measured
**23.39/23.63/23.93/24.35/23.68s (23.68s median)**; each indexed call took 2.019--2.184ms and every
launch stopped normally with zero transform failure. The adjacent prior cooled cohort was 24.12s
median, so the 0.44s wall shift is consistent with the exact seam reduction but is not a shuffled
paired A/B. See `docs/evidence/2026-08-05-resource-priority-index.md`.
The last visible texture-prefetch tail was one WebP resource. Preflight's arm64 preparation JVM had
no reader, while Starsector initialized its old x86-native WebP provider under Rosetta and left the
main thread polling the one-thread queue 117 times (about 1.27 corrected wall seconds). The
preparation CLI now carries a pure-Java WebP ImageIO reader; canonical ARGB hashes matched
Starsector's decoder for both simple lossless VP8L assets. The enabled extended lossy-alpha WebP
did not match and is deliberately omitted from the prepared manifest, leaving the game's decoder
authoritative. A deep real preparation validated the other 32,919 entries with one intentional
unsupported fallback. The final live profile moved `prefetchKept` 1 -> 0 and main-thread prefetch
sleeps 117 -> 0 while still reporting that one non-startup fallback. The following
one-minute-cooled cohort measured
**23.24/23.34/23.03/22.90/22.99s (23.03s median, 0.44s range)**, versus the adjacent prior 23.68s
median. Every run applied all 33 exact transformations and stopped automatically. See
`docs/evidence/2026-08-05-webp-prefetch-tail.md`.
The trusted SPFT reader still copied every prepared payload after reading the complete file. It now
reads fixed metadata and then the pixels directly into their final adopted array. Across all 30,638
unique real blobs (5.33 GB of pixels), alternating fresh-process passes moved a warm complete-cache
read from 1.831s to 0.815s and removed one manifest-sized transient allocation. The cooled live
cohort measured **23.19/22.88/23.08/23.09/22.54s (23.08s median)** versus the adjacent 23.03s, so
this is retained as a CPU/allocation/thermal-headroom win with no claimed median wall shift. All
runs remained exact and fail-open. See
`docs/evidence/2026-08-06-trusted-texture-direct-read.md`.
The exact texture cache now has explicit storage policies. `balanced` is the default and uses
pure-Java lossless LZ4 in the existing checksummed SPFT envelope and a distinct blob
name. On the full real corpus it stores 2.201GB instead of 5.335GB, saving 3.13GB. A deep preparation
validated 32,919 entries with zero failure and one already-known fidelity-gated WebP fallback. The
live gate served 15,469 prepared textures with zero retained prefetch work or transform failure, and
the cooled balanced cohort measured **23.15s median (22.59--23.21)** versus the adjacent raw 23.08s.
That 0.07s difference is below noise. Switching updates the manifest; the existing conservative
`cache prune` can then reclaim the unreferenced representation explicitly. See
`docs/evidence/2026-08-06-balanced-texture-storage.md`.
Balanced serving now also reuses a bounded compressed-input scratch per loader thread. The full
installed LZ4 corpus previously allocated and discarded 2.20GB of encoded arrays on its way to the
required 5.33GB of final pixels. On the bundled JVM, an alternating 5,000-blob replay moved from
629.025ms to 607.822ms median (**-3.4%**) and eliminated 359.9MB of transient allocation; complete
old/new replay produced the same checksum for all 30,638 blobs. The final live gate served 15,469
prepared textures with zero corruption, quarantine, decode fallback, or internal failure and
reached the menu in 21.61s. Raw/`fastest`, checked tooling reads, formats, and identities are
unchanged. Treat this as allocation/GC/thermal headroom, not a whole-launch timing claim. See
`docs/evidence/2026-08-06-balanced-texture-scratch.md`.
LunaLib 2.0.5 and Nexerelin 0.12.2b also run forks of the same asynchronous version checker over
the same 74 mod URLs. Exact, source-bound adapters now share only successful HTTP(S) response bytes
within one game process; callers receive independent streams, failures retry independently,
non-HTTP URLs bypass, and nothing persists across launches. The unattended profiled gate installed
both targets and observed 31 reused responses against 41 network fetches before main-menu shutdown,
holding 16,010 bytes. It exited ACTIVE with zero decline/failure. This is a measured network/CPU/
thermal-load reduction, not a wall-time claim. See
`docs/evidence/2026-08-06-version-check-response-dedup.md`.
The fresh profile also exposed Preflight's own generic resource-path normalizer under the hull,
weapon, projectile, variant, and texture caches. Its regex drive check and split plus deque/list/join
pipeline are now one validation scan; already-normalized paths return unchanged and only paths that
actually need slash/dot cleanup allocate a rebuilt string. A 20,000-path equivalence corpus includes
Unicode and rejection cases. On the bundled x86 JVM the exact eight-path microbenchmark improved
from a 371.85ns median to 54.03ns (**6.88x**). The following live gate remained ACTIVE and reduced
main-thread normalizer samples 8 -> 3; every survivor was only the unchanged `Locale.ROOT`
lowercase call, while the removed regex/container stack fell to zero. Its 23.60s sampled wall time
is diagnostic, not an attributed launch claim. See
`docs/evidence/2026-08-06-resource-path-normalization.md`.
The remaining regex-backed path seams are now allocation-light too. Across 22,128 real cached JAR
entry names, the shared scanner measured 440.09 -> 51.73ns/name (8.51x); across all 12,584 prepared
spec keys, the exact drive scanner measured 350.12 -> 100.51ns/key (3.48x). More importantly, ten
warm native cached classpath-index builds moved from a 371.05ms median to 337.46ms, saving 33.59ms
(9.1%) without changing any cache format or identity. An adjacent schema-scoped projectile-number
pretyping experiment was rejected and deleted: exact installed-json replay regressed 4.596 ->
5.350ms. Do not retry reflective post-decode number promotion. See
`docs/evidence/2026-08-06-remaining-path-regex.md`.
The prepared-audio runtime has now removed the redundant heap-copy chain that remained after trusted
reads. On the exact game JVM, the same 519-blob/297MB subset moved from a 394.604ms legacy median to
218.149ms direct (**1.81x, -176.455ms, -44.7%**). Complete checked-versus-trusted equality passed
for all 2,020 distinct installed blobs and 1,212,686,724 PCM bytes. For the live launch's logical
1,226,415,962 served PCM bytes, four eliminated PCM-sized intermediates represent about 4.91GB of
avoided transient heap traffic; the final direct OpenAL copy necessarily remains. The checked
tooling reader and public defensive model boundary are unchanged, while malformed/changed blobs
still fail open. The unattended live gate reached the menu in 22.36s, served 2,049/2,050 decodes and
1,226,415,962 PCM bytes with the expected one fallback and zero failure, applied all 40 exact
transformations, stopped cleanly, and left no JVM. It is an encouraging single diagnostic, not a
new startup cohort or attributed wall-time claim. See
`docs/evidence/2026-08-06-prepared-audio-direct-read.md`.
SpecStore's fixed smart-quote cleanup is now allocation-light too. Its exact normalizer compiled and
ran two constant regexes for every one of 28,624 values in the live corpus. A hash- and shape-pinned
rewrite uses equivalent linear scans, composes with the prepared-variant cache on the same class,
and reports its own count. On the bundled game JVM, one million two-regex normalizations moved from
1162.118ms to 288.858ms median (**4.02x, -873.260ms**), with identical output. The final unattended
gate served 57,248 fast replacements, hit all 5,573 prepared variants, reached the menu in 21.93s,
applied all 40 transformations with zero decline/failure, and stopped normally. This is a CPU and
allocation result rather than an attributed whole-launch timing claim. See
`docs/evidence/2026-08-06-spec-store-quote-normalization.md`.
GraphicsLib's compact replay was also tested with its already-completed material and surface
branches skipped. This removed exactly 18,672 texture-data lookups, but two fresh-process gates
measured the 9,336-call replay at 0.35s and 0.30s versus the retained 0.28s; the complete
auto-generation block likewise rose from 0.61s to 0.68--0.70s. The exact exception-safe transform
was deleted. Do not repeat this branch-per-request approach.
Nexerelin's remaining 0.6--0.9s callback is now exactly attributed rather than guessed at. Across
75 faction configs, cached merged reads cost only 0.11--0.15s and 6,655 JSON accesses only
0.06--0.09s. About 0.30s comes from the first missing `doesVariantExist` call constructing the
complete `CampaignEngine`. A temporary exact guard proved that this side effect is required:
Nexerelin immediately dereferences `Global.getSector()` while loading relationship and faction
data. The guard was deleted. Do not retry this as a lookup cache; removing the cost requires a
different Nexerelin initialization boundary or a safe optimization inside campaign-engine
construction. The retained breakdown is measurement-only and opt-in.
That constructor was split once. Sector/factory publication plus combat-engine initialization was
only about 20ms; the remainder was distributed first-use construction, led by Hyperspace,
`FactionManager`, and `CampaignClock` at roughly 40ms each in one sample. The constructor drill was
removed after attribution because its extra labels overflowed the general startup report. Do not
trade this startup cost for a later first-campaign-use hitch.
`--fast` now also omits the exact reviewed per-file projectile, weapon, hull, and variant INFO
messages while preserving warnings, errors, summaries, and `Skipping variant [...]`. The clean live
gate removed 12,584 events / 1,560,182 formatted bytes and reduced the four affected loader calls by
106ms in aggregate (545->501, 481->452, 420->415, and 300->272ms). It reached the menu in 22.77s and
stopped normally. Treat this as a measured CPU/allocation/log-volume result, not an exact causal
whole-launch claim. The transform is atomic per class and declines on instruction drift. See
`docs/evidence/2026-08-06-concise-asset-progress-logs.md`.
The texture cache's configure-time full provider validation is now the immutable launch snapshot
under `--fast`. This removes the redundant winner `toRealPath`/`readAttributes` round trip for each
of 15,469 served textures. In the adjacent live pair the exact `load()` seam fell 4,962->4,559ms
(-403ms), with identical 15,469 hits / 2,116,422,119 pixels / three known misses and zero failure.
Whole launches were 22.77s and 22.35s; use the seam delta as the causal result. A source edit made
after premain validation intentionally takes effect next launch. `--recheck-texture-sources` and the
existing content-hash diagnostic restore the stronger live checks. See
`docs/evidence/2026-08-06-validated-texture-index-snapshot.md`.
Balanced prepared textures are now also served from one profile pack instead of 15,469 loose-file
opens. The reviewed 2.204GB pack holds 30,638 distinct blobs for 32,919 manifest entries, validates
as an unchanged hit in 67ms, and fails open to the authoritative loose blobs on any pack problem.
The runtime writes a checksummed successful-access-order hint at normal shutdown; the next prepare
lays those blobs out first and appends unseen blobs in stable logical order. Missing/corrupt hints
are ignored. The final clean learned-order gate reached the menu in **18.80s** with 15,470 pack
reads, identical 15,469 game-facing hits / 2,116,422,119 bytes / three known misses, zero pack
failure, and a 1,632ms exact load seam versus 4,559ms for validated loose blobs (-2,927ms, -64.2%).
The adjacent logical-order learning run was already 19.12s; treat whole-run differences as
supporting single diagnostics, not a cohort. A 16MiB read-ahead window was rejected and deleted
after reading 132.2GB and regressing wall time to 35.59s. See
`docs/evidence/2026-08-06-packed-texture-store.md`.
Dynamic AppCDS was then tested against the real bundled JVM rather than inferred from the existing
small capability probe. Plain dumping refuses a Java agent; HotSpot's diagnostic agent override is
explicitly testing-only and forces remote verification during dumping. Starsector's shipped
obfuscation immediately fails that verification (`Illegal field name "for.Object"` in
`StarfarerSettings`) before resource loading. No transform or game state was reached and the test
archive was deleted. Do not retry AppCDS on this game build; the gate can be revisited only when the
shipped classes are verifier-valid and the exact JVM supports production agent-assisted archives.
See `docs/evidence/2026-08-06-appcds-obfuscated-class-gate.md`.
A subsequent exact-profile GraphicsLib experiment recorded and replayed 47,339 calls through the
mod's public mapping API. It was rejected: reflection expanded the generated-normal path from 0.25s
to 1.20s and the callback from 1.03s to 1.70s, with no attributable wall-time win. The implementation
and its 4.19MiB artifact were deleted. Do not retry public-call traversal replay; see
`docs/evidence/2026-08-06-graphicslib-traversal-replay-rejected.md`.
The shared vanilla text reader is now allocation-light as well. Instead of a fresh 1MiB buffer,
`StringBuffer`, final copy, and CR-removal regex per input, the exact LoadingUtils rewrite reads once,
compacts CR bytes in place, and decodes once. Across all 17,666 installed spec files on the bundled
x86 JVM, the median moved 761.978->276.073ms (-485.905ms, -63.8%, 2.76x) with exact text equality.
The clean live gate used it 1,300 times for 3.12MB in 150ms, applied all 40 transforms without
failure, preserved every texture/normal cache count, reached the menu in 18.88s, and stopped
normally. The exact corpus result is causal; the single wall time only supports no regression. See
`docs/evidence/2026-08-06-loading-utils-reader.md`.
Balanced texture storage now skips LZ4 only where it is objectively ineffective. The installed
profile selects 1,476 raw and 29,162 LZ4 blobs, growing the 2.204GB pack by only 9.78MB. Seven
alternating fresh bundled-JVM replays of the exact 14,774-entry startup order moved the pack-read
median from 1248.789ms to 1190.886ms (-57.903ms, -4.6%) with identical 2.074GB output and checksum.
The live mixed-pack gate preserved every texture/cache count, applied all 40 transforms without
failure, reached the menu in 19.30s, and stopped normally. Storage-policy switching also preserves
learned pack order now: observations match by codec-independent content/transformation identity, so
testing `fastest` can no longer poison the next `balanced` layout. The repaired all-LZ4 layout gate
was 18.92s and clean. See
`docs/evidence/2026-08-06-balanced-hybrid-texture-pack.md`.
Evidence:
`docs/evidence/2026-08-05-persisted-rule-token-shapes.md`,
`docs/evidence/2026-08-05-graphicslib-normal-validation-journal.md`, and
`docs/evidence/2026-08-05-adapter-source-hash-journal.md`, and
`docs/evidence/2026-08-05-ashlib-variant-index.md`, and
`docs/evidence/2026-08-05-magiclib-optional-paintjob-json.md`, and
`docs/evidence/2026-08-05-nexerelin-faction-config-startup.md`.

## Environment notes that cost time to rediscover

- Launch the game with `--direct`; without it the run stalls on the launcher's Play button and
  measures nothing. Leo runs the launches; keep other CPU off the machine while one is measuring.
- `ls` is aliased to `eza` — use `command ls`. `grep --include=*.java` fails under this zsh.
  `.mvn/maven.config` supplies `--also-make`, so narrow `mvn -pl X ...` commands cannot silently
  compile against stale installed reactor dependencies. `-Dtest=` still needs
  `-Dsurefire.failIfNoSpecifiedTests=false` when upstream modules are included.
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
