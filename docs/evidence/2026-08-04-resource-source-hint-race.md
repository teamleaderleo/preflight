# An existing mod resource can disappear when another loading thread steals its source hint

## Result

The intermittent startup fatals which report an existing mission JSON file as missing are caused by
a race in Starsector 0.98a-RC8's resource resolver. This is independent of Preflight's experimental
resource-probe cache: the same failure reproduced through the vanilla launcher, and the latest
failure occurred with that cache disabled.

The resolver stores a one-shot "search this mod" hint in a field on its singleton instance. The
public setter and resolver are each `synchronized`, but a mod-specific load is two separate calls:

1. set the mod-directory hint;
2. resolve the requested path;
3. clear the hint in a `finally` path.

The monitor is released between steps 1 and 2. A second loading thread can therefore enter the
resolver first, take and clear the first thread's hint, and apply it to its own path. The correct
directory is then skipped. The error remains misleading because the resolver appends every root to
its diagnostic string before applying the hint filter.

This explains all observed properties together: the victim file changes with timing, the file is
present and unchanged, the fatal lists the directory that contains it, a cooldown makes the failure
less likely without guaranteeing anything, and vanilla can reproduce it.

## Installed-bytecode evidence

The exact installed archive is:

- `fs.common_obf.jar` SHA-256
  `10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708`
- `com/fs/util/C.class` SHA-256
  `ee81369a75dfa518ddbbf1bfb83c96845effc2cf9189179fc08a17863837d0fd`

In that class:

- the singleton resolver has a private `String` field;
- `return(String)` writes the field;
- `Object(String, boolean)` begins by reading the field to local 3 and clearing it;
- a non-null local 3 filters directory roots with `endsWith` before the per-root open;
- both methods are synchronized, but there is no monitor spanning both calls.

An ASM caller census of the installed `starfarer_obf.jar` found the source-hint setter used by
`StarfarerSettings$1.loadJSON`, `loadCSV`, and `loadText`. Each resolves the mod directory, sets the
hint, and then invokes a separate `LoadingUtils` load.

The 2026-08-04 22:05 failure is retained at:

`~/.starsector-preflight/runs/campaign-v3-fast-validation-20260804-220528`

It reported `data/missions/xlu_test_other/descriptor.json` missing before the main menu, with the
experimental resource-probe cache absent from `--fast`.

## Preflight correction

`SourceHintIsolationPlan` replaces only the reviewed field transaction:

- the setter stores the hint in `SourceHintIsolationRuntime`'s `ThreadLocal<String>`;
- the resolver takes and clears the current thread's hint at the same bytecode position;
- `null` still clears the hint;
- synchronized method flags and all source-selection logic remain shipped behavior.

The correction is registered for every enabled adapter, including `--fast`. It is gated on the
exact class hash, archive hash, methods, loader, protection domain, Java 17 class version, field
transaction instruction shape, and call source. Any game patch or foreign transformer drift keeps
the original bytes.

It does not retry missing resources and cannot convert a genuinely absent file into a hit. The
optional resource-probe cache targets the same class; the transformation registry composes the two
rewrites and has a test requiring both boundaries to survive together.

## Offline verification

- deterministic two-thread test: Alpha and Beta set hints, interleave, and each takes its own hint;
- one-shot test: a second take is null;
- shape tests: setter and resolver no longer access the singleton hint field;
- fail-closed tests: wrong hash, wrong setter shape, and a second rewrite decline;
- composition test: all 11 optional `File.exists()` probe sites and the per-root wrapper coexist;
- installed-archive test: the exact shipped class transforms and contains one runtime `set` and one
  runtime `take` call.

## Live campaign smoke

`campaign-source-hint-v1-20260804-221805` completed with exit 0 after loading a campaign and roaming
the map. Adapter health was ACTIVE: 17 transformations applied, zero declined, and zero contained
failures. The exact source-hint target matched with no source, loader, class, method, or archive
problem. Runtime counters were:

- 2,806 setter calls: 1,403 non-null hint scopes and their 1,403 final clears;
- 14,637 resolver takes;
- 1,222 non-null hints consumed on the resolver path.

The difference between scopes and consumed hints is expected: a cache hit can complete between the
set and final clear without entering the resolver. The experimental resource-probe cache was off,
so it neither caused nor masked the result.

The JFR window was 42.98 seconds while the user browsed the web, so this run is deliberately not a
startup performance measurement. Absence of an intermittent failure in one run is likewise a smoke
check, not proof of race elimination; the deterministic interleaving test is the proof of the
isolation property.
