# Public-writing sales inventory

This is the intentionally overcomplete reservoir for public Preflight writing.

It is not a release post and it is not a product contract. The point is to keep strong hooks from
disappearing when a README, Patreon post, forum announcement, release note, interview answer, or
Sponsors page has to become shorter. Current code, tests, the product contract, release readiness,
and retained evidence still outrank this document when a fact changes.

## The strongest sale

Preflight is a free, open-source performance launcher for Starsector. On the reviewed 83-mod
development setup, a controlled same-session comparison measured an 89.00-second normal-launch
median and a 15.53-second Preflight median, with one run reaching 15.25 seconds.

That performance investigation then grew into a much larger Starsector companion app: its own
before-and-after benchmark, local playtime tracking, named mod profiles, launch settings, preparation
and storage planning, recovery, privacy-conscious support, signed updates, removal, and a read-only
mod linter.

The hard sell is the combination. There are faster-launch claims, launcher utilities, mod tools, and
careful release processes elsewhere. Preflight's interesting thing is how many of those concerns now
meet in one product while still retaining exact fallback and evidence boundaries.

## Hooks by audience

### A heavily modded Starsector player

Lead with:

- the measured startup result;
- the built-in benchmark, so they can measure their own installation;
- named/searchable/duplicable mod profiles;
- game settings beside Launch;
- battle-size presets that can extend beyond the vanilla slider to 2,000 deployment points;
- disk planning before preparation and the minimal-disk route;
- recovery that does not require deleting everything and starting again;
- playtime tracking that Starsector itself does not expose;
- the read-only setup checker for missing/disabled dependencies, duplicate mod IDs, broken metadata,
  and resolved variants that point at absent hulls.

Useful line:

> Preflight can save you a pile of startup time, remember which mod setup you meant to use, expose
> the launch settings you actually care about, and tell you what preparation will cost before it
> writes anything.

### A player who distrusts launchers touching their game

Lead with concrete boundaries:

- no permanent patch to Starsector or mod JARs;
- no rewrite of saves, game assets, mod archives, or activation data;
- runtime shortcuts exist only inside the child game JVM;
- changed or unfamiliar targets decline to the original game path;
- profile and launch-setting writes are explicit, bounded, reviewed, and backed up;
- the desktop host exposes typed commands rather than an arbitrary shell;
- stopping a process requires both the recorded PID and matching start time;
- every native package carries a machine-readable capability receipt saying what it can write,
  launch, link to, and contact over the network.

Useful line:

> You do not have to infer what the installer is allowed to do from a privacy paragraph. The package
> carries a receipt for its actual native commands, writes, child processes, links, and endpoints.

### A privacy-conscious user

Lead with behavior:

- no account;
- ordinary game launches upload no logs or telemetry;
- update checks are separate and use one fixed feed;
- Copy setup is bounded public-support text;
- the ZIP is a separate fixed allowlist with explicit exclusions;
- sending is separate from creation and review;
- automatic failed-run reporting starts off;
- upload has progress/cancel/retry;
- accepted uploads receive a receipt with retention/deletion information;
- when enabled by the release intake, the app can delete the uploaded report through its scoped
  deletion authorization.

Useful line:

> Preflight does not ask you to trust the phrase “diagnostic data.” It tells you which files are in
> the ZIP, which categories are excluded, the byte count and digest, and only then gives you a Send
> button.

### A mod author

Lead with the linter, scan, and setup-analysis tools:

- `preflight lint --path ./MyMod` works on one mod without requiring a whole profile;
- whole-profile lint understands provider order and cross-mod relationships;
- `preflight scan` inventories the enabled profile, largest assets/mods, extensions, missing IDs,
  duplicates, and provider winners;
- `preflight analyze setup` is a separate deep read-only check that does not launch or modify the
  game;
- setup analysis can flag enabled mods with missing/invalid metadata, duplicate declared mod IDs,
  required dependencies that are missing or installed-but-disabled, malformed dependency metadata,
  and winning variants that reference hull IDs absent from the resolved profile;
- the linter edits nothing, gives no score, and has no automatic fixer;
- progressive JPEGs measured around 8.75 times slower through the game's ImageIO path;
- calibration across 86 installed mod directories produced a median of zero findings, with 44
  completely clean;
