#!/usr/bin/env python3
"""Synthetic regression tests for starsector_mod_tax_triage.py."""
import starsector_mod_tax_triage as triage


def owner(mod_id, total, hitch50=0, hitch100=0, overlap=0.0):
    return {
        "ownerKey": f"mod:{mod_id}",
        "ownerKind": "MOD",
        "ownerName": mod_id,
        "modId": mod_id,
        "classes": 1,
        "calls": 100,
        "totalMillis": total,
        "maximumCallbackMillis": 25.0,
        "callsOverlapping50msFrames": hitch50,
        "callsOverlapping100msFrames": hitch100,
        "frameAssociationsOver50ms": hitch50,
        "frameAssociationsOver100ms": hitch100,
        "callbackOverlapMillis": overlap,
        "maximumAssociatedFrameMillis": 120.0 if hitch100 else 60.0 if hitch50 else 0.0,
    }


def tax(frame_rows, hitch_rows):
    return {
        "frameTaxBasis": "synthetic",
        "hitchTaxBasis": "synthetic",
        "frameTax": frame_rows,
        "hitchTax": hitch_rows,
    }


def test_join_promotes_static_findings_only_for_runtime_observed_mods():
    unresolved = {
        "ownerKey": "dynamic:janino",
        "ownerKind": "DYNAMIC_JANINO",
        "ownerName": "Janino generated/loaded class",
        "modId": None,
        "classes": 2,
        "calls": 40,
        "totalMillis": 88.0,
        "maximumCallbackMillis": 12.0,
    }
    runtime = {
        "campaignEngineTimes": {
            "scriptOwnerTax": tax(
                [owner("hot_mod", 300.0), owner("steady_mod", 200.0), unresolved],
                [owner("hot_mod", 0.0, hitch50=2, hitch100=1, overlap=45.0)],
            )
        },
        "campaignMarketFleetTimes": {
            "marketIndustryClassesOwnerTax": tax(
                [owner("hot_mod", 500.0)],
                [owner("hot_mod", 0.0, hitch50=1, overlap=10.0)],
            )
        },
    }
    hot_patterns = {
        "findings": [
            {
                "modId": "hot_mod", "score": 15, "className": "x.Hot",
                "methodName": "advance", "pattern": "SORT_OR_SHUFFLE",
            },
            {
                "modId": "static_only", "score": 30, "className": "y.Static",
                "methodName": "render", "pattern": "GL_QUERY_OR_SYNC",
            },
        ]
    }

    result = triage.triage(runtime, hot_patterns)
    by_mod = {row["modId"]: row for row in result["mods"]}

    assert result["runtimeOwnerTaxFamilies"] == 2
    assert result["runtimeObservedModCount"] == 2
    assert result["staticObservedModCount"] == 2
    assert by_mod["hot_mod"]["priority"] == "A_HITCH_AND_STATIC"
    assert len(by_mod["hot_mod"]["runtimeFrameTaxFamilies"]) == 2
    # Profiler families stay separate. Equal family-local ranks use larger measured time first.
    assert [row["totalMillis"] for row in by_mod["hot_mod"]["runtimeFrameTaxFamilies"]] == [500.0, 300.0]
    assert len(by_mod["hot_mod"]["runtimeHitchTaxFamilies"]) == 2
    assert by_mod["hot_mod"]["promotedStaticFindings"][0]["pattern"] == "SORT_OR_SHUFFLE"
    assert by_mod["steady_mod"]["priority"] == "D_STEADY_RUNTIME_ONLY"
    assert by_mod["static_only"]["priority"] == "E_STATIC_ONLY"
    assert by_mod["static_only"]["promotedStaticFindings"] == []
    assert result["unresolvedRuntimeOwners"][0]["ownerKind"] == "DYNAMIC_JANINO"


def test_hitch_runtime_without_static_stays_runtime_only():
    runtime = {
        "nested": {
            "thingOwnerTax": tax(
                [owner("runtime_only", 12.0)],
                [owner("runtime_only", 0.0, hitch50=1, overlap=4.0)],
            )
        }
    }
    result = triage.triage(runtime, {"findings": []})
    assert result["mods"][0]["priority"] == "C_HITCH_RUNTIME_ONLY"


def main():
    test_join_promotes_static_findings_only_for_runtime_observed_mods()
    test_hitch_runtime_without_static_stays_runtime_only()
    print("starsector_mod_tax_triage: ok")


if __name__ == "__main__":
    main()
