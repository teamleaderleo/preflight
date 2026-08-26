#!/usr/bin/env python3
"""Sync repeated current product facts from docs/project-facts.json."""

from __future__ import annotations

import argparse
import json
import re
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FACTS = ROOT / "docs/project-facts.json"
HEADLINES = ROOT / "docs/claim-headlines.md"

PUBLIC_COPY = (
    "README.md",
    "docs/beta-announcement-draft.md",
    "docs/beta-announcement-leo-draft.md",
    "docs/engineering-overview.md",
    "docs/github-sponsors-page-draft.md",
    "docs/leo-talking-points.md",
    "docs/patreon-page-draft.md",
    "docs/public-writing-sales-inventory.md",
    "docs/release-post-draft.md",
    "docs/releases/0.1.0.md",
    "CLAUDE.md",
    "LLM_HANDOFF.md",
)
TECHNICAL_COPY = ("docs/optimization-history.md", "docs/technical-writeup-draft.md")
SPEEDUP_COPY = (
    "docs/beta-announcement-draft.md",
    "docs/beta-announcement-leo-draft.md",
    "docs/github-sponsors-page-draft.md",
    "docs/leo-talking-points.md",
    "docs/patreon-page-draft.md",
    "docs/public-writing-sales-inventory.md",
    "docs/release-post-draft.md",
    "docs/releases/0.1.0.md",
)
HEADLINE_PATTERN = re.compile(
    r"current readable development headline is \*\*(\d+(?:\.\d+)?)s → (\d+(?:\.\d+)?)s\*\*",
    re.IGNORECASE,
)


def q2(value: Decimal) -> str:
    return format(value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP), "f")


def load_facts() -> dict:
    facts = json.loads(FACTS.read_text(encoding="utf-8"))
    if facts.get("format") != "preflight-project-facts-v1":
        raise ValueError("unsupported docs/project-facts.json format")
    return facts


def metrics(facts: dict) -> dict[str, str]:
    current = facts["startup"]["development"]
    before = Decimal(current["baselineSeconds"])
    after = Decimal(current["endpointSeconds"])
    return {
        "speedup": q2(before / after),
        "saved": q2(before - after),
        "reduction": q2((Decimal(1) - after / before) * 100),
    }


def rendered_values() -> tuple[str, str]:
    match = HEADLINE_PATTERN.search(HEADLINES.read_text(encoding="utf-8"))
    if match is None:
        raise ValueError("docs/claim-headlines.md has no generated current startup headline")
    return match.group(1), match.group(2)


def rewrite_public(text: str, old_before: str, old_after: str, facts: dict) -> str:
    current = facts["startup"]["development"]
    old_speedup = q2(Decimal(old_before) / Decimal(old_after))
    return (
        text.replace(old_before, current["baselineSeconds"])
        .replace(old_after, current["endpointSeconds"])
        .replace(f"{old_speedup}×", f"{metrics(facts)['speedup']}×")
    )


def rewrite_technical(text: str, old_before: str, old_after: str, facts: dict) -> str:
    current = facts["startup"]["development"]
    before = current["baselineSeconds"]
    after = current["endpointSeconds"]
    for old, new in (
        (f"From {old_before} seconds to {old_after}", f"From {before} seconds to {after}"),
        (f"{old_before} seconds to {old_after} seconds", f"{before} seconds to {after} seconds"),
        (f"{old_before} → {old_after}", f"{before} → {after}"),
        (f"instead of {old_before}", f"instead of {before}"),
        (f"current retained accelerated development endpoint is **{old_after} seconds**",
         f"current retained accelerated development endpoint is **{after} seconds**"),
        (f"current retained development endpoint is **{old_after} seconds**",
         f"current retained development endpoint is **{after} seconds**"),
    ):
        text = text.replace(old, new)
    return text