- the current rule set found a handful of real released-config defects among more than fifteen
  thousand reviewed config files instead of burying authors in generic strict-JSON noise.

Useful line:

> I pointed the same profiling work at the mods themselves. Most were fine. The interesting part was
> finding the expensive or silently unread minority without pretending every unusual asset was a
> mistake.

### A developer or open-source reader

Lead with the parts that show discipline:

- the benchmark history contains wrong measurements and corrections instead of only successful
  screenshots;
- rejected optimizations remain documented;
- exact version/class/archive/loader/method gates protect runtime transforms;
- the benchmark shuffles conditions and refuses underpowered result sets;
- release packages have checksums, dependency inventories, CycloneDX SBOMs, and capability receipts;
- release lifecycle checks exercise install, update, rollback, and removal;
- candidate evidence is being tied to the exact package bytes, not merely the same source revision;
- incompatible cache representations coexist so a rollback can still use the data expected by the
  older application;
- the same Java engine serves desktop and CLI entry points.

Useful line:

> The repository keeps the experiments that failed, the measurements that lied, and the checks that
> were added because of both. The release process gets the same treatment as the hot path.

### A supporter

Lead with what support pays for:

- real cross-platform testing hardware;
- time spent on compatibility changes after game/mod releases;
- packaging and release verification;
- hosted support/report infrastructure;
- continued performance investigation;
- future software and creative projects.

Do not turn a support tier into a private product. The attractive pitch is that sponsorship helps
fund unusually deep public work.

## Player candy we should say louder

### Battle size beyond the vanilla slider

The desktop derives its controls from the installation's own limits but has an extended ceiling of
2,000 deployment points. On an ordinary installation, the current presets are Minimum, Default,
Larger (600), Big (1000), Huge (1500), and Maximum (2000).

This is a very strong Starsector-specific hook because it is immediately understandable and has
nothing to do with explaining cache internals.

Do not describe the extended value as a separate Preflight game rule. It writes Starsector's own
`battleSize` preference. Opening the vanilla slider later can reset a value above that slider's
installed maximum.

### High-DPI resolution handling

The desktop uses the physical panel pixel dimensions behind OS scaling when offering resolution
choices. A Retina desktop reported as 1440×932 at 2× scale is treated as a 2880×1864 panel for the
resolution list and UI-scale ceiling.

This is not headline copy. It is excellent “somebody actually cared about this” material for a UI or
release post.

### Playtime that survives the launcher window

The local ledger is not a stopwatch tied to the desktop window. It follows Starsector sessions that
Preflight launches and can observe even when the desktop minimizes or exits afterward.

The public hook is simple: Starsector has no built-in lifetime counter, and Preflight gives one to
sessions it observes.

### Profiles without copying a second mod installation

Saved profiles retain ordered mod selections and can reuse matching prepared data. Duplicating a
profile does not copy mods, saves, or prepared bytes.

That makes “try another mod setup” much lighter than maintaining whole duplicate game directories.
Do not call Preflight a complete mod manager; it does not install or update mods.

### A Starsector-flavored UI without shipping Starsector art

The Hangar's ship display is not copied game artwork bundled into Preflight. The native host reads
installed hull definitions and, for featured ships, traces the installed sprite locally into bounded
wireframe contours, holes, interior lines, engines, and weapon mounts.

That gives the desktop a visual relationship with the installed game while keeping proprietary game
art out of Preflight's package.

Good design-post hook:

> How do you make a launcher feel like it belongs beside a game without copying the game's UI or
> shipping its art? In Preflight's case: read the installed ship data and draw a new wireframe from
> it locally.

## Power-user candy

### Same engine, desktop and terminal

The desktop and CLI use the same Java engine and safety checks. The GUI is not a separate simplified
implementation with different rules.

### Discovery without launch

`preflight doctor` finds launch candidates and shows what Preflight selected without starting the
game.

### Exact launch preview

`--dry-run` prints the selected launcher, complete command, working directory, trace destination,
adapter mode, and injected Java option without starting Starsector.

This is strong trust copy for users with unusual wrappers.

### Alternate launchers

Preflight can wrap the ordinary Starsector launcher, Fast Rendering launcher names, or an explicitly
selected compatible wrapper. It does not need to replace their files on disk.

### Direct launch

