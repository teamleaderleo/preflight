# Mod-stack intelligence prototype

## Purpose

Explore the next layer of Preflight: explaining a resolved Starsector installation as a living system rather than only inspecting files.

The goal is to answer the questions players and mod authors actually ask while keeping every answer tied to evidence:

- Why is this mod active?
- Which enabled mods reference this library?
- Which copy of this resource wins for this exact profile?
- Which source supplied this setting, and which resolver made it effective?
- What did Preflight actually observe during the last launch?
- Which old state belongs to Preflight, and which external files only look stale?

This is an explanation product, not a compatibility score, blame engine, or replacement mod manager.

## Core rule: every explanation is an evidence claim

The UI should never receive an unqualified sentence such as `MagicLib is used by 19 mods` or `LunaLib owns this setting` as a bare fact. The analysis layer should produce a small claim envelope first.

Conceptually, every claim carries:

- **subject** — the mod, resource, setting, subsystem, run, or owned artifact being explained;
- **layer** — declared, static, resolved, observed, or historical;
- **value** — the actual assertion;
- **scope** — installation, ordered profile, resource path, run, or time range where it applies;
- **evidence** — the metadata row, parsed file, provider chain, resolver rule, event, or retained record supporting it;
- **derivation** — direct evidence or the named deterministic rule used to derive the value;
- **identity binding** — game/profile/source identity that makes the claim current;
- **freshness** — current, stale, historical, or unavailable;
- **completeness** — whether absence is meaningful for this evidence source.

The product can render that compactly. The model still needs to retain it so a stronger sentence cannot accidentally be built from weaker evidence later.

Avoid confidence percentages. Prefer inspectable evidence states such as `declared`, `derived from provider order`, `observed during run`, `historical record`, and `unknown`.

## Information layers

Preflight should keep different kinds of knowledge separate. A later layer may confirm an earlier one; it should not silently promote it.

### Declared

Facts supplied by mod metadata or another explicitly declared source:

- dependencies;
- versions;
- optional integrations;
- plugin declarations;
- declared configuration keys.

Declared means `the source says this`, not `the game exercised this`.

### Static

Facts discovered from content without executing the game:

- class/API references in JARs;
- resource providers;
- configuration-key references;
- override relationships;
- known file-format relationships.

Use `references` or `contains evidence for` rather than `uses` when runtime use has not been observed. Reflection, generated names, optional paths, dead code, and bundled-but-unused classes all make static absence and presence weaker than runtime facts.

### Resolved

Facts produced by a reviewed deterministic resolver for one exact game/profile snapshot:

- active resource provider;
- shadowed providers;
- selected mod versions;
- effective values for settings whose resolution algorithm Preflight actually models;
- selected configuration source when a rule-specific resolver establishes one.

Resolved facts need the rule that produced them. `Provider ordering selected Mod A` is stronger and more useful than `Mod A wins`.

### Observed

Facts emitted by an actual launch or another instrumented operation:

- initialized systems when an authoritative event exists;
- adapter outcomes;
- prepared-data hits/declines;
- benchmark/run context;
- explicit runtime settings observations.

Observed evidence explains what the producer recorded. Missing events mean `unobserved` unless the producer declares the event stream complete for that question.

### Historical

Facts retained from an earlier installation/profile/run generation:

- prior enabled profiles;
- previously observed libraries or settings;
- old Preflight-owned prepared data;
- removed or renamed mod directories when a previous exact record proves the earlier identity;
- prior run evidence.

Historical evidence should always carry the generation/time it came from. Archaeology must never make an old fact look current merely because the same pathname or mod ID exists again.

## Identity and freshness boundary

Every resolved, observed, or historical explanation needs an explicit identity boundary.

At minimum, profile-scoped facts should bind to:

- installation identity;
- game build/content identity where relevant;
- exact ordered enabled-mod profile identity;
- the source/provider generations used by the claim;
- the run/evidence identity for observed facts.

A pathname is a selector, not durable identity. A mod ID is a label, not a file generation. The same logical path can name different bytes later.

The #832/#833 file-generation work is therefore part of the long-term authority boundary for persisted exact-content explanations. Until strong source-generation evidence is available for a claim, Preflight can compute and display a current-snapshot explanation while avoiding a stronger durable statement that survives arbitrary source replacement.

When any identity input changes, the old claim becomes historical or stale. It should never be silently carried forward as current.

## Negative evidence and completeness

Absence is only meaningful when the evidence source is complete for that question.

Examples:

- `No dependency is declared` can be valid when the complete mod metadata was parsed.
- `No static reference was found` means exactly that; reflection or generated names may still produce runtime use.
- `MagicLib did not initialize` requires an authoritative complete initialization event stream. A timeline with no MagicLib event only proves that Preflight did not observe one.
- `No provider exists` requires a complete provider inventory for that logical resource and profile.

The claim envelope should make completeness explicit so UI wording can distinguish `none`, `unobserved`, and `unknown`.

## First prototype surfaces

### Installation map

Start with language that reflects the evidence layer.

Example:

```text
83 enabled mods

Libraries
  LazyLib
    statically referenced by: 19 enabled mods
    observed initialized: yes, last Preflight launch
  MagicLib
    statically referenced by: 34 enabled mods
    runtime status: unobserved
  LunaLib
    declared/settings integration evidence: 12 mods

Dependencies
  required declarations: 147
  satisfied in this profile: 146
  unresolved declarations: 1
```

This keeps `reference`, `resolution`, and `observation` separate instead of collapsing them into one `used by` count.

### Resource explanation

This is a strong first implementation target because Preflight already has an ordered provider model.

Example:

