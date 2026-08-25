# First-Party Starsector Gameplay & Content Mod Concepts

> **Status:** Post-RC creative/research lane. No implementation branch starts while #652 convergence is active.
> No existing Preflight release authority is changed by this document.

---

## Product Boundary

Treat these as real Starsector mods first:

- Save-facing gameplay state belongs to the mod, not the launcher
- Campaigns must remain loadable without Preflight running merely because one of our mods is installed
- Use ordinary Starsector mod APIs wherever possible
- Optional Preflight Runtime integration may add diagnostics, exact capability evidence, performance help, or cross-mod awareness, but it must not become the only authority keeping ships/factions/campaign state coherent
- Agent/bytecode intervention remains for exact reviewed seams that the normal mod API cannot reach
- Do not expose a generic arbitrary-mod bytecode-patching SDK merely to make our own content easier

## Infrastructure Split (Retained)

| Component | Responsibility |
|-----------|----------------|
| **Preflight Core** | Launcher, setup analysis, preparation, recovery, evidence |
| **Preflight Runtime** | Exact-gated agent plus any small in-game companion bridge |
| **First-party gameplay/content mods** | Independent Starsector mods that may optionally consume runtime evidence |
| **Public SDK** | Later question, not a prerequisite for making fun things |

---

## Strongest First Concept — Reclamation / Shipwright

**Working idea:** An exploration/salvage mod built around **constructing ships from coherent surviving pieces**.

Takes the #911 "orange from parts" lesson and turns it into an intentional game mechanic rather than an emergency compatibility shim.

### Core Loop

1. Discover derelict fabrication sites, shipbreakers, wreck-fields, or ancient assembly engines
2. Recover hull frames, weapon systems, module sections, fabrication patterns, and rare structural materials
3. Decide whether to strip, repair, combine, or feed them into a fabrication event
4. Create one coherent ship from a deliberately designed family of compatible parts
5. Live with the resulting strengths, weaknesses, visible damage history, and unusual module combination

**Fun comes from meaningful constrained assembly, not an unbounded random-ID soup.**

### Design Direction

Create our own small family of ships designed for partial/modular construction from day one:

- Root hulls with explicit module sockets
- Interchangeable bow/engine/hangar/armor/support sections where combinations are actually authored to work
- Variant families that prove module/reference closure
- Damaged/pristine forms with explicit semantics rather than relying on accidental generic D-hull behavior
- Salvage outcomes that can legitimately produce fewer sections or no finished ship
- Clear boundaries between optional modules and required identity

### Fabrication Screen/Event Example

```
FRAME: Survey cruiser
PORT MODULE: missile battery
STARBOARD MODULE: cargo blister
ENGINE: overdriven salvage core
STRUCTURAL STATE: unstable but serviceable
```

This lets us make a genuinely weird fleet without silently producing half-valid FleetMembers.

### Why This Is a Good First Mod

It directly exercises knowledge we already earned:

- Module/variant closure
- Candidate eligibility vs constructibility
- Generation-before-commit validation
- Save/reload/persistence boundaries
- Recovery/D-mod behavior
- Random-selection probability semantics
- Asset validation and profiling

We would be designing the content around those semantics instead of reverse-engineering somebody else's assumptions.

---

## Concept — The Scrapper Compact

A small scavenger faction whose doctrine is **whatever coherent technology it has actually recovered**.

Rather than a conventional faction with a fixed full catalog, its fleets evolve through bounded acquisition rules:

- Starts with a small original baseline hull/weapon set
- Gains access to salvaged technologies over the campaign
- Can mount or field outside technology only when the exact ship/loadout path is known to be usable
- Recovered technology changes future doctrine and market stock
- Particularly exotic finds become rare one-off ships rather than silently becoming universal faction knowledge

### Visual Identity

Lean into repair plates, grafts, mismatched engine blocks, refitted civilian frames, and deliberately ugly-but-functional engineering.

### Optional Cross-Mod Mode (Later)

Allow the Compact to use **already-installed third-party content at runtime without redistributing it**.

**Conservative constraints:**

- Only content from enabled mods
- Exact installed-generation evidence
- Explicit candidate capability/admission rules
- Respect known restricted/no-drop/no-dealer/unique semantics where authoritative
- Never assume every `_bp` tag means pirate-generation permission
- No copying third-party assets into our distribution
- Failure/ambiguity means omit, not fabricate

**The base mod should still be fun with only our own assets.**

---

## Concept — Derelict Ecology

Make wrecks and abandoned infrastructure feel like an ecosystem rather than one-click loot containers.

### Possible Encounters

- A broken carrier whose surviving flight-deck module can be detached and recovered
- A wreck slowly being stripped by independent scavengers
- An automated drydock repeatedly producing malformed shells until the player repairs/changes its input pool
- Debris fields where the choice is between intact modules, raw materials, or information about a larger wreck
- Salvage sites that change after leaving and returning because other fleets interact with them
- Incomplete fabrication patterns that unlock an alternate version of one of our ships rather than merely another commodity

---

## Next Steps

1. **No code yet** — this document captures intent for post-RC exploration
2. Validate each concept against Starsector mod API capabilities
3. Prototype one concept as a standalone Starsector mod (no Preflight dependency)
4. Only then consider optional Runtime integration for diagnostics/evidence
5. Keep Preflight Core/Runtime release authority unchanged

---

*Related: #652 (convergence), #911 (orange from parts lesson)*
