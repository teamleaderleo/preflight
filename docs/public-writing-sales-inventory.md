# Public-writing sales inventory

This is the intentionally overcomplete reservoir for public Preflight writing. It exists so good
hooks, weird specifics, and useful side stories survive the shortening process; it is source
material, not a release post and not a checklist that any one piece of public writing has to march
through.

Read [Public writing style](public-writing-style.md) before turning this material into prose. Current
code, tests, the product contract, release readiness, and retained evidence still outrank every line
here when a fact changes.

## The strongest sale

Preflight is a free, open-source performance launcher for Starsector. On the reviewed 83-mod
development setup, a controlled same-session comparison measured an **89.00-second ordinary-launch
median** and a **15.53-second Preflight median**, with one Preflight launch reaching **15.25
seconds**. Earlier in development the same installation had reached roughly 101 seconds.

That is a large enough result to carry the headline by itself, which is convenient because the
investigation then went on and produced a fairly unreasonable amount of application around it: the
same before-and-after benchmark in the desktop, local Starsector playtime, named mod profiles, launch
settings through 2,000-point battles, storage planning and recovery, read-only setup analysis,
privacy-conscious support, signed updates, a locally generated wireframe Hangar, and a mod linter.

The interesting thing is the accretion. Preflight started as one performance problem and kept finding
places where a heavily modded installation was tedious, opaque, or unnecessarily repetitive; the
public writing should feel like that story unfolding, rather than a brochure trying to prove that a
launcher has many features.

## Audience hooks

### Heavily modded players

The obvious opening is the measured launch result followed immediately by the built-in benchmark:
there is a large development number, and the product contains the tool that lets somebody find out
what the corresponding number is on their own machine.

After that, use whichever adjacent annoyance will feel most familiar to the audience. Named profiles
mean trying another setup without maintaining another complete game directory. Resolution,
fullscreen, sound, antialiasing, UI scale, RAM, and battle size sit beside Launch, and on a standard
installation the battle-size presets go through 2,000 deployment points. Preparation tells you what
it will cost before it writes, can offer a minimal-disk route, and has normal-speed fallback and
repair when acceleration data cannot be used. The read-only setup check can find missing or disabled
dependencies, duplicate IDs, bad metadata, and selected broken references before the game starts.

Playtime is good candy because Starsector has no built-in lifetime counter. Preflight keeps a local
total for sessions it launches and can observe even when the desktop minimizes or exits afterward.

### Players who are suspicious of launchers touching the game

Start with behavior they can picture. Runtime optimizations live inside the launched game process;
Starsector and mod JARs, executables, assets, and saves stay outside the acceleration path. Profile
activation and launch settings are the two backed-up game-owned preference changes. When reviewed
game or mod code changes, the affected shortcut steps aside and the original game path handles that
work.

The deeper package story is there for people who want it: native packages carry a machine-readable
capability receipt describing the commands, writes, child processes, links, and network endpoints in
that package. That is a useful second paragraph or evidence link, not an entrance exam before the
Download button.

### Privacy-conscious players

Ordinary launches upload no logs or telemetry. **Copy setup** creates a small support summary with
useful game, profile, mod, and launch facts while leaving out private paths, credentials, saves, and
arbitrary logs. A deeper support ZIP is a separate action, shown before sending, with explicit
exclusions and cancellation/retry; automatic failed-run reporting starts off.

The good public line is behavioral: tell people what they can inspect and what stays out. Avoid
turning that into generic values language.

### Mod authors

The linter is a second public story in its own right. `preflight lint --path ./MyMod` can inspect one
mod by itself; whole-profile mode understands provider order and cross-mod relationships; `scan`
inventories the resolved profile; and `analyze setup` catches deterministic dependency/reference
problems without launching Starsector.

The calibration result is unusually useful: across 86 installed mod directories, the **median was
zero findings and 44 of 86 were completely clean**. Progressive JPEGs were one of the clearest
measured cases, decoding about **8.75 times slower** through the game's ImageIO path than equivalent
baseline encoding. The linter edits nothing, gives no score, and has no automatic fixer.

Useful hook: "I pointed the profiler at 86 mods. Most of them were fine. The interesting part was
what the expensive minority had in common."

### Developers and open-source readers

The failed experiments are valuable here. The first texture-cache pilot could report healthy hits
while breaking visuals; a supposed timing split came from a stale benchmark anchor; JFR's clock was
wrong under one runtime condition; a GraphicsLib replay made its target path slower; AppCDS failed to
earn its complexity and came back out.

Pair that with the release story only when the audience wants it: signed updates, rollback rehearsal,
checksums, dependency inventories, CycloneDX SBOMs, package lifecycle checks, and capability
receipts. The thread connecting all of it is that the project keeps the counterexamples and makes
future claims answer to them.

### Supporters

The creator-level pitch is simple: support pays for development time, testing hardware, hosting,
release work, compatibility work after game and mod updates, and whatever future project receives
the same unfortunate amount of attention. Preflight remains free and open source; contribution tiers
are contribution levels rather than product editions.

## Player candy worth saying louder

### Battle size beyond the vanilla slider

On a standard installation the desktop can offer battle-size presets through **2,000 deployment
points** while writing Starsector's own `battleSize` preference. It is immediately understandable,
peculiarly Starsector, and requires almost no promotional varnish.

### High-DPI resolution handling

The desktop reasons from physical panel pixels behind OS scaling when it builds the resolution list.
This is excellent "somebody actually cared about this" material for a product or UI post, even if it
rarely belongs in the headline.

### Profiles without duplicate installations