```text
sounds/weapon/fire.ogg

Profile:
  Main Campaign · <profile fingerprint>

Selected provider:
  Mod A

Earlier providers:
  Mod B
  Mod C

Resolution:
  Selected by Starsector resource-provider order for this exact profile

Freshness:
  Current snapshot
```

The question is why this logical resource resolves to these bytes for this profile. Keep file/resource provider resolution distinct from class loading and other lookup systems that may use different rules.

A useful CLI prototype could be:

```text
preflight explain resource sounds/weapon/fire.ogg
```

The machine form should return the provider chain and derivation, not only the rendered sentence.

### Configuration explanation

`Ownership` is too broad unless Preflight knows the resolver. Separate four concepts:

- **declared source** — a file or API exposes a value;
- **writer** — a component can persist/change a value;
- **reader** — a component consumes a value;
- **effective authority** — the reviewed resolver that decides the value the game/component will use.

Example when a rule-specific resolver exists:

```text
graphicsLib.enableShaders

Effective value:
  true

Inputs:
  GraphicsLib settings file: false
  LunaLib setting: true

Resolver:
  GraphicsLib/LunaLib integration rule <rule id/version>

Selected source:
  LunaLib setting

Evidence:
  resolved for this exact profile snapshot
```

If Preflight only sees both keys, show both sources and leave effective authority unknown. Key presence alone does not prove precedence.

Each supported settings family should earn a resolver through tests/counterexamples rather than inheriting a generic precedence assumption.

### Runtime timeline

The timeline should be an event view, not a reconstructed story.

Example:

```text
Launch <run id>
 |
 +-- [observed] MagicLib initialization event
 +-- [observed] GraphicsLib initialization event
 +-- [observed] Preflight texture acceleration active
 +-- [observed] adapter X declined: fingerprint mismatch
```

Each event should retain:

- event producer/type;
- run identity;
- ordering/timestamp semantics provided by that source;
- bounded public detail;
- whether the producer promises completeness for the event family.

Several event streams may only provide partial ordering. Render them as such rather than manufacturing a total causal sequence.

## Mod-author value

A good report should help authors and players understand systems without judging them.

Avoid:

- compatibility scores;
- conflict rankings;
- automatic blame;
- treating shared APIs as conflicts;
- converting static references into runtime accusations;
- inferring intent from filenames or package names.

Prefer:

- `these 19 enabled mods statically reference LazyLib API classes`;
- `this optional integration is declared`;
- `this resource provider is selected by the exact profile order`;
- `this resolver selected the LunaLib value`;
- `this assumption was confirmed by event X during run Y`;
- `Preflight has no authoritative evidence for this question yet`.

Explanations should expose enough provenance that an author can disagree with the rule or evidence rather than with a mysterious verdict.

## Archaeology and cleanup

Large installations accumulate history:

- removed mods;
- old libraries;
- stale profiles;
- abandoned prepared data;
- duplicate archives;
- renamed directories.

Archaeology needs the historical layer. Current filenames alone cannot prove that an old directory belonged to a previously observed mod generation.

Cleanup should use explicit ownership tiers:

1. **Preflight-owned** — exact ownership is proven; preview and bounded removal can be product actions.
2. **External, operator-selected** — the player explicitly selects a mod/archive target; Preflight may explain evidence and consequences before a separate action.
3. **External, inferred** — Preflight only suspects age/duplication/relationship; explanation is advisory and deletion stays out of the automatic path.
4. **Game-owned or ambiguous** — retain and explain why authority is insufficient.

`Safe to remove` should be reserved for an ownership rule strong enough to support the exact removal action. Otherwise use wording such as `appears unused in this profile`, `older observed generation`, or `duplicate content detected`, with provenance.

## Suggested implementation order

### 1. Define the claim envelope

Create the internal/machine representation before broad UI work. Pin scope, evidence, identity, freshness, derivation, and completeness semantics with small tests.

### 2. Resource explanation

Build from the existing profile/resource index and provider order. This gives immediate player/mod-author value while exercising the claim model on already-understood authority.

### 3. Installation map: declared + static

Inventory metadata and bytecode/content references. Use evidence-layer wording (`declares`, `references`) and avoid runtime conclusions.

### 4. Observed timeline

Project existing run/adapter/prepared-data events into the same claim model. Preserve event-family completeness and ordering limits.

### 5. Configuration resolvers

Add one settings family at a time. Each resolver gets a named rule and counterexamples proving precedence. Avoid a universal configuration-authority heuristic.

### 6. Historical archaeology

Only after snapshot/generation identity is strong enough to distinguish old evidence from a current same-name object. Keep external cleanup advisory until exact ownership exists.

## Counterexamples the model should survive

Before calling the model ready, pin examples such as:

- a JAR statically references LazyLib but its code path never runs;
- runtime use occurs through reflection with no static class reference;
- two mods provide the same resource and profile order changes the winner;
- the same pathname is replaced with a new file generation after analysis;
- the same mod ID appears in a different directory/version later;
- two configuration sources contain a key but only one reviewed resolver defines precedence;
- a valid texture pack/resource set exists while its preferred order differs;
- an expected runtime event is absent from an event family that does not promise completeness;
- historical evidence names a mod that has since been removed and another mod later reuses the directory name;
- a cleanup candidate sits outside Preflight ownership even though its bytes duplicate another archive.

The desired result is conservative, useful language rather than a guess promoted into fact.

## Possible long-term outcome

Preflight becomes the tool that answers:

> `What is this Starsector installation actually doing, and what evidence supports that answer?`

The performance launcher is the entry point. The installation model is the deeper product. The evidence contract is what lets that deeper product stay trustworthy as it grows.
