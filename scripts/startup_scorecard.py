#!/usr/bin/env python3
"""Print the accumulated Preflight startup scorecard.

The inputs below are measured deltas from the linked repository evidence.
They deliberately keep ranges where the same optimization was repeated.
"""

from __future__ import annotations

from dataclasses import dataclass


BASELINE_SECONDS = 88.13
MEASURED_VANILLA_SECONDS = 80.09
MEASURED_FULL_SECONDS = 42.36
ORIGINAL_OBSERVED_LOW_SECONDS = 90.0
ORIGINAL_OBSERVED_HIGH_SECONDS = 100.0


@dataclass(frozen=True)
class Saving:
    name: str
    low_seconds: float
    high_seconds: float
    source: str


SAVINGS = (
    Saving(
        "Prepared textures + prefetch bypass",
        25.530,
        25.530,
        "docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md",
    ),
    Saving(
        "AshLib repeated ship JSON",
        7.066,
        7.435,
        "docs/evidence/2026-08-02-ashlib-startup-json-cache.md",
    ),
    Saving(
        "GraphicsLib compact auto-generation replay",
        4.821,
        4.821,
        "docs/evidence/2026-08-02-graphicslib-compact-autogen-replay.md",
    ),
    Saving("Merged variant JSON", 2.700, 2.700, "PR #275"),
    Saving("Merged weapon JSON", 2.000, 2.000, "PR #278"),
    Saving("Merged projectile JSON", 1.100, 1.100, "PR #281"),
    Saving("Merged ship-hull JSON", 1.700, 1.700, "PR #284"),
    Saving("Merged campaign-rules CSV", 0.680, 0.680, "PR #288"),
    Saving("Campaign-rule duplicate index", 0.561, 0.561, "PR #286"),
    Saving("Rule-token memo", 0.150, 0.150, "PR #291"),
    Saving("Prepared rule-command package map", 0.165, 0.165, "PR #298"),
    Saving("Shared cache-profile identity pass", 1.161, 1.161, "PR #300"),
)


# Per-launch counters gathered from the component runs. Some counters describe
# consecutive stages of the same item (for example decode + conversion), because
# each stage is a separate operation that no longer runs.
OPERATION_COUNTS = {
    "texture prefetch enqueues skipped": 50_879,
    "image decodes bypassed": 21_652,
    "pixel conversions bypassed": 21_652,
    "derived-color calculations bypassed": 21_652,
    "merged variant JSON hits": 5_138,
    "merged weapon JSON hits": 2_921,
    "merged projectile JSON hits": 1_159,
    "merged hull JSON hits": 2_471,
    "merged rules CSV hits": 1,
    "linear duplicate scans replaced by hash checks": 21_059,
    "repeated tokenizations served from the memo": 62_340 - 31_614,
    "prepared command-package hits": 671,
    "provider real-path resolutions avoided": 12_797 - 694,
    "redundant resource-index reads avoided": 6 - 1,
}


CACHE_OR_MEMO_HITS = (
    21_652  # prepared texture hits
    + 5_138
    + 2_921
    + 1_159
    + 2_471
    + 1  # merged rules CSV
    + (62_340 - 31_614)
    + 671
)


def percent_reduction(before: float, after: float) -> float:
    return (before - after) / before * 100.0


def speedup(before: float, after: float) -> float:
    return before / after


def main() -> None:
    saved_low = sum(item.low_seconds for item in SAVINGS)
    saved_high = sum(item.high_seconds for item in SAVINGS)
    floor_high = BASELINE_SECONDS - saved_low
    floor_low = BASELINE_SECONDS - saved_high

    print("Accumulated measured startup savings")
    for item in SAVINGS:
        value = (
            f"{item.low_seconds:.3f}s"
            if item.low_seconds == item.high_seconds
            else f"{item.low_seconds:.3f}-{item.high_seconds:.3f}s"
        )
        print(f"  {value:>15}  {item.name}  ({item.source})")

    print()
    print(f"Total of the component savings: {saved_low:.3f}-{saved_high:.3f}s")
    print(f"  predicted floor from the 2026-08-01 {BASELINE_SECONDS:.2f}s baseline: "
          f"{floor_low:.3f}-{floor_high:.3f}s")

    print()
    print("MEASURED, 2026-08-03 composed campaign (this is the number to quote):")
    print(f"  vanilla                {MEASURED_VANILLA_SECONDS:.2f}s")
    print(f"  full                   {MEASURED_FULL_SECONDS:.2f}s")
    print(f"  removed                {MEASURED_VANILLA_SECONDS - MEASURED_FULL_SECONDS:.2f}s "
          f"({percent_reduction(MEASURED_VANILLA_SECONDS, MEASURED_FULL_SECONDS):.1f}%)")
    print(f"  speedup                {speedup(MEASURED_VANILLA_SECONDS, MEASURED_FULL_SECONDS):.2f}x")
    print(
        f"  against the 90-100s lived range: "
        f"{speedup(ORIGINAL_OBSERVED_LOW_SECONDS, MEASURED_FULL_SECONDS):.2f}-"
        f"{speedup(ORIGINAL_OBSERVED_HIGH_SECONDS, MEASURED_FULL_SECONDS):.2f}x"
    )
    print()
    print("The component savings above summed to within ~2s of the measured floor, but the")
    print("2026-08-01 baseline they were subtracted from had moved 8s by the time the composed")
    print("campaign ran. Divide by the vanilla measured in the same session, not an older one.")

    print()
    print(f"Cache or memo hits represented by the component runs: {CACHE_OR_MEMO_HITS:,}")
    print(f"All counted operations removed or shortcut: {sum(OPERATION_COUNTS.values()):,}")
    for name, count in OPERATION_COUNTS.items():
        print(f"  {count:>8,}  {name}")


if __name__ == "__main__":
    main()