The CLI can use Starsector's own `launchDirect` path and its saved resolution/fullscreen/sound
preferences to skip the launcher UI. It refuses when those saved inputs are malformed or unavailable
instead of guessing.

Do not imply every desktop launch currently uses this path. Sell it as a power-user/unattended path
and as part of the permission-free benchmark machinery.

### Full mod-profile census

`preflight scan` reports enabled and missing IDs, file and byte totals, images, sounds, JARs, loose
Java, data-file totals, extension/mod breakdowns, largest assets/mods, duplicate logical paths, and
provider-order winners.

The census is a product in its own right for people trying to understand a huge mod setup.

### Deep setup check without launching the game

`preflight analyze setup` composes bounded metadata and static-reference providers over the resolved
profile. It can report:

- enabled mods with unavailable/invalid metadata;
- duplicate installed mods declaring the same ID;
- missing or installed-but-disabled required dependencies;
- malformed/incomplete dependency and total-conversion metadata;
- winning variants whose declared hull is absent from the authoritative resolved hull/skin set.

The command exits after analysis, says “Nothing was changed,” and can emit JSON for tooling.

That is a strong support and mod-author hook because it finds deterministic setup problems without
requiring a game launch, save load, or log dump.

## Troubleshooting is a feature

Preflight has Recommended, Conservative, and Off/troubleshooting presets.

Recommended enables every optimization that passed its live gate. Conservative keeps broad
immutable-input startup caches and omits mod-specific/gameplay shortcuts. Off disables runtime
transformations and profiling while keeping wrapper/process behavior available.

This means “something seems wrong” does not have to become “uninstall Preflight and reconstruct your
launch setup.” It can become one controlled downgrade with evidence left intact.

## Release engineering is saleable

### Native packages bring their own Java runtime

The Windows/macOS/Linux desktop packages contain a minimal Java runtime for Preflight. Users do not
need a system JDK for the native desktop app. Starsector and Fast Rendering continue using their own
runtime.

This belongs in installation copy. “No separate Java install for the desktop app” removes friction.

### The update path is explicit

Preflight checks one fixed feed, presents release notes, rechecks the exact offer before downloading,
verifies the updater signature, and only installs after **Install and restart**.

Download/signature/install failure leaves the current version runnable.

### Rollback is considered before migration

Incompatible cache formats move into separate namespaces instead of rewriting every old cache in
place. An older application can therefore still find the representation it understands after a
rollback. Old namespaces remain visible as Preflight data until the user chooses broader cleanup.

That is a strong engineering story because rollback is treated as a real state, not a line in a
README.

### Exact package capabilities

`engine/capability-receipt.json` binds the exact source revision and engine JAR and lists native
commands, writes, child processes, fixed links, update endpoint, and optional report endpoint.
Platform receipts bind the same statement to the actual package/update artifact hashes.

This is one of the project's strongest developer-facing trust hooks. Say it plainly.

### Release artifacts are inspectable

The planned public release includes checksums, license/notices/privacy/known-limitations files,
dependency inventory, five CycloneDX SBOMs, updater metadata, and platform-native packages.

Do not make the average player read this list before Download. Put it where a skeptical developer or
security-conscious user can find it immediately.

## Reliability hooks that deserve more airtime

- one normal packaged desktop lifetime per user/session prevents two UI instances from simultaneously
  believing they own the same native operation;
- one cross-process Preflight operation lease coordinates preparation, launch, profile changes,
  settings writes, pruning, updates, reports, and removal;
- request/plan results are bound to the installation/profile state that produced them so a stale
  response cannot silently apply to a newer selection;
- interrupted preparation never activates an incomplete artifact;
- low disk refuses before creating the preparation root when the conservative bound does not fit;
- damaged profile metadata can be repaired without throwing away shared content-addressed blobs;
- a frozen-run stop is tied to exact process identity so a reused PID or unrelated Starsector process
  is left alone;
- profile/settings writes preserve backups and recheck the reviewed source immediately before
  publication;
- update/cache design keeps rollback in mind instead of assuming every migration only moves forward.

These do not all belong in the first page of the README. Together they are excellent “why I spent so
long before beta” material.

## Gameplay work beyond startup

Recommended includes reviewed campaign/combat shortcuts in addition to startup preparation. Current
examples include exact campaign entity lookup/index work, commodity event-mod memoization,
deployment-member icon lookup, selected GraphicsLib runtime work, and simulator-opponent safety.

