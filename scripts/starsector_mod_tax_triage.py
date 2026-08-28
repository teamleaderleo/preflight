#!/usr/bin/env python3
"""Join per-mod runtime tax with offline bytecode leads without double-counting nested profilers."""
import argparse
import json
from collections import defaultdict


OWNER_TAX_SUFFIX = "OwnerTax"


def owner_tax_reports(value, path="$"):
    """Yield (path, owner-tax mapping) for every emitted runtime owner-tax section."""
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}"
            if (key.endswith(OWNER_TAX_SUFFIX)
                    and isinstance(child, dict)
                    and isinstance(child.get("frameTax"), list)
                    and isinstance(child.get("hitchTax"), list)):
                yield child_path, child
            yield from owner_tax_reports(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from owner_tax_reports(child, f"{path}[{index}]")


def _mod_id(row):
    if not isinstance(row, dict) or row.get("ownerKind") != "MOD":
        return None
    value = row.get("modId")
    return value if isinstance(value, str) and value else None


def runtime_evidence(runtime):
    """Keep each profiler family separate because campaign timers can be inclusive/nested."""
    by_mod = defaultdict(lambda: {"frameTax": [], "hitchTax": []})
    unresolved = []
    family_count = 0
    for path, report in owner_tax_reports(runtime):
        family_count += 1
        for rank, row in enumerate(report.get("frameTax") or [], 1):
            if not isinstance(row, dict):
                continue
            mod_id = _mod_id(row)
            entry = {
                "family": path,
                "rank": rank,
                "totalMillis": row.get("totalMillis"),
                "calls": row.get("calls"),
                "classes": row.get("classes"),
                "maximumCallbackMillis": row.get("maximumCallbackMillis"),
            }
            if mod_id:
                by_mod[mod_id]["frameTax"].append(entry)
            elif row.get("ownerKind") in {"UNRESOLVED", "DYNAMIC_JANINO"}:
                unresolved.append({
                    "family": path,
                    "rank": rank,
                    "ownerKey": row.get("ownerKey"),
                    "ownerKind": row.get("ownerKind"),
                    "ownerName": row.get("ownerName"),
                    "totalMillis": row.get("totalMillis"),
                })
        for rank, row in enumerate(report.get("hitchTax") or [], 1):
            if not isinstance(row, dict):
                continue
            mod_id = _mod_id(row)
            if not mod_id:
                continue
            over_50 = int(row.get("callsOverlapping50msFrames") or 0)
            over_100 = int(row.get("callsOverlapping100msFrames") or 0)
            associations_50 = int(row.get("frameAssociationsOver50ms") or 0)
            associations_100 = int(row.get("frameAssociationsOver100ms") or 0)
            overlap = float(row.get("callbackOverlapMillis") or 0.0)
            if over_50 == 0 and over_100 == 0 and associations_50 == 0 and overlap <= 0.0:
                continue
            by_mod[mod_id]["hitchTax"].append({
                "family": path,
                "rank": rank,
                "callsOverlapping50msFrames": over_50,
                "callsOverlapping100msFrames": over_100,
                "frameAssociationsOver50ms": associations_50,
                "frameAssociationsOver100ms": associations_100,
                "callbackOverlapMillis": overlap,
                "maximumAssociatedFrameMillis": row.get("maximumAssociatedFrameMillis"),
            })
    return by_mod, unresolved, family_count


def static_evidence(hot_patterns):
    by_mod = defaultdict(list)
    for finding in hot_patterns.get("findings") or []:
        if not isinstance(finding, dict):
            continue
        mod_id = finding.get("modId")
        if isinstance(mod_id, str) and mod_id:
            by_mod[mod_id].append(finding)
    for findings in by_mod.values():
        findings.sort(key=lambda row: (
            -int(row.get("score") or 0),
            str(row.get("className") or ""),
            str(row.get("methodName") or ""),
            str(row.get("pattern") or ""),
        ))
    return by_mod


def priority(frame_tax, hitch_tax, static_findings):
    """Discrete evidence tier; avoids inventing a unitless score from overlapping timers."""
    has_runtime = bool(frame_tax)
    has_hitch = bool(hitch_tax)
    has_static = bool(static_findings)
    if has_hitch and has_static:
        return "A_HITCH_AND_STATIC"
    if has_runtime and has_static:
        return "B_STEADY_AND_STATIC"
    if has_hitch:
        return "C_HITCH_RUNTIME_ONLY"
    if has_runtime:
        return "D_STEADY_RUNTIME_ONLY"
    return "E_STATIC_ONLY"


def triage(runtime, hot_patterns, static_limit_per_mod=12):
    runtime_mods, unresolved, family_count = runtime_evidence(runtime)
    static_mods = static_evidence(hot_patterns)
    mods = []
    all_mod_ids = sorted(set(runtime_mods) | set(static_mods))
    for mod_id in all_mod_ids:
        runtime_entry = runtime_mods.get(mod_id, {"frameTax": [], "hitchTax": []})
        frame_tax = sorted(
            runtime_entry["frameTax"],
            key=lambda row: (row["rank"], -(float(row.get("totalMillis") or 0.0)), row["family"]),
        )
        hitch_tax = sorted(
            runtime_entry["hitchTax"],
            key=lambda row: (
                -int(row.get("callsOverlapping100msFrames") or 0),
                -int(row.get("callsOverlapping50msFrames") or 0),
                -float(row.get("callbackOverlapMillis") or 0.0),
                row["rank"],
                row["family"],
            ),
        )
        all_static_findings = static_mods.get(mod_id, [])
        static_findings = all_static_findings[:static_limit_per_mod]
        mods.append({
            "modId": mod_id,
            "priority": priority(frame_tax, hitch_tax, static_findings),
            "bestFrameTaxRank": min((row["rank"] for row in frame_tax), default=None),
            "runtimeFrameTaxFamilies": frame_tax,
            "runtimeHitchTaxFamilies": hitch_tax,
            "promotedStaticFindings": static_findings if frame_tax or hitch_tax else [],
            "staticFindingCount": len(all_static_findings),
            "runtimeObserved": bool(frame_tax or hitch_tax),
        })
    tier_order = {
        "A_HITCH_AND_STATIC": 0,
        "B_STEADY_AND_STATIC": 1,
        "C_HITCH_RUNTIME_ONLY": 2,
        "D_STEADY_RUNTIME_ONLY": 3,
        "E_STATIC_ONLY": 4,
    }
    mods.sort(key=lambda row: (
        tier_order[row["priority"]],
        row["bestFrameTaxRank"] if row["bestFrameTaxRank"] is not None else 10**9,
        row["modId"],
    ))
    return {
        "format": "starsector-preflight-mod-tax-triage-v1",
        "classification": (
            "diagnostic join only; nested/inclusive runtime profiler families remain separate and "
            "static bytecode findings are promoted only for runtime-observed mods"
        ),
        "runtimeOwnerTaxFamilies": family_count,
        "runtimeObservedModCount": sum(1 for row in mods if row["runtimeObserved"]),
        "staticObservedModCount": len(static_mods),
        "unresolvedRuntimeOwners": unresolved,
        "mods": mods,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("runtime_report", help="runtime-frame-report.json from the matching game run")
    parser.add_argument("hot_patterns", help="JSON from `classpath hot-patterns`")
    parser.add_argument("--static-limit-per-mod", type=int, default=12)
    args = parser.parse_args(argv)
    if args.static_limit_per_mod < 1 or args.static_limit_per_mod > 1000:
        parser.error("--static-limit-per-mod must be between 1 and 1000")
    with open(args.runtime_report, encoding="utf-8") as source:
        runtime = json.load(source)
    with open(args.hot_patterns, encoding="utf-8") as source:
        hot_patterns = json.load(source)
    print(json.dumps(
        triage(runtime, hot_patterns, args.static_limit_per_mod),
        indent=2,
        sort_keys=True,
    ))


if __name__ == "__main__":
    main()
