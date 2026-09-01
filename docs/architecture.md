# Architecture

## TL;DR

```text
React UI
   ↓ typed desktop commands
Rust/Tauri host
   ↓ starts / queries
Java CLI + engine
   ↓ launches with
Java agent inside Starsector

Preflight-owned cache/history sits beside that path.
```

The important rules are simple:

- game/mod binaries and saves stay source material;
- reusable acceleration data lives under Preflight-owned storage;
- runtime changes live inside the launched JVM;
- exact identity checks decide whether an optimization can run;
- when a shortcut can't prove its target, the original game path stays available;
- the CLI and desktop share the same Java engine instead of implementing separate product logic.

For a conversational walkthrough, read [How Preflight works](how-preflight-works.md). This page is the contributor-facing module/data map.

## Principles

1. Game/mod source material stays unchanged except for explicit, confirmed preference/profile operations owned by the [product contract](product-contract.md).
2. Preflight-owned acceleration data is rebuildable.
3. Cache/runtime eligibility failures return to the owning original path or rebuild path.
4. Persisted formats and runtime integration interfaces are versioned.
5. Content identity, enabled-mod order, source bindings, and relevant transformation configuration participate in invalidation.
6. Files are resolved through approved canonical roots; links that escape those roots are rejected.
7. First preparation and repeat-launch performance are measured separately.
8. Performance claims carry their actual profile/machine/measurement context.

Moving release blockers belong in [Release readiness](release-readiness.md) / [#652](https://github.com/teamleaderleo/preflight/issues/652), not in this architecture page.

## Java modules

### `preflight-core`

Reusable identity, format, validation, storage, and report logic.

Persisted families include resource-provider indexes, classpath profile/archive indexes, texture manifests/packs/blobs, SpecStore data, generated-bytecode records, and bounded evidence formats. Format changes use explicit versioning and version-qualified cache directories where needed.

### `preflight-agent`

The Java agent that runs inside the selected Starsector child JVM.

It owns reviewed in-memory transformations, adapter/runtime evidence, and related JVM-side instrumentation. Exact class/source/loader/profile checks gate each plan. Unknown or changed targets leave that optimization inactive.

Optimizations remain independently disableable through the reviewed preset/plan boundary.

### `preflight-cli`

The runnable command engine and launcher wrapper.

It owns installation discovery, profile inventory, preparation, launch orchestration, profiles/settings/history, benchmark/analysis commands, evidence export, and the packaged executable JAR.

`prepare` handles the offline families that can actually be prepared at that boundary. Other Recommended acceleration data can be learned/materialized at its own exact runtime boundary. See [Preparation](prepare.md).

### `preflight-synthetic-startup`

Synthetic child-JVM workloads and experiments that let the project exercise performance/runtime mechanisms without requiring a real Starsector launch for every test.

Player-facing product logic belongs in the normal engine modules, not here.

## Desktop application

`preflight-desktop/` is the React + Tauri desktop product.

- **React** owns presentation and user interaction.
- **Rust/Tauri** owns the narrow native bridge: typed commands, process/native-dialog/update/package behavior.
- **Java** remains the source of truth for Starsector-facing operations.

The browser layer doesn't receive a generic shell/filesystem interface. The Rust host exposes the operations the product needs instead.

See [`preflight-desktop/README.md`](../preflight-desktop/README.md) for desktop development/package detail and [UI design](ui-design.md) for visual/interaction guidance.

## Launch flow

```text
preflight.jar run
  -> discover the selected Starsector/supported launcher
  -> resolve profile + eligible prepared artifacts
  -> create an isolated run directory
  -> add the Preflight JAR as a process-local Java agent
  -> start the existing launcher as a child process
  -> track that exact process/run
  -> write bounded run/adapter/profile/JFR-derived evidence
```

A launch preset chooses the reviewed optimization set:

- **Recommended:** normal accelerated path;
- **Conservative:** portable/less invasive subset;
- **Off / troubleshooting:** keeps launch/process ownership and bounded outcome reporting while runtime acceleration is disabled.

Preparation by itself doesn't activate a runtime transformation. The launch still has to select the reader/plan, and its exact identity/runtime checks still have to pass.

## Prepared-data layout

The cache root has independent versioned families. Code should use the owning directory helpers rather than reconstructing paths from this overview.

```text
~/.starsector-preflight/
  cache/
    resource-indexes[/version]/
    classpath[/version]/
      profiles/
      archives/
    spec-store/
      profiles/
      variant-json[/version]/
      weapon-json[/version]/
      projectile-json[/version]/
      hull-json[/version]/
      rules-csv[/version]/
      rule-command-classes[/version]/
      merged-reads[/version]/
    manifests[/version]/
    packs/
    blobs/
    quarantine/
    reports/
  runs/
    RUN/
      run.json
      profile.json
      startup.jfr
      summary.json
      adapter.json
      adapter-analysis.json
```

Content-addressed artifacts can be shared across profiles when their identities permit it. Profile-named artifacts bind ordered/profile-specific selection to exact inputs.

Missing, corrupt, stale, ambiguous, escaped, or identity-mismatched data is rejected by its owning component and either rebuilt or left to the original game path.

## Evidence belongs with evidence

Architecture tells you where responsibilities and boundaries live. It doesn't own the performance chronology.

For current/rejected optimization history and benchmark context, use:

- [Engineering overview](engineering-overview.md)
- [Optimization history](optimization-history.md)
- [Startup benchmark protocol](startup-benchmark.md)
- [Evidence archive](evidence/)

The selected current development headline is **112.17s → 13.69s**; the historical A/B/gate numbers stay in those evidence/history documents rather than being recopied here.
