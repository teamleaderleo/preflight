# Hitch packet v1: first exact campaign explanation

**Date:** 2026-08-28

**Disposition:** retained diagnostic infrastructure; this is not an FPS optimization claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Child:** [Hitch packet v1 #1150](https://github.com/teamleaderleo/preflight/issues/1150)

**Implementation:** `2f0ea0b3` (`Add bounded gameplay hitch packets`)

## Result

The first sealed live packet explained a 50.003 ms paused-campaign frame well enough to choose the
next experiment. Of that interval, 49.210 ms was before native swap, 0.538 ms was native swap,
0.248 ms was message processing, and 0.006 ms was other post-swap work. The nine exact broad
`CampaignEngine` phase producers overlapped only about 0.171 ms of the trigger frame. This hitch is
therefore not a native presentation wait and is not explained by those broad campaign phases.

The next highest-information slice is inside the pre-swap interval: distinguish game CPU work from
the game's own limiter/cap wait, then use an exact packet-triggered CPU escalation only if the game
work side remains large. This observation does not demote GPU/presentation decomposition for the
broader program; it narrows this particular hitch.

## Retained design

- The ordinary frame path writes only primitive arrays. It creates no per-frame packet objects.
- A frame of at least 50 ms opens a packet with up to two seconds of matching prehistory and one
  second of posthistory. Overlapping triggers coalesce.
- Capture is limited to 256 recent frames, eight packets, and 384 frames per packet.
- Only stable campaign pause states and combat observed after campaign entry are eligible. Focus,
  state, pause-transition, and sequence gaps terminate a packet. Title-screen demo combat cannot
  consume a gameplay slot.
- Frame/presentation history and the nine exact major campaign phases share `System.nanoTime`.
  Dynamic script-class attribution remains outside this join.
- The exact campaign producer is discovery-only and keeps 32,768 primitive call spans. The first
  live run proved 8,192 was insufficient; the corrected capacity completely covered the retained
  three-second confirmation packet.
- Live snapshots are explicitly best effort while producer arrays are changing. The sealed
  shutdown report is authoritative.
- Finalized packet reports are cached, so the one-second publisher does not repeatedly rebuild old
  histories.
- Adapter and diagnostic readers now accept this still-bounded richer report up to 4 MiB. The
  existing diagnostic-bundle content ceiling remains 5 MiB.

The recorder is enabled with frame-time telemetry. Exact campaign-phase joins exist only when the
separate `campaignTimes` discovery plan is enabled. With that producer absent, presentation history
remains useful and phase coverage is reported unavailable rather than inferred.

## Discovery run and corrections

The Preflight-only `campaign-profile-paused-unpaused` discovery route passed all 10 semantic steps.
It retained eight packets and dropped 19 later triggers, but exposed three defects in the first
shape:

1. two title-screen demo-combat hitches could consume the scarce packet budget;
2. 8,192 campaign call spans did not cover a full three-second packet in this profile;
3. the resulting rich adapter report exceeded the old 512 KiB bounded reader ceiling.

Those are retained findings, not discarded setup noise. The implementation now excludes
pre-campaign demo combat, uses a 32,768-span primitive ring, and applies a tested 4 MiB read ceiling.
The discovery files were 3.3 MiB in total and are represented by hashes in the bounded JSON record.

## Confirmation identity and health

The corrected Preflight-only `campaign-profile-current-state` route passed all five semantic steps
from 2026-08-27T17:12:46Z through 17:14:54Z and exited through the controller with code 0. The
reference identity was:

- Starsector 0.98a-RC8 core JAR SHA-256
  `a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149`;
- ordered profile fingerprint
  `2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`;
- texture-profile fingerprint
  `59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`;
- 1440x932 windowed direct launch with sound, ordinary presentation policy, frame telemetry and
  campaign discovery timing enabled;
- native arm64 Oracle Java 17.0.12 wrapper and the installed x86-64 Zulu Java 17.0.10 game runtime
  under Rosetta.

Adapter health was `ACTIVE`: 69 exact transformations applied across 87 registered targets, with
zero source-binding rejections, unavailable plans, declines, contained failures, cache rejection
signals, wrapper failures, or runtime-integrity failures. Fail-open remained enabled, the kill
switch remained inactive, and lifecycle inspection found no fatal. `originalCodeRetained=false`
means no transform needed to decline or fall back in this run; original behavior remains the
registered response to an identity mismatch or disabled plan.

The recorder observed 6,359 display boundaries. Its inclusive boundary-hook overhead averaged
15.784 microseconds and peaked once at 9.865 ms. An isolated steady-state calibration on the actual
x86-64 game Java 17 runtime measured a median 2.317 ns per enabled no-trigger ring write across 12
interleaved samples of four million calls. The disabled comparator optimized away, so this is an
absolute direct-call calibration, not an FPS delta or a complete in-game overhead claim.

## Packet evidence

The confirmation retained one complete paused-campaign packet:

| Field | Value |
| --- | ---: |
| Frames | 175 |
| Trigger sequence | 1,292 |
| Triggers / severe triggers | 1 / 0 |
| Duration | 50.003 ms |
| Pre-swap | 49.210 ms |
| Native swap | 0.538 ms |
| Messages | 0.248 ms |
| Other after swap | 0.006 ms |
| Joined exact campaign spans | 16,284 |
| Truncated frames / dropped packet triggers | 0 / 0 |

The trigger-frame phase overlaps were UI data 0.001 ms, factions 0.005 ms across 89 calls,
locations 0.162 ms across six calls with an 0.085 ms maximum, and campaign help 0.003 ms. Other
reported broad phases rounded below one microsecond. Because phase coverage was complete, the large
unattributed pre-swap remainder is a real boundary for the next probe, not a call-ring truncation.

## Correctness and save boundary

The recorder adds static, bounded telemetry state only. It does not transform save/load or
serialization classes, write a save, replace gameplay objects, or change campaign time. Exact
producer transforms retain the repository's source SHA, loader, and plan gates and fail inertly on
diagnostic errors. The live route continued the existing save and performed no save action.

Unit coverage exercises chronological wrap, pre/post capture, coalescing, packet and frame caps,
focus gaps, title-demo exclusion, incomplete live snapshots, absent producer behavior, call-span
overwrites, exact producer integration, and the richer report read ceiling. Full Java 17
`./mvnw verify` passed all five modules: 2,237 tests, zero failures/errors, and nine expected skips.

## Mutable conclusions and open questions

**Observed:** the packet joins enough independently timed evidence to say where this hitch was not.
It converted “FPS dropped” into a specific next experiment.

**Observed:** enormous phase-call volume is real: 514,404 exact major-phase spans were written in
this short run. Volume remains an optimization lead even though these calls did not explain this
trigger frame.

**Explored, not exhausted:** presentation wait caused earlier paused 30-FPS quantization, but not
this packet. Campaign phases have explained other unpaused clusters, but not this packet. Both
boundaries stay open for workloads that fingerprint differently.

**Open:** how much of the 49.210 ms pre-swap interval is actual game work versus limiter/cap sleep?

**Open:** can a thin trigger arm a short, higher-detail CPU capture after the next matching hitch
without contaminating the packet that caused escalation?

**Open:** should reports compact non-trigger frames into bounded histograms or deltas before the
packet corpus grows beyond this first implementation?

The machine-readable record, hashes, and exact calibration samples are in
[`2026-08-28-hitch-packet-v1.json`](data/2026-08-28-hitch-packet-v1.json). Raw launch directories,
console logs, and runtime reports are intentionally disposable after this record is committed.