The campaign investigations also measured real hot call volumes and removed large amounts of
redundant allocation/work. Those results are not yet a clean general FPS claim. Sell the existence of
reviewed runtime work and the fact that it is measured; keep startup as the strongest controlled
performance headline.

Useful line:

> Startup is where the huge controlled number is. Preflight also contains reviewed campaign and
> combat shortcuts, but I am not turning exploratory frame-time work into a universal FPS promise.

## How much harder to say it

Weak:

> Preflight has profiles, settings, and diagnostics.

Stronger:

> The loading-time experiment grew a profile manager, a playtime tracker, a launch-settings front
> end, storage/recovery tooling, signed updates, and a support path that can tell you the digest of
> the ZIP before you decide to send it.

Weak:

> Preflight is careful about compatibility.

Stronger:

> Runtime shortcuts are pinned to the code they were reviewed against. If the target changes,
> Preflight declines that shortcut and lets the original game code run.

Weak:

> Preflight respects privacy.

Stronger:

> Ordinary launches upload no logs or telemetry. The support ZIP has a fixed allowlist and explicit
> exclusions, shows you its size and digest before sending, and returns a receipt with retention and
> deletion information.

Weak:

> Preflight has good release engineering.

Stronger:

> The updater is signed, rollback is rehearsed, incompatible cache formats can coexist for rollback,
> and the downloaded package carries a machine-readable statement of what it is allowed to write,
> launch, and contact over the network.

Weak:

> Preflight has game settings.

Stronger:

> Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size live beside Launch,
> including battle-size presets through 2,000 deployment points.

Weak:

> Preflight has setup diagnostics.

Stronger:

> Without launching Starsector, the deep setup check can tell you that a required dependency is
> installed but disabled, that two installed mods claim the same ID, or that the winning variant in
> the resolved profile points at a hull that does not exist there.

## Do not accidentally undersell with these habits

- Do not call a finished feature “small” just because its implementation was bounded.
- Do not say “there is also” five times. Put features into a sentence that explains why a player
  cares.
- Do not replace a concrete guarantee with “safe,” “privacy-focused,” “robust,” or “secure.” State
  the behavior.
- Do not make the startup number carry the whole page. The broader app is part of why the project is
  interesting.
- Do not bury the linter as developer tooling. Mod authors are a real audience.
- Do not bury no-system-Java native installs in the installation appendix.
- Do not bury report deletion, rollback behavior, or capability receipts in implementation docs.
- Do not bury `analyze setup` as an internal checker. It is useful player-facing diagnosis even
  before the desktop gets its future self-check button.
- Do not imply open PRs or candidate-only evidence are already shipped.
- Do not turn the current campaign/combat investigation into an FPS promise it has not earned.

## Good future-post titles / hooks

- “I tried to reduce one loading screen and accidentally made a Starsector companion app”
- “How 101 seconds became 15.25”
- “The loading bar said 0%. The game was still doing 20 seconds of work.”
- “The first cache had perfect hit counters and broke the screen”
- “I pointed a profiler at 86 Starsector mods”
- “Starsector has no playtime counter, so now Preflight does”
- “Yes, the battle-size button goes to 2,000”
- “What exactly is this launcher allowed to touch?”
- “Why does a game launcher rehearse rollback?”
- “What is actually inside a privacy-conscious support ZIP?”
- “Five gigabytes for less than half a second?”
- “The benchmark was wrong. Twice.”
- “The desktop app does not need you to install Java”
- “The same launcher has a GUI, a dry-run, a profile census, and a mod linter”
- “Can I tell my mod setup is broken without launching the game?”
- “Why does Preflight redraw Starsector ships instead of shipping their art?”

## Boundaries before publication

Keep these distinctions explicit:

- current-main product features can be sold as implemented;
- open PRs are active work, not shipped features;
- development performance evidence is real but remains development-context evidence until the exact
  release candidate has its own retained benchmark;
- Windows/Linux package automation is strong, while broader real-game/native-user evidence still
  arrives through beta;
- Fractal Softworks guidance/publication decisions remain owner-level release gates;
- report intake behavior in a public package is candidate evidence until the final packaged path is
  accepted.
