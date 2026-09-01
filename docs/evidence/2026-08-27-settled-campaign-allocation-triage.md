# Settled campaign allocation triage

Date: 2026-08-27

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, bundled x86-64
Zulu 17 under Rosetta, Preflight enabled

## Measurement boundary

The reusable allocation view now accepts scenario step names in addition to broad campaign/combat
classification. It anchors the JFR recording clock to `preflight.AgentStarted`, calibrates the
Rosetta clock skew from `jdk.CPULoad`, and maps the wall-clock receipts in the sibling
`smoke-evidence.json` onto exact JFR intervals. This recording needed a 2.493x conversion from wall
time to recorded time.

```bash
python3 scripts/starsector_gameplay_hotspots.py \
  benchmark-results/campaign-sample-reused-cursors-2-20260827-0154/startup.jfr \
  --allocations --step paused-settled --step unpaused-settled --top 20
```

The paused settled interval was 45.001 seconds wall / 18.052 seconds recorded. It contained 605
allocation samples, including 107 campaign samples weighted at 171.6 MiB. The unpaused settled
interval was 45.005 seconds wall / 18.054 seconds recorded. It contained 938 allocation samples,
including 647 campaign samples weighted at 971.5 MiB. JFR allocation weights are statistical
estimates, not exact byte counters.

The highest paused mod-owned category was Mnemonic Sensors' Kotlin `filterNotNullTo` chain at
23.9 MiB. The highest unpaused leaf was fresh ship-stat construction at 129.6 MiB. Neither result is
patched in this wave: the Mnemonic loop still needs a reviewed direct-iterator rewrite, while a
global cache of mutable ship stats would be unsafe without a narrower invalidation boundary.

## Rejected combat attribution

The whole recording contains 174 samples classified under a combat engine, including 125.4 MiB
weighted to AI-grid construction. Neither settled scenario interval contains a combat allocation
sample. Those samples belong to initial setup/transitions, not a played battle, so they are not
evidence for combat FPS or an optimization target. A separate deterministic real-battle fixture is
required.

## RAT missing-flag exception

The unpaused settled interval contains 12 allocation samples under
`ForceNegAbyssalRep.advance`, weighted at 14.0 MiB. Of that, 12.0 MiB is
`Throwable.fillInStackTrace` through `JSONException`; the remaining 2.0 MiB is the required lookup.

Random Assortment of Things 3.3.1 scans every faction every 0.3--0.5 game seconds. Its reviewed
source asks for the optional `rat_abyss_faction` custom-data flag through a generic false-fallback
helper. The compiled helper nevertheless calls `JSONObject.getBoolean`, so an ordinary faction
without the key constructs and catches an exception to produce `false`. The installed JSON API
provides `optBoolean(String)`, whose missing-key result is the same `false` without an exception.

The adapter plan changes only that one invocation. It is gated by:

- exact target class SHA-256
  `0c84737fb3c365d195e10df213f08d8184b645e03d2d75a7447a2b6286aaee5f`;
- exact RAT archive SHA-256
  `d34c805f84c259d9edcec197183a49cef4f3e488b2bf37768bb55f39f6d694e7`;
- Java 17 class major version 61, mod source, reviewed loader, method descriptor, and exactly one
  required lookup with no pre-existing optional lookup.

Class, archive, source, loader, version, or instruction-shape drift keeps original bytecode. The
rewrite adds no field, cache, serialization data, or persistent state, so it cannot enter or mutate
a save. The independent plan kill switch is
`PREFLIGHT_DISABLE_ADAPTER_PLANS=rat-3.3.1-abyss-faction-optional-flag-v1`.

## Claim boundary

This recording identifies the throwing path and justifies the semantic substitution. It does not
yet claim an FPS improvement. A Preflight-only follow-up must first show the exact plan installed
and the `ForceNegAbyssalRep -> JSONException -> fillInStackTrace` stack absent from a comparable
unpaused settled interval.

The bounded machine-readable record is
[`data/2026-08-27-settled-campaign-allocation-triage.json`](data/2026-08-27-settled-campaign-allocation-triage.json).
The raw JFR remains a disposable local artifact and is not committed.
