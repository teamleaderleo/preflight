# GraphicsLib repeatedly constructs unattached fleet managers while drawing insignias

**Date:** 2026-08-04

**Install:** Starsector 0.98a-RC8, GraphicsLib 1.12.1, current mod profile

**Status:** measured from an existing gameplay JFR, exact adapter built and replayed against the
installed archive, then exercised successfully in a live combat pilot.

## The runtime lead

The 9,409-second recording at
`~/.starsector-preflight/runs/20260719-072149/startup.jfr` contains 25,951 execution samples from
the game thread. Matching stack classes to the exact class inventories of the installed mod JARs
puts GraphicsLib on **1,141 samples (4.40%)**, Nexerelin on 75 (0.29%), and UAF on 57 (0.22%). These
are inclusive runnable-CPU samples over a mixed long session, not wall-clock or FPS claims.

GraphicsLib's largest Java-owned frame is
`org.dark.graphics.plugins.InsigniaPlugin.renderInUICoords`, with 215 samples. The leaves beneath
that one method are unusually concentrated:

| leaf under the insignia renderer | samples |
| --- | ---: |
| `CombatFleetManager.<init>` | 105 |
| `CombatFleetManager.o00000` | 77 |
| `LinkedHashMap$LinkedHashIterator.nextNode` | 13 |
| `FleetMember.getCaptain` | 13 |
| everything else | 20 |

The installed GraphicsLib source explains the stack. For every alive non-fighter, non-drone,
non-player ship, on every UI render, it calls:

```java
engine.getFleetManager(ship.getOriginalOwner()).getDeployedFleetMember(ship)
```

The installed game's `CombatEngine.getFleetManager(int)` walks the live fleet-manager list. If no
manager has that owner, it constructs and returns a new unattached `CombatFleetManager(owner)`.
It does not add that object to the list. The insignia plugin therefore repeats the same list walk
and, for an absent owner, the same doomed construction for every matching ship and every render.
The returned empty manager cannot resolve a deployed member, so the plugin discards it immediately.

## The adapter

`--graphicslib-insignia-cache` transforms
only the exact reviewed `InsigniaPlugin` class from the exact GraphicsLib 1.12.1 archive and the mod's
URL classloader. The rewrite leaves the original render body and all coordinate/shader calls in
place. It inserts a four-entry map that is cleared at the start of each render invocation and routes
the one `getFleetManager(int)` call through it.

The scope is one invocation deliberately:

- repeated ships with the same original owner receive the exact same accessor result;
- null results are distinguished from an absent map entry and reused too;
- nothing survives into the next render, so a fleet-manager topology change cannot become stale;
- any class, archive, loader, method-shape, or second-transform drift retains the original bytes.

The shutdown report records cache hits, misses, total requests, total nanoseconds in each path,
mean hit/miss microseconds, and an estimated avoided duration that projects the same session's mean
miss cost across its hits. A live combat pilot must show both hits and misses; a transformed class
that serves no repeated owner has proved no speedup. This is an estimate rather than an A/B claim,
but it lets an ordinary beta session price the lead without asking its player to run a benchmark.

## Launch-free verification

- focused plan tests pass and prove the render method contains the cache helper while the sole real
  accessor remains in that helper;
- an execution-level woven fixture defines the transformed class, renders two ships with one owner,
  proves one real accessor call plus one cache hit, then proves the next render clears and revalidates;
- the same executed fixture proves a null manager result is cached distinctly from an absent entry;
- changed hashes, changed archives, foreign loaders, and a second rewrite fail closed;
- the opt-in installed-archive test transforms the actual `Graphics.jar` and declines a byte-changed
  copy;
- CLI parsing and agent injection tests reject adapter-off or probe-only use;
- an installed `--dry-run` selected the flag and emitted the exact agent option without starting the
  game.

The first live combat pilot served 58,945 hits and 7,303 misses with no adapter failure. The mean
observed miss was 1.245 microseconds and the session's hit/miss mix estimates 70.3 ms avoided inside
this narrow accessor. That is activation and compatibility evidence, not a controlled frame-time
A/B. After this gate the cache is included by `--fast`; the exact adapter still declines on any
class, archive, loader, or method-shape drift.

## What this says about mod-specific work

The same long recording does not justify direct Nexerelin or UAF rewrites. Their exact JAR-owned
frames are each below 0.3% of game-thread samples and diffuse. Nexerelin still matters indirectly:
the fleets it creates expose Starsector's stale sector entity map, which is why Preflight's separate
engine-level `--campaign-entity-index` pilot is the stronger campaign-map optimization. The rule is
to patch the shared engine seam when a mod amplifies it, and patch the mod only when its own frame is
measured as the cost, as it is here for GraphicsLib.