def render_headlines(facts: dict) -> str:
    current = facts["startup"]["development"]
    historical = facts["startup"]["historicalSameProfileAB"]
    gate = facts["startup"]["validatedAcceleratedGate"]
    before = current["baselineSeconds"]
    after = current["endpointSeconds"]
    return f"""# Machine-audited benchmark facts

This page is generated from [`project-facts.json`](project-facts.json) by `scripts/sync_project_facts.py`. The full technical history and retained evidence live elsewhere.

All rows below refer to the {current['modCount']}-mod {current['hardware']} development installation running Starsector {current['gameBuild']} with {current['runtime']}.

| Fact | Result |
| --- | ---: |
| Earlier validated accelerated gate, {current['modCount']}-mod profile | **{gate['seconds']}s** |
| Historical same-profile ordinary baseline, {current['modCount']} mods | **{historical['baselineSeconds']}s** |
| Historical same-profile accelerated A/B median, {current['modCount']} mods | **{historical['endpointSeconds']}s** |
| Historical A/B elapsed-time reduction | **{historical['deltaSeconds']}s** |
| Historical A/B percentage reduction | **{historical['reductionPercent']}%** |
| Current retained accelerated development endpoint, {current['modCount']} mods | **{after}s** |
| Current maintainer-selected ordinary development baseline | **{before}s** |

The current readable development headline is **{before}s → {after}s**. The {historical['baselineSeconds']}s → {historical['endpointSeconds']}s pair remains the retained interleaved A/B comparison for the separate attribution question it measured.
"""


def evidence_errors(facts: dict) -> list[str]:
    current = facts["startup"]["development"]
    historical = facts["startup"]["historicalSameProfileAB"]
    gate = facts["startup"]["validatedAcceleratedGate"]
    checks = (
        (current["baselineEvidence"], (current["baselineSeconds"],)),
        (current["endpointEvidence"], (current["endpointSeconds"],)),
        (historical["evidence"], (
            historical["baselineSeconds"], historical["endpointSeconds"],
            historical["deltaSeconds"], historical["reductionPercent"],
        )),
        (gate["evidence"], (gate["seconds"],)),
    )
    errors: list[str] = []
    for relative, tokens in checks:
        if not isinstance(relative, str) or not relative.startswith("docs/evidence/"):
            errors.append(f"invalid evidence path: {relative!r}")
            continue
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing evidence: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        missing = [token for token in tokens if token not in text]
        if missing:
            errors.append(f"{relative}: missing {', '.join(missing)}")
    return errors


def expected_files(facts: dict) -> dict[Path, str]:
    old_before, old_after = rendered_values()
    expected: dict[Path, str] = {}
    for relative in PUBLIC_COPY:
        path = ROOT / relative
        expected[path] = rewrite_public(path.read_text(encoding="utf-8"), old_before, old_after, facts)
    for relative in TECHNICAL_COPY:
        path = ROOT / relative
        expected[path] = rewrite_technical(path.read_text(encoding="utf-8"), old_before, old_after, facts)
    expected[HEADLINES] = render_headlines(facts)
    return expected


def presence_errors(facts: dict) -> list[str]:
    current = facts["startup"]["development"]
    speedup = metrics(facts)["speedup"]
    errors: list[str] = []
    for relative in PUBLIC_COPY:
        text = (ROOT / relative).read_text(encoding="utf-8")
        if current["baselineSeconds"] not in text or current["endpointSeconds"] not in text:
            errors.append(relative)
    for relative in SPEEDUP_COPY:
        if f"{speedup}×" not in (ROOT / relative).read_text(encoding="utf-8"):
            errors.append(relative)
    return errors


def sync(write: bool) -> int:
    facts = load_facts()
    drift: list[str] = []
    for path, expected in expected_files(facts).items():
        if path.read_text(encoding="utf-8") == expected:
            continue
        drift.append(path.relative_to(ROOT).as_posix())
        if write:
            path.write_text(expected, encoding="utf-8")
    drift = (presence_errors(facts) if write else drift + presence_errors(facts)) + evidence_errors(facts)
    if drift:
        print("project facts are out of sync: " + ", ".join(sorted(set(drift))))
        print("run `python3 scripts/sync_project_facts.py --write`")
        return 1
    current = facts["startup"]["development"]
    result = metrics(facts)
    print(
        f"{current['baselineSeconds']}s -> {current['endpointSeconds']}s; "
        f"{result['speedup']}x; {result['saved']}s saved; {result['reduction']}% reduction"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    return sync(args.write)


if __name__ == "__main__":
    raise SystemExit(main())
