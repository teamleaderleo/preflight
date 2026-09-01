# Installed fleet-inflation phase checkpoint

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical` at `d5fee527`.

Status: **phase boundary verified; first observation retained; frame association still pending**.

This is a mutable experiment checkpoint, not an optimization result. It follows the lazy
fleet-inflation hitch explanation in
[the preceding retained report](2026-08-28-installed-lazy-fleet-inflation-hitches.md).

## Exact installed boundary

The opt-in `vanilla-default-fleet-inflater-time-probe-v1` plan targets only
`com.fs.starfarer.api.impl.campaign.fleets.DefaultFleetInflater` from the installed 0.98a-RC8
`starfarer.api.jar`:

- class SHA-256: `80a07787e75edbdd5ae0b80da023aeaa59f43d08263de10b77af4595452b08ae`;
- source JAR SHA-256: `6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185`;
- class-file major: 61 (Java 17);
- exact method: `inflate(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;)V`;
- kill switch: `preflight.campaign.fleetInflaterTimes.disabled=true`.

The transform fails closed on class hash, method shape, or already-woven runtime calls. The
installed-byte integration test observed ten phase entries, ten exits, one member-loop marker, and
an original-byte decline on a second transform. The complete repository `./mvnw verify` gate passed
after the plan was registered.

## First installed observation

Run `issue-1158-inflater-phase-r1-20260828-131411` used the same 83-mod profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, recommended Preflight plan,
1440x932 window, Apple M5 OpenGL-over-Metal adapter, and the semantic paused-to-unpaused route. All
scenario actions passed, the adapter applied all 75 exact transformations with no contained
failure, no save command was issued, and the harness stopped only its recorded process.

It observed five real `DefaultFleetInflater.inflate` calls covering 30 fleet members:

| phase | calls | total | maximum | share of inflater total |
| --- | ---: | ---: | ---: | ---: |
| total | 5 | 53.741 ms | 26.351 ms | 100.0% |
| initial setup | 5 | 1.466 ms | 1.122 ms | 2.7% |
| hullmod pool | 5 | 0.183 ms | 0.052 ms | 0.3% |
| weapon pool | 5 | 2.589 ms | 1.155 ms | 4.8% |
| fighter pool | 5 | 0.841 ms | 0.279 ms | 1.6% |
| member work | 5 | 39.900 ms | 18.084 ms | 74.2% |
| autofit, inclusive inside member work | 30 | 21.820 ms | 4.373 ms | 40.6% |
| D-mod work, inclusive inside member work | 30 | 8.180 ms | 2.203 ms | 15.2% |
| final sync | 5 | 8.679 ms | 5.717 ms | 16.2% |
| exact sync call, inclusive inside final sync | 5 | 8.542 ms | 5.626 ms | 15.9% |

The top-level phases account for 53.657 ms of the 53.741 ms total. Autofit and D-mod rows are nested
inside member work, while the exact sync call is nested inside final sync; they must not be summed
again. The exclusive member remainder after autofit and D-mod work was 9.900 ms.

The enclosing tactical timer observed 52 `inflateIfNeeded()` calls taking 64.727 ms total, of which
only five performed a real inner inflation. Matching the five inner and outer intervals leaves
approximately 0.532, 2.041, 2.677, 3.368, and 2.164 ms outside the inner inflater. That residual is
consistent with outer listener/removal/bookkeeping work, but it is not yet directly timed and must
remain a hypothesis.

## Invalid frame evidence retained

This run is intentionally not an FPS or frame-association claim. The game did not remain the active
window: 8,151 of 8,254 observed boundaries were excluded as inactive, leaving only 102 active
startup-like frames and zero frames in `campaignUnpausedActive`. The 16 retained inflater slow spans
therefore have no eligible worst-frame population to join against. Their absence from a frame join
is **unclassified**, not evidence that the work missed bad frames.

The dedicated discovery route now foregrounds the exact PID after the untouched initial paused
window. `scripts/starsector_slow_span_frames.py` also performs the phase-to-frame interval join
automatically and refuses to interpret missing retained frames as a negative result. A foreground
rerun remains the next required physical check.

To avoid another physical round trip if that rerun confirms the member/autofit lead, it now also
contains the discovery-only `vanilla-core-autofit-time-probe-v1` boundary. That plan requires exact
installed `CoreAutofitPlugin` SHA-256
`5ccef552d487617232057f17a5b009566179b53862aade7bee8aabccff703b5c`, Java class-file major 61,
the reviewed `doFit(...)` descriptor, ten semantic regions, and 35 fixed helper call sites. Its 46
paired entry/exit sites preserve every original call and result, decline on a second transform, and
have the independent kill switch `preflight.campaign.coreAutofitTimes.disabled=true`. Broad regions
are intentionally inclusive of their named helper families, and `setupModules` may include a
recursive `moduleAutofit`; the report labels that nesting. It retains at most 48 spans of at least
one millisecond. Focused installed-byte tests and the complete `./mvnw verify` gate passed. There is
not yet any installed-run telemetry from this deeper boundary.

## Explored questions

- **Are availability-pool builds the large reusable seam?** Current answer: unlikely. Initial setup
  plus all three pool families consumed only 5.078 ms across all five inflations, with no individual
  pool phase above 1.155 ms. Do not build a cache candidate from this observation.
- **Is per-member work the dominant internal family?** Current answer: yes in this observation,
  accounting for 74.2% of inflater time. Autofit was the largest named nested component, but its 30
  calls were distributed and the worst individual call was 4.373 ms.
- **Can final synchronization simply be removed or deferred?** Current answer: no evidence supports
  that. It is stateful and consumed 16.2% here; changing its timing requires stronger correctness
  proof.
- **Does reducing allocation or call volume alone justify a candidate?** No. The rejected AI Tweaks
  experiment remains the counterexample: a large allocation collapse without a reproducible
  player-visible frame win is still a rejection.

## Falsifiable hypotheses and open questions

1. A foreground rerun should reproduce at least one real inflation and place its retained total span
   inside an eligible campaign frame. If it does not, preserve that workload variation rather than
   manufacturing a join.
2. If the worst joined inflation is again dominated by member work, a narrower exact timer inside
   the reviewed `CoreAutofitPlugin.doFit` boundaries has higher information value than attempting an
   availability-pool cache.
3. If actual inflations remain below roughly one ordinary frame, the current phase result explains
   stateful occasional work but does not select an optimization. Retain it and return to the broader
   #449 attribution funnel.
4. The outer `inflateIfNeeded()` residual may be listener/removal cost. Instrument that boundary only
   if the foreground join shows the residual materially contributes to a bad frame.
5. Any future candidate must preserve generated fleet variants, D-/S-mod state, listener behavior,
   synchronization, exact adapter health, and a comparable campaign workload. Discovery FPS from
   this intrusive plan cannot be used as its performance claim.
