#!/usr/bin/env python3
"""Synchronize repeated public facts from docs/project-facts.json.

Edit the facts file, then run:

    python3 scripts/sync_project_facts.py --write

CI uses --check. The script intentionally owns only repeated headline/product facts; experiment
records and evidence remain hand-authored historical records.
"""

from __future__ import annotations

import argparse
import json
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FACTS_PATH = ROOT / "docs/project-facts.json"
CLAIMS_PATH = ROOT / "docs/claims.json"
HEADLINES_PATH = ROOT / "docs/claim-headlines.md"

# These surfaces present the current development result as current copy. Replacing the old selected
# values here is intentional when the canonical facts change.
PUBLIC_COPY_FILES = (
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
)

# These are historical narratives. Only the current-headline phrases are rewritten; earlier values
# elsewhere in the files remain part of the chronology.
TECHNICAL_COPY_FILES = (
    "docs/optimization-history.md",
    "docs/technical-writeup-draft.md",
)

SPEEDUP_FILES = (
    "docs/beta-announcement-draft.md",
    "docs/beta-announcement-leo-draft.md",
    "docs/github-sponsors-page-draft.md",
    "docs/leo-talking-points.md",
    "docs/patreon-page-draft.md",
    "docs/public-writing-sales-inventory.md",
    "docs/release-post-draft.md",
    "docs/releases/0.1.0.md",
)

FORMAT = "preflight-project-facts-v1"
TWO_PLACES = Decimal("0.01")


class FactSyncError(ValueError):
    pass


