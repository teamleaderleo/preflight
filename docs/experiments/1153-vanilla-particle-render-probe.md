# Issue #1153: vanilla DynamicParticleGroup render probe

Fast Rendering's largest narrow batching win is its explicit treatment of Starsector's built-in particle groups. FR brackets selected vanilla particle rendering with draw reordering, groups compatible immediate-mode draws by draw mode / texture state / blend state, and flushes them at a combat-layer boundary.

Porting FR's whole combat renderer or global GL bridge would carry far more compatibility risk than the batching idea itself. This probe answers the question needed before taking that cost: how much live combat time is actually spent inside vanilla `DynamicParticleGroup.render(float,float)` on the workloads from #449/#1152, and does that method directly contain the legacy GL calls we would need to rewrite?

## Exact target

Class:

```text
com/fs/graphics/particle/DynamicParticleGroup
```

Method:

```text
render(FF)V
```

The runner derives the class SHA from the installed reviewed Starsector 0.98a-RC8 `starfarer_obf.jar` and binds the external AdapterTarget to the reviewed core archive SHA, code-source suffix, and application classloader.

If another renderer shadows the class or the core archive/class changes, the target declines.

## Probe mechanics

Property:

```text
-Dpreflight.dynamicParticleGroupProbe=true
-Dpreflight.dynamicParticleGroupProbe.report=<path>
```

`DynamicParticleGroupRenderProbePlan` inserts one `System.nanoTime()` read at method entry and one timing commit before each normal `RETURN`. It does not instrument particles, vertices, or individual GL calls.

The transform also records a static callsite fingerprint from the exact target method:

- normal return sites;
- `glBegin` / `glEnd` sites;
- `glVertex*` sites;
- `glTexCoord*` sites;
- `glColor*` sites;
- `glBindTexture` sites;
- `glBlendFunc*` sites.

This distinguishes two next-step cases:

1. the group directly owns immediate-mode submission, in which case a local primitive-buffer/array rewrite is plausible;
2. the group delegates rendering elsewhere, in which case the fingerprint points us away from rewriting the wrong class.

Runtime telemetry records aggregate calls, total milliseconds, mean/max microseconds, and counts above 0.25/0.5/1/2/5 ms.

## Live command

```bash
bash scripts/run-1153-particle-probe.sh \
  --route ordinary \
  --workload-id <id>
```

Then repeat on `symmetric-1040` if the ordinary run shows meaningful particle time.

The script explicitly disables the frame-sync, GraphicsLib tess-array, and packed-replay experiments so the profile describes vanilla particle rendering plus the normal frame-time probe.

## Decision rule

Move to a local particle batching rewrite when:

- the exact transform applies once with zero contained failures;
- particle render time is material in the measured combat route, especially in tail-heavy windows;
- the GL fingerprint shows direct immediate-mode work or identifies a tighter downstream method;
- the live visual result remains unchanged with the timing-only probe.

If aggregate particle time is tiny on both #449/#1152 routes, record that result and stop spending compatibility budget on this lane.
