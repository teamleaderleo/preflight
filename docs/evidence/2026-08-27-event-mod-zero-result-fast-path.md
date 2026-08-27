# Commodity event-mod zero-result fast path

**Status:** accepted with limit; exact structural, installed-class, and live campaign checks passed

The remaining `CommodityOnMarket.reapplyEventMod` memo wrapper was still a coherent active-campaign
leaf after the earlier recomputation memo and telemetry cleanup. Two recent ordinary sampled runs
put the wrapper at 41/676 and 42/618 campaign samples, or 6.07% and 6.80%. The complete memo guard
still compared four backing-stat identities, four dirty/value pairs, the exact `eMod` entry and
description, and a conditional commodity econ unit on every unchanged call.

## Narrower result proof

Exact installed bytecode makes a much smaller common proof possible. Vanilla computes
`trade + max(tradePlus, 0) + min(tradeMinus, 0)`. When all three current stats are clean and exactly
zero and the current `eMod` mapping is absent, vanilla can only attempt an unsuccessful removal.
Availability changes, backing-stat replacement, commodity metadata, and prior memo fields cannot
affect that result.

The public transformed method now checks only the runtime gate, memo-valid bit, three current clean
zero values, and the exact current key. The complete mutation-aware fingerprint lives in a private
synthetic slow method. Dirty values, nonzero values, cancellation, a present `eMod`, first use, and
every mismatch take that slow method. It retains the original method, the exact post-vanilla
fingerprint, and linkage-error fail-open behavior.

The transform remains pinned to `CommodityOnMarket` SHA-256
`0d4157d29532ef969b0d61a52783a4fc3846c758d73409141915c2807e3c83e4`, the reviewed core archive,
Java 17 bytecode, app loader, companion `MutableStat` target, and exact method shapes. It adds no new
instance field beyond the existing 14 private transient memo fields. The private method is not save
state, and neither path opens, writes, or changes a campaign save.

## Opportunity and live result

One Preflight-only counter run completed both settled measurement windows before the known
interaction-active cleanup pause refusal. It observed 29,562,310 unchanged hits and 193,824 first or
changed-state delegations. Every unchanged hit—100% of hits and 99.35% of all observed calls—used
the exact-zero branch. `fastValidationUnavailable` remained zero.

The first unsplit arithmetic implementation was rejected. It reproduced cancellation with
`Math.max/min`, and two ordinary profiles left the wrapper at 55/578 and 50/529 campaign samples
(9.52% and 9.45%). A narrower three-zero proof still left the full fingerprint in the same compiled
method and measured 60/717 (8.37%). Moving the rare fingerprint into the private slow method reduced
the target to 18/441 campaign samples, or 4.08%, in the final live run. The final route completed all
semantic steps with 59 exact transforms, zero declines, zero contained failures, and zero inactive
frame intervals.

The final run's overall active-campaign frame pacing was poor: 38.38 average FPS, 8.95 FPS 1% low,
111.7ms p99, 12.38% repeated-cluster exposure, and 159.4ms/s stutter burden. It ran after repeated
profiles on a hot passively cooled machine and exercised a materially harsher campaign window.
Those numbers prohibit an FPS-uplift claim; they do not erase the direct hit ratio or the targeted
sample-share reduction. The retained claim is the exact common-case validation bypass, successful
live compatibility, and directional reduction of the targeted CPU category.

Focused Java 17 tests execute the exact installed classes through first use, zero-state hits,
available-stat changes, clean backing-stat replacement, cancelling modifiers, dirty/nonzero input,
description mutation, same-key object replacement, direct aggregate writes, and missing-accessor
fail-open. The complete repository verification result is recorded in the bounded data file.

The bounded record is
[`data/2026-08-27-event-mod-zero-result-fast-path.json`](data/2026-08-27-event-mod-zero-result-fast-path.json).
Raw JFRs, full logs, screenshots, and transformed binaries remain disposable local artifacts.
