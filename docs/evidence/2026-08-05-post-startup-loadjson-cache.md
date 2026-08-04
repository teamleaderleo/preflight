# Post-startup JSON reads can reuse the full-data profile cache

**Date:** 2026-08-05

**Status:** implementation and full repository verification complete; cold/warm live pilot pending

## Lead

The clean `aitweaks-boxing-fps-v3-20260805-062901` pilot made 36,090 unrestricted `loadJSON`
calls. The in-process memo served 26,590 repeats, but each process still parsed 7,637 distinct paths
once. Save loading was reported complete at 46.219 seconds, after which Combat Chatter began a large
batch of deferred CSV and character-JSON reads around 48.5 seconds. Retained bad campaign frames
clustered during this early period and nearly disappeared later.

This does not establish that JSON parsing caused every adjacent long frame. Log-gap attribution is
not causal. It does identify repeatable work that the next process currently pays again while the
campaign is already visible.

## Boundary

The existing in-process `loadJSON` memo now checks the prepared full-data-profile artifact on its
first eligible post-startup path. A prepared tagged tree is decoded once and becomes the ordinary
memo value for that process; later callers receive the same mutable object exactly as they do after
a vanilla parse today.

Persistence is deliberately narrower than the in-process memo:

- it is disabled until the exact reviewed resource-loader completion marker returns, after all mod
  resource roots are installed;
- resolver-restricted and one-shot-state calls still run vanilla and are never cached;
- only relative or contained absolute paths under `data/` are nameable; traversal and backslashes
  fail closed;
- `data/config/settings.json` remains unkeyed because its overlay changes while roots come online;
- the artifact remains gated by the content hash of every `data/` file in every enabled root, in
  override order;
- values use the already fidelity-replayed tagged-tree bridge; malformed/unrepresentable values,
  checksum errors, profile changes, and key collisions fall back to vanilla;
- the startup publication remains transactional. A second publication at JVM shutdown adds only
  the lazy reads learned since startup, and an unchanged revision performs no write.

The existing artifact and identity are reused, so this adds no profile hashing and no new cache
file. Older binaries fail closed if they encounter the new key kind.

## Verification

Executable tests perform a cold merged read, publish it, perform a post-startup single-file JSON
read, publish the later revision, and start a new simulated process. The second process reconstructs
a distinct equivalent `JSONObject` without calling vanilla. They also prove that a repeated
publication is a no-op and that the lightweight completion transform installs when the JSON memo is
enabled without frame telemetry. Key tests pin the covered path, dynamic-settings refusal, and
artifact validation. Full `mvn verify` passes, including failsafe and synthetic cross-process tests.

The next live launch is intentionally a learning run. It should report post-startup prepared misses
and captures, then grow the existing `.spmr` artifact at shutdown. A second otherwise-identical
launch should report prepared hits, fewer post-load `LoadingUtils` reads, and a better first-30-
second campaign distribution if this seam materially contributes to the observed jitter.