Saved profiles retain ordered mod selections and can reuse matching prepared data. Duplicating a
profile copies the profile definition rather than the mods, saves, or prepared bytes. This is a good
way to explain the feature without quietly promoting Preflight into a complete mod manager, which it
isn't.

### The Hangar draws from the installed game

Featured ships can be traced locally from installed hull and sprite data into Preflight's own
wireframe rendering, so the app can feel adjacent to Starsector without bundling the source artwork.
There is a whole design/dev post hiding in that one decision.

### The launcher can disappear after launch

After Preflight confirms the actual game process is alive, the desktop can minimize, stay open, or
quit according to the remembered preference, and playtime recording can continue after Quit. The
product does the launch work and then gets out of the way.

## Power-user candy

The desktop and CLI use the same Java engine. `doctor` shows launcher discovery without starting the
game, `--dry-run` shows the selected launch command, `scan` inventories a huge enabled profile,
`analyze setup` checks the resolved setup, and the optional direct path uses Starsector's saved
launcher preferences. Preflight can also wrap a selected compatible launcher without replacing it on
disk.

These are good trust and power-user details because they are useful before they are impressive.

## Storage has its own counterintuitive story

On the reviewed 83-mod development profile, Balanced retained **4.76 GB**, Minimal disk retained
**10.9 MB**, and Fastest retained **10.03 GB**. Ten fresh-JVM texture replays measured 1,137ms for
Balanced and 691ms for Fastest, which means several extra gigabytes bought hundreds of milliseconds
at that seam rather than another giant launch-time collapse.

"Five gigabytes for 446 milliseconds?" is a better storage story than "Fastest is best," because the
whole point of the option is that the marginal trade can be worth it to one player and ridiculous to
another.

## Reliability stories that deserve occasional airtime

Some reliability work is compelling when translated back into the player problem it solves. An
interrupted preparation never becomes the new active prepared profile; low disk is caught before the
normal preparation starts writing; cleanup is previewed; damaged prepared data can be repaired;
process stopping is tied to the recorded process identity; update/cache design keeps rollback in
mind.

This material belongs in a "why did beta preparation take so long?" post or a skeptical-user answer.
It does not all belong above the README fold.

## How much harder to say it

The useful distinction is concrete versus generic, rather than weak versus loud.

"Preflight has profiles, settings, and diagnostics" says almost nothing. "The loading-time
investigation grew named profiles, Starsector playtime, the launch settings I kept reaching for, and
a setup checker that can tell me a required dependency is merely disabled before I launch the game"
is longer, more specific, and sounds like somebody remembers why the features exist.

"Preflight is careful about compatibility" is fog. "When reviewed game or mod code changes, that
shortcut steps aside and Starsector handles the work normally" says what the player experiences.

"Preflight respects privacy" is equally vaporous. "Ordinary launches upload no logs or telemetry;
Copy setup leaves out paths, credentials, saves, and arbitrary logs; the deeper support ZIP is shown
before sending" gives the reader something to evaluate.

"Preflight has good release engineering" sounds like a résumé. If the audience cares, tell them the
updater is signed, rollback is exercised, and the package can tell them which native operations and
network endpoints it contains.

## Habits that make public copy worse

Do not turn this inventory into a listicle merely because the material arrived here as categories.
Do not make every sentence a punch. Do not manufacture sets of three for rhythm. Keep transitions;
let one feature or failure cause the next one when the story actually works that way.

Keep the odd specificity. **83 mods**, **89.00 → 15.53**, **2,000 deployment points**, **44 of 86
clean**, the bad JFR clock, the texture cache that lied with perfect-looking counters: this is the
stuff that makes the project sound lived-in instead of branded.

Use unusual vocabulary when it is the precise or entertaining word, then go back to ordinary
English. A rare word gains its pleasure from having normal words around it.

Avoid repeated public-facing engineering terms such as "exact," "bounded," "authority," and
"fail-closed" when the player-level behavior can be said directly. Those words remain useful in
engineering documents and occasionally in a technical explanation; their ubiquity is what turns
public copy into a defense brief.

Do not turn a finished feature into a diminutive because its implementation was narrow. Do not bury
the linter, no-system-Java desktop install, rollback behavior, report deletion, or setup analysis
merely because their evidence is technical. Do not describe open PRs or candidate-only evidence as
shipped. Keep startup as the strongest controlled performance claim instead of laundering exploratory
campaign/frame-time work into a universal FPS promise.

## Good future-post hooks

Use whichever one opens a real story:

- "I tried to reduce one loading screen and accidentally made a Starsector companion app"
- "How 101 seconds became 15.25"
- "The loading bar said 0%. The game was still doing 20 seconds of work."
- "The first cache had perfect hit counters and broke the screen"
- "I pointed a profiler at 86 Starsector mods"
- "Starsector has no playtime counter, so now Preflight does"
- "Yes, the battle-size button goes to 2,000"
- "Why does a game launcher rehearse rollback?"
- "Five gigabytes for 446 milliseconds?"
- "The benchmark was wrong. Twice."
- "How Preflight draws Starsector ships without shipping Starsector art"
- "Can I tell my mod setup is broken without launching the game?"

## Release boundary before publication

The live beta gate is the four candidate/platform tasks in
[#652](https://github.com/teamleaderleo/preflight/issues/652): real-game Windows and Linux exercise,
the complete hosted three-platform candidate, the startup benchmark on the packaged candidate, and
the final packaged support-intake cancel/retry/delete canary.

The Fractal Softworks request remains courtesy correspondence and is outside the publication gate.
#833 generation-authority work and the other continuing hardening/research lanes are post-RC unless a
concrete candidate failure promotes one. Development performance evidence stays labelled as
development evidence until the packaged-candidate result joins it.