def decimal_text(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise FactSyncError(f"{name} must be a decimal string")
    try:
        number = Decimal(value)
    except Exception as error:  # Decimal raises several subclasses depending on input.
        raise FactSyncError(f"{name} must be decimal: {value!r}") from error
    if not number.is_finite() or number <= 0:
        raise FactSyncError(f"{name} must be positive and finite")
    return value


def q2(value: Decimal) -> str:
    return format(value.quantize(TWO_PLACES, rounding=ROUND_HALF_UP), "f")


def load_facts(path: Path = FACTS_PATH) -> dict:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("format") != FORMAT:
        raise FactSyncError(f"project facts must use format {FORMAT}")
    startup = payload.get("startup")
    if not isinstance(startup, dict):
        raise FactSyncError("startup facts must be an object")
    development = startup.get("development")
    historical = startup.get("historicalSameProfileAB")
    if not isinstance(development, dict) or not isinstance(historical, dict):
        raise FactSyncError("startup development and historicalSameProfileAB must be objects")
    decimal_text(development.get("baselineSeconds"), "development baselineSeconds")
    decimal_text(development.get("endpointSeconds"), "development endpointSeconds")
    decimal_text(historical.get("baselineSeconds"), "historical baselineSeconds")
    decimal_text(historical.get("endpointSeconds"), "historical endpointSeconds")
    decimal_text(historical.get("deltaSeconds"), "historical deltaSeconds")
    decimal_text(historical.get("reductionPercent"), "historical reductionPercent")
    decimal_text(startup.get("validatedAcceleratedGateSeconds"), "validatedAcceleratedGateSeconds")
    mod_count = development.get("modCount")
    if not isinstance(mod_count, int) or mod_count <= 0:
        raise FactSyncError("development modCount must be a positive integer")
    for key in ("hardware", "gameBuild", "runtime", "baselineEvidence", "endpointEvidence"):
        if not isinstance(development.get(key), str) or not development[key].strip():
            raise FactSyncError(f"development {key} must be a non-empty string")
    storage = payload.get("storage")
    if not isinstance(storage, dict):
        raise FactSyncError("storage facts must be an object")
    decimal_text(storage.get("compactSteadyStateGB"), "compactSteadyStateGB")
    return payload


def derived(facts: dict) -> dict[str, str]:
    development = facts["startup"]["development"]
    before = Decimal(development["baselineSeconds"])
    after = Decimal(development["endpointSeconds"])
    return {
        "speedup": q2(before / after),
        "savedSeconds": q2(before - after),
        "reductionPercent": q2((Decimal(1) - after / before) * Decimal(100)),
    }


def current_selected_values(claims: dict) -> tuple[str, str]:
    baseline = None
    endpoint = None
    entries = claims.get("publishedQuantities")
    if not isinstance(entries, list):
        raise FactSyncError("docs/claims.json has no publishedQuantities list")
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("type") != "seconds":
            continue
        derivation = str(entry.get("derivation", ""))
        if "current development/public baseline" in derivation:
            baseline = str(entry.get("value"))
        if "current accelerated development endpoint" in derivation:
            endpoint = str(entry.get("value"))
    if baseline is None or endpoint is None:
        raise FactSyncError("could not locate current startup baseline/endpoint in docs/claims.json")
    return baseline, endpoint


def update_claims(claims: dict, facts: dict) -> None:
    development = facts["startup"]["development"]
    before = development["baselineSeconds"]
    after = development["endpointSeconds"]
    for entry in claims["publishedQuantities"]:
        if not isinstance(entry, dict) or entry.get("type") != "seconds":
            continue
        derivation = str(entry.get("derivation", ""))
        if "current development/public baseline" in derivation:
            entry["value"] = before
            entry["evidence"] = development["baselineEvidence"]
            entry["derivation"] = (
                "Recent ordinary startup observation selected by the maintainer as the current "
                "development/public baseline under the same game-log startup clock."
            )
        elif "current accelerated development endpoint" in derivation:
            entry["value"] = after
            entry["evidence"] = development["endpointEvidence"]
            entry["derivation"] = (
                "Retained current accelerated development endpoint under the same game-log startup "
                "clock used by the development record. The historical same-profile A/B campaign "
                "answers a separate comparison question."
            )

    # Keep the accepted accelerated development claim aligned with the selected endpoint when the
    # evidence path is the endpoint evidence named by the facts file.
    for claim in claims.get("claims", []):
        if not isinstance(claim, dict):
            continue
        evidence = claim.get("evidence")
        if isinstance(evidence, dict) and evidence.get("path") == development["endpointEvidence"]:
            result = claim.get("result")
            if isinstance(result, dict):
                result["seconds"] = float(after)


def rewrite_public_copy(text: str, old_before: str, old_after: str, facts: dict) -> str:
    development = facts["startup"]["development"]
    new_before = development["baselineSeconds"]
    new_after = development["endpointSeconds"]
    old_speedup = q2(Decimal(old_before) / Decimal(old_after))
    new_speedup = derived(facts)["speedup"]
    return (
        text.replace(old_before, new_before)
        .replace(old_after, new_after)
        .replace(f"{old_speedup}×", f"{new_speedup}×")
    )


def rewrite_technical_copy(text: str, old_before: str, old_after: str, facts: dict) -> str:
    new_before = facts["startup"]["development"]["baselineSeconds"]
    new_after = facts["startup"]["development"]["endpointSeconds"]
    pairs = (
        (f"From {old_before} seconds to {old_after}", f"From {new_before} seconds to {new_after}"),
        (f"{old_before} seconds to {old_after} seconds", f"{new_before} seconds to {new_after} seconds"),
        (f"{old_before} → {old_after}", f"{new_before} → {new_after}"),
        (f"instead of {old_before}", f"instead of {new_before}"),
        (f"current retained accelerated development endpoint is **{old_after} seconds**",
         f"current retained accelerated development endpoint is **{new_after} seconds**"),
        (f"current retained development endpoint is **{old_after} seconds**",
         f"current retained development endpoint is **{new_after} seconds**"),
    )
    for old, new in pairs:
        text = text.replace(old, new)
    return text


def render_headlines(facts: dict) -> str:
    development = facts["startup"]["development"]
    historical = facts["startup"]["historicalSameProfileAB"]
    gate = facts["startup"]["validatedAcceleratedGateSeconds"]
    before = development["baselineSeconds"]
    after = development["endpointSeconds"]
    return f"""# Machine-audited claim headlines

This page is generated from [`project-facts.json`](project-facts.json) by `scripts/sync_project_facts.py`. The full technical history and retained evidence live elsewhere.

All rows below refer to the {development['modCount']}-mod {development['hardware']} development installation running Starsector {development['gameBuild']} with {development['runtime']}.

| Claim | Result |
| --- | ---: |
| Earlier validated accelerated gate, {development['modCount']}-mod profile | **{gate}s** |
| Historical same-profile ordinary baseline, {development['modCount']} mods | **{historical['baselineSeconds']}s** |
| Historical same-profile accelerated A/B median, {development['modCount']} mods | **{historical['endpointSeconds']}s** |
| Historical A/B elapsed-time reduction | **{historical['deltaSeconds']}s** |
| Historical A/B percentage reduction | **{historical['reductionPercent']}%** |
| Current retained accelerated development endpoint, {development['modCount']} mods | **{after}s** |
| Current maintainer-selected ordinary development baseline | **{before}s** |

The current readable development headline is **{before}s → {after}s**. The {historical['baselineSeconds']}s → {historical['endpointSeconds']}s pair remains the retained interleaved A/B comparison for the separate attribution question it measured.
"""


def candidate_files(facts: dict) -> dict[Path, str]:
    claims = json.loads(CLAIMS_PATH.read_text(encoding="utf-8"))
    old_before, old_after = current_selected_values(claims)
    outputs: dict[Path, str] = {}

    for relative in PUBLIC_COPY_FILES:
        path = ROOT / relative
        outputs[path] = rewrite_public_copy(path.read_text(encoding="utf-8"), old_before, old_after, facts)

    for relative in TECHNICAL_COPY_FILES:
        path = ROOT / relative
        outputs[path] = rewrite_technical_copy(path.read_text(encoding="utf-8"), old_before, old_after, facts)

    update_claims(claims, facts)
    outputs[CLAIMS_PATH] = json.dumps(claims, indent=2, ensure_ascii=False) + "\n"
    outputs[HEADLINES_PATH] = render_headlines(facts)
    return outputs


def check_expected_presence(facts: dict) -> list[str]:
    development = facts["startup"]["development"]
    before = development["baselineSeconds"]
    after = development["endpointSeconds"]
    speedup = derived(facts)["speedup"]
    problems: list[str] = []
    for relative in PUBLIC_COPY_FILES:
        text = (ROOT / relative).read_text(encoding="utf-8")
        if before not in text or after not in text:
            problems.append(f"{relative}: missing current startup range {before} / {after}")
    for relative in SPEEDUP_FILES:
        text = (ROOT / relative).read_text(encoding="utf-8")
        if f"{speedup}×" not in text:
            problems.append(f"{relative}: missing derived speedup {speedup}×")
    return problems


def synchronize(write: bool) -> int:
    facts = load_facts()
    outputs = candidate_files(facts)
    drift: list[str] = []
    for path, expected in outputs.items():
        current = path.read_text(encoding="utf-8")
        if current == expected:
            continue
        relative = path.relative_to(ROOT).as_posix()
        drift.append(relative)
        if write:
            path.write_text(expected, encoding="utf-8")

    if write:
        # Re-evaluate after writing; this also catches a managed copy surface that never carried the
        # desired value in the first place.
        drift = check_expected_presence(facts)
    else:
        drift.extend(check_expected_presence(facts))

    if drift:
        unique = sorted(set(drift))
        action = "run `python3 scripts/sync_project_facts.py --write`" if not write else "review sync errors"
        print("project facts are out of sync: " + ", ".join(unique))
        print(action)
        return 1

    values = derived(facts)
    development = facts["startup"]["development"]
    print(
        f"project facts synchronized: {development['baselineSeconds']}s -> "
        f"{development['endpointSeconds']}s, {values['speedup']}x, "
        f"{values['savedSeconds']}s saved, {values['reductionPercent']}% reduction"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="rewrite managed copy and claim files")
    mode.add_argument("--check", action="store_true", help="fail when managed files differ from the facts")
    args = parser.parse_args()
    return synchronize(write=args.write)


if __name__ == "__main__":
    raise SystemExit(main())
