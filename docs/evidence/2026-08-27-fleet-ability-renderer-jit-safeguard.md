# Exact FleetAbilityRenderer interpreter safeguard

**Status:** retained exact-runtime reliability safeguard; one focused route and one focus-interrupted route completed

An unattended Preflight-only campaign run failed 99.667 seconds into Starsector with:

```text
java.lang.NullPointerException: Cannot invoke "java.util.Iterator.hasNext()" because "<local4>" is null
  at com.fs.starfarer.campaign.fleet.FleetAbilityRenderer.render
  at com.fs.starfarer.campaign.fleet.FleetAbilityRenderer.render
  at com.fs.graphics.LayeredRenderer.render
  at com.fs.starfarer.campaign.BaseLocation.render
```

The exact installed method obtains local 4 from
`fleet.getAbilities().values().iterator()`. The installed `BaseCampaignEntity.getAbilities()`
returns either its existing map or a new `LinkedHashMap`; the ordinary JDK collection path cannot
produce a null iterator. Preflight does not transform `FleetAbilityRenderer`. This is evidence of an
impossible runtime state, not proof of one particular compiler flag as the cause.

The reviewed macOS launcher already requires interpreted execution for exact `Ship.advance` and
`Ship.render` bytecode after two comparable impossible runtime casts under the bundled x86-64
Zulu 17 JVM through Rosetta. The same narrow policy now covers
`FleetAbilityRenderer.render`, but only when all existing runtime, launcher, directive, platform,
and `Ship.class` gates match and the renderer class has SHA-256
`cc48a9afc218b0e08e7b1731f47b570e55331d3999823aea4c4e27fa049db38c`.
Unknown or changed classes retain the launcher's compilation policy. The environment kill switch
remains `PREFLIGHT_DISABLE_COMBAT_JVM_SAFEGUARD`.

## Live result

The first guarded follow-up completed the full semantic route: menu, internal Continue, an initial
pause observation, 27 seconds of paused warm-up, 45 seconds settled paused, internal unpause, five
seconds of transition, and 45 seconds settled unpaused. Java printed and accepted all three exact
compile exclusions. The route crossed the prior failure point without another null iterator.

That run exposed a separate harness-accounting defect. After the controller completed measurement,
it sent SIGTERM to its exact owned JVM. Starsector then logged two OpenAL native-link errors while
cleaning up, and the generic top-level error rule mislabeled the successful route as fatal. The
controller now publishes a bounded stop-intent receipt before signalling. Only the observed
`AL10.nalGetError` and `AL10.nalListenerfv` cleanup stacks are downgraded, and they remain visible as
`controller-stop-openal-cleanup` evidence. The same stacks without controller stop intent, and every
other native-link error, remain fatal.

A packaged follow-up completed with launcher exit 0, run outcome `COMPLETED`, adapter health
`ACTIVE`, 59 exact transforms, zero contained failures, and zero runtime-integrity failures. Its run
report retained both ignored cleanup matches and no fatal match. All ten scenario steps passed.
The operator focused another application during the run, so 4,701 inactive intervals were correctly
dropped and its incomplete state buckets are excluded from performance comparison. The earlier
focused guarded run supplies the route-survival evidence; neither run proves that an intermittent
JIT fault can never recur.

The compact receipt is
[`data/2026-08-27-fleet-ability-renderer-jit-safeguard.json`](data/2026-08-27-fleet-ability-renderer-jit-safeguard.json).
Raw logs, JFR recordings, and transformed binaries are disposable local artifacts.
