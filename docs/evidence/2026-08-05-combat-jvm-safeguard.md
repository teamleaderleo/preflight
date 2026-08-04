# Exact combat JIT safeguard

On 2026-08-05 a controlled combat run crashed while ships were being destroyed around a full
retreat order:

```text
java.lang.ClassCastException: class com.fs.starfarer.combat.entities.ship.A.J
cannot be cast to class com.fs.starfarer.combat.entities.ship.A.null
    at com.fs.starfarer.combat.entities.Ship.advance(Unknown Source)
```

The run is
`~/.starsector-preflight/runs/frame-time-state-v3-20260805-004434`; Preflight classified it as
`FATAL_LOG_EVIDENCE` even though the shell launcher returned zero.

## Why this is not an ordinary bad cast

Static inspection of the exact installed `starfarer_obf.jar` shows that `A.J` directly implements
`A.null`. A one-shot probe at `CombatEngine.advance` then established the loaded relationship before
combat:

- `A.null.isAssignableFrom(A.J) == true`;
- both classes use `jdk.internal.loader.ClassLoaders$AppClassLoader:app`;
- both code sources are the same installed `starfarer_obf.jar`;
- `A.null` is present in `A.J.getInterfaces()`;
- Preflight transforms neither `Ship.advance`, `A.J`, nor `A.null`.

The installation's launcher is not the stock macOS policy. It runs x86-64 Zulu 17.0.10 through
Rosetta, enables a large group of experimental compiler/vector flags, and supplies a directives
file that restricts combat code to C1. The impossible cast under that policy is therefore strong
evidence of a JIT/runtime miscompile. It is not proof of which individual flag or compiler phase is
responsible.

Java verification cannot be restored as a diagnostic: the shipped obfuscated core contains JVM
identifiers such as `for.Object`, and Zulu 17 rejects it with `ClassFormatError` before the title
screen. Starsector's `-noverify` is required for this build.

## Controlled isolation

The follow-up run
`~/.starsector-preflight/runs/retreat-ship-interpreted-v1-20260805-005857` added only:

```text
-XX:CompileCommand=exclude,com/fs/starfarer/combat/entities/Ship.advance
```

The player recreated the relevant destruction/full-retreat overlap. The run exited normally with
adapter health `ACTIVE`, 26 transformations, zero declines/failures, and the runtime relationship
above intact. Its combat frame distribution was 5,905 frames, p50 16.8ms, p95 24.7ms, and p99
48.8ms. The exclusion did not show an obvious performance penalty in that non-identical battle.
One successful run does not prove an intermittent failure is gone, but it is a meaningful A/B and
the narrowest safe workaround available before the JVM starts.

## Automatic boundary

`CombatJvmSafeguard` now injects that one compile exclusion only when all of these match:

1. macOS;
2. the reviewed aggressive launcher flags;
3. the reviewed combat C1-only directives;
4. bundled x86-64 Azul Zulu 17.0.10 on Darwin;
5. SHA-256 `71997384...b29926` for the exact `Ship.class` entry.

Any probe error or identity drift retains the launcher's original policy. The decision, reason,
class hash, and escape hatch are written to `run.json`. Users can opt out with
`PREFLIGHT_DISABLE_COMBAT_JVM_SAFEGUARD=1`. A real-install dry run activated the safeguard and
showed the exact `_JAVA_OPTIONS`; synthetic tests cover platform/launcher drift, explicit disable,
the final class gate, option idempotence, and preservation of a manual diagnostic mode.
