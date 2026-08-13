# Post-startup JSON reads can reuse the full-data profile cache

**Date:** 2026-08-05

**Status:** implementation, full repository verification, and cold/warm live pilot complete

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

## Live cold/warm result

The learning run
`~/.starsector-preflight/runs/post-startup-json-learning-20260805-065028` and warm run
`~/.starsector-preflight/runs/post-startup-json-warm-20260805-065335` both completed normally. All
33 reviewed transforms applied, with no decline or contained failure.

The cache itself worked as designed:

| metric | learning | warm |
| --- | ---: | ---: |
| unrestricted `loadJSON` calls | 36,090 | 36,090 |
| in-process memo hits | 26,590 | 26,592 |
| prepared single-file hits | 0 | 746 |
| prepared single-file misses | 750 | 2 |
| prepared single-file captures | 746 | 0 |
| failures / collisions / unstorable values | 0 | 0 |
| full-data artifact size | 9,055,324 bytes | 9,055,392 bytes |

Thus the warm process served 746 of 748 eligible first reads from prepared trees: **99.73%**
coverage. The 68-byte second revision was one newly observed merged-read entry, not churn in the
single-file set. The warm log also contained 172 `LoadingUtils` lines in the first 35 seconds after
save completion versus 363 in the learning run.

That technical hit did **not** improve the early-campaign frame distribution:

| first 30 campaign seconds | learning | warm |
| --- | ---: | ---: |
| frames | 1,417 | 1,401 |
| average FPS | 47.34 | 46.72 |
| median FPS | 58.82 | 57.80 |
| 1% low FPS | 12.09 | 11.61 |
| p95 / p99 frame time | 45.0 / 82.7ms | 50.3 / 86.1ms |
| frames meeting 60 FPS | 43.33% | 43.04% |

The sessions were operator-driven rather than a frame-identical synthetic workload, so the small
movement is noise, not a measured regression. It is enough to reject the proposed explanation:
repeat JSON parsing was not the dominant cause of the visible campaign warm-up jitter. The cache
still eliminates repeat parsing with a narrow fail-closed boundary and negligible artifact growth,
but it carries no campaign-FPS claim.

Remaining campaign logger activity points toward real catch-up simulation: Nexerelin fleet-pool,
route, economy, diplomacy, and mission work continues across the bad interval. The 116-line vanilla
`RepTrackerEvent` burst itself spans only about 17ms, so its logging cannot explain seconds of
jitter; it is merely evidence that an event pass occurred. Exact call-site timing is required before
changing any of those systems.
