#!/usr/bin/env python3
"""Build a local, reproducible paper-balance model for an enabled Starsector profile.

The tool reads the installed game and enabled mods in game load order. It writes only derived data
to the requested output directory; it never edits the installation and never copies source assets.
Scores are conditional models, not declarations of universal combat strength. Scripted systems,
AI behavior, armor geometry, ammunition economy, skills, officers, and encounter context require
empirical calibration in the simulator.
"""

from __future__ import annotations

import argparse
import bisect
import collections
import copy
import csv
import json
import math
import re
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


CORE_RELATIVE = Path("Contents/Resources/Java")
ENABLED_RELATIVE = Path("mods/enabled_mods.json")
OUTPUT_FORMAT = "starsector-paper-balance-v2"
SUSTAINED_DPS_WINDOW_SECONDS = 60.0
PD_ANTI_SHIP_WEIGHT = 0.25
PROFILE_WEIGHTS = {
    # "survival" is absolute effective durability across the full player-ship population. Keeping
    # it separate from durability/DP prevents tiny denominators from making fragile frigates look
    # universally optimal while preserving their efficiency and specialist-role signal.
    "balanced": {"mobility": 0.15, "durability": 0.25, "survival": 0.15, "flux": 0.20,
                 "firepower": 0.20, "logistics": 0.05},
    "mobility": {"mobility": 0.45, "durability": 0.10, "survival": 0.05, "flux": 0.15,
                 "firepower": 0.20, "logistics": 0.05},
    "durability": {"mobility": 0.05, "durability": 0.30, "survival": 0.30, "flux": 0.15,
                   "firepower": 0.15, "logistics": 0.05},
    "firepower": {"mobility": 0.10, "durability": 0.15, "survival": 0.10, "flux": 0.15,
                  "firepower": 0.45, "logistics": 0.05},
}
SIZE_WEIGHT = {"SMALL": 1.0, "MEDIUM": 3.0, "LARGE": 7.0}
SIZE_ORDER = {"SMALL": 1, "MEDIUM": 2, "LARGE": 3}


def strip_starsector_comments(text: str) -> str:
    """Remove #, //, and block comments without touching quoted strings."""
    text = text.lstrip("\ufeff")
    out: list[str] = []
    index = 0
    quote: str | None = None
    while index < len(text):
        char = text[index]
        if quote:
            out.append(char)
            if char == "\\" and index + 1 < len(text):
                index += 1
                out.append(text[index])
            elif char == quote:
                quote = None
            index += 1
            continue
        if char in ('"', "'"):
            quote = char
            out.append(char)
            index += 1
            continue
        if char == "#":
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and index + 1 < len(text) and text[index + 1] == "/":
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and index + 1 < len(text) and text[index + 1] == "*":
            index += 2
            while index + 1 < len(text) and text[index:index + 2] != "*/":
                index += 1
            index = min(len(text), index + 2)
            continue
        out.append(char)
        index += 1
    return "".join(out)


def normalize_single_quoted_strings(text: str) -> str:
    """Convert dialect single-quoted strings while preserving apostrophes in double strings."""
    out: list[str] = []
    index = 0
    in_double = False
    while index < len(text):
        char = text[index]
        if in_double:
            out.append(char)
            if char == "\\" and index + 1 < len(text):
                index += 1
                out.append(text[index])
            elif char == '"':
                in_double = False
            index += 1
            continue
        if char == '"':
            in_double = True
            out.append(char)
            index += 1
            continue
        if char != "'":
            out.append(char)
            index += 1
            continue
        index += 1
        value: list[str] = []
        while index < len(text):
            char = text[index]
            if char == "\\" and index + 1 < len(text):
                index += 1
                escaped = text[index]
                value.append("'" if escaped == "'" else "\\" + escaped)
            elif char == "'":
                index += 1
                break
            else:
                value.append(char)
            index += 1
        out.append(json.dumps("".join(value)))
    return "".join(out)


def quote_bare_tokens(text: str) -> str:
    """Quote dialect bare keys and enum values without matching text inside strings."""
    out: list[str] = []
    stack: list[str] = []
    index = 0
    in_string = False
    previous_significant = ""
    while index < len(text):
        char = text[index]
        if in_string:
            out.append(char)
            if char == "\\" and index + 1 < len(text):
                index += 1
                out.append(text[index])
            elif char == '"':
                in_string = False
                previous_significant = '"'
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if char in "[{":
            stack.append(char)
        elif char in "]}" and stack:
            stack.pop()
        if char.isalpha() or char in "_$":
            stop = index + 1
            while stop < len(text) and (text[stop].isalnum() or text[stop] in "_$.-"):
                stop += 1
            token = text[index:stop]
            look = stop
            while look < len(text) and text[look].isspace():
                look += 1
            next_significant = text[look] if look < len(text) else ""
            is_key = next_significant == ":" and bool(stack and stack[-1] == "{")
            is_value = (previous_significant in ":[" or
                        (previous_significant == "," and bool(stack and stack[-1] == "[")))
            if is_value and stack and stack[-1] == "[":
                end = stop
                while end < len(text) and text[end] not in ",]":
                    end += 1
                candidate = text[index:end].strip()
                if (candidate not in {"true", "false", "null", "NaN", "Infinity"}
                        and not any(mark in candidate for mark in "{[\"")):
                    out.append(json.dumps(candidate))
                    previous_significant = '"'
                    index = end
                    continue
            if is_key or (is_value and token not in {"true", "false", "null", "NaN", "Infinity"}):
                out.append(json.dumps(token))
            else:
                out.append(token)
            previous_significant = '"' if is_key or is_value else token[-1]
            index = stop
            continue
        out.append(char)
        if not char.isspace():
            previous_significant = char
        index += 1
    return "".join(out)


def normalize_dialect_numbers(text: str) -> str:
    """Normalize .8, 0., and 00 forms accepted by the game's permissive reader."""
    out: list[str] = []
    index = 0
    in_string = False
    while index < len(text):
        char = text[index]
        if in_string:
            out.append(char)
            if char == "\\" and index + 1 < len(text):
                index += 1
                out.append(text[index])
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if char == "." and index + 1 < len(text) and text[index + 1].isdigit():
            if not out or not out[-1].isdigit():
                out.append("0")
            out.append(char)
            index += 1
            continue
        if char == "." and out and out[-1].isdigit() and (
                index + 1 == len(text) or not text[index + 1].isdigit()):
            out.append(".0")
            index += 1
            continue
        if char.isdigit() and (not out or not (out[-1].isalnum() or out[-1] in "_.")):
            stop = index + 1
            while stop < len(text) and text[stop].isdigit():
                stop += 1
            digits = text[index:stop]
            out.append(str(int(digits)) if len(digits) > 1 and digits.startswith("0") else digits)
            index = stop
            continue
        out.append(char)
        index += 1
    return "".join(out)


def loads_starsector_json(text: str) -> Any:
    cleaned = strip_starsector_comments(text)
    cleaned = normalize_single_quoted_strings(cleaned)
    # Quote bare array values before normalizing numbers so identifiers such as ``WS 003`` keep
    # their significant leading zero rather than becoming ``WS 3``.
    cleaned = quote_bare_tokens(cleaned)
    cleaned = normalize_dialect_numbers(cleaned)
    cleaned = re.sub(r"(?<=[\[:,])\s*\+(?=\d)", " ", cleaned)
    cleaned = re.sub(r";(?=\s*[\"'}\]])", ",", cleaned)
    cleaned = re.sub(r"(?<=\d)[fFdDlL](?=\s*[,}\]])", "", cleaned)
    while True:
        without_trailing = re.sub(r",\s*([}\]])", r"\1", cleaned)
        if without_trailing == cleaned:
            break
        cleaned = without_trailing
    value, _end = json.JSONDecoder(strict=False).raw_decode(cleaned.lstrip())
    return value


def read_starsector_json(path: Path) -> Any:
    return loads_starsector_json(path.read_text(encoding="utf-8-sig", errors="replace"))


@dataclass(frozen=True)
class Provider:
    provider_id: str
    name: str
    root: Path
    order: int
    core: bool = False


def resolve_providers(game: Path) -> tuple[list[Provider], dict[str, Any]]:
    enabled_document = read_starsector_json(game / ENABLED_RELATIVE)
    enabled = enabled_document.get("enabledMods")
    if not isinstance(enabled, list) or not all(isinstance(value, str) for value in enabled):
        raise ValueError("enabled_mods.json does not contain a string enabledMods array")

    candidates: dict[str, list[tuple[Path, dict[str, Any]]]] = collections.defaultdict(list)
    parse_failures: list[dict[str, str]] = []
    for metadata in sorted((game / "mods").rglob("mod_info.json")):
        try:
            document = read_starsector_json(metadata)
            mod_id = document.get("id")
            if isinstance(mod_id, str) and mod_id:
                candidates[mod_id].append((metadata.parent, document))
        except (OSError, ValueError) as error:
            parse_failures.append({"file": str(metadata.relative_to(game)), "problem": str(error)[:300]})

    providers = [Provider("core", "Starsector core", game / CORE_RELATIVE, 0, True)]
    unresolved: list[str] = []
    ambiguous: dict[str, list[str]] = {}
    for order, mod_id in enumerate(enabled, 1):
        matches = candidates.get(mod_id, [])
        if not matches:
            unresolved.append(mod_id)
            continue
        matches.sort(key=lambda item: (len(item[0].relative_to(game / "mods").parts), str(item[0])))
        selected_root, document = matches[0]
        if len(matches) > 1:
            ambiguous[mod_id] = [str(root.relative_to(game)) for root, _ in matches]
        providers.append(Provider(mod_id, str(document.get("name") or mod_id), selected_root, order))
    diagnostics = {
        "enabledIds": enabled,
        "enabledCount": len(enabled),
        "resolvedCount": len(providers) - 1,
        "unresolvedIds": unresolved,
        "ambiguousIds": ambiguous,
        "metadataParseFailures": parse_failures,
    }
    return providers, diagnostics


def normalized_row(row: dict[str, str]) -> dict[str, str]:
    return {(key or "").strip().lower(): (value or "").strip() for key, value in row.items()}


def merged_csv(providers: Iterable[Provider], relative: str, id_column: str) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    winners: dict[str, dict[str, Any]] = {}
    providers_by_id: dict[str, list[str]] = collections.defaultdict(list)
    files = 0
    rows = 0
    malformed = 0
    for provider in providers:
        path = provider.root / relative
        if not path.is_file():
            continue
        files += 1
        with path.open(encoding="utf-8-sig", errors="replace", newline="") as source:
            for raw in csv.DictReader(source):
                row = normalized_row(raw)
                item_id = row.get(id_column, "")
                if not item_id or item_id.startswith("#"):
                    malformed += 1
                    continue
                rows += 1
                row["providerId"] = provider.provider_id
                row["providerName"] = provider.name
                row["providerOrder"] = provider.order
                providers_by_id[item_id].append(provider.provider_id)
                winners[item_id] = row
    return winners, {
        "files": files,
        "rows": rows,
        "winnerIds": len(winners),
        "malformedOrBlankIds": malformed,
        "overriddenIds": {key: value for key, value in providers_by_id.items() if len(value) > 1},
    }


def merged_json_specs(providers: Iterable[Provider], relative: str, suffix: str,
                      id_keys: tuple[str, ...]) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    by_logical_path: dict[str, dict[str, Any]] = {}
    path_providers: dict[str, list[str]] = collections.defaultdict(list)
    winners: dict[str, dict[str, Any]] = {}
    providers_by_id: dict[str, list[str]] = collections.defaultdict(list)
    failures: list[dict[str, str]] = []
    files = 0
    for provider in providers:
        directory = provider.root / relative
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob(f"*{suffix}")):
            files += 1
            try:
                document = read_starsector_json(path)
                logical_path = str(path.relative_to(directory))
                merged = deep_merge(by_logical_path.get(logical_path, {}), document)
                merged["providerId"] = provider.provider_id
                merged["providerName"] = provider.name
                merged["providerOrder"] = provider.order
                merged["logicalPath"] = logical_path
                by_logical_path[logical_path] = merged
                path_providers[logical_path].append(provider.provider_id)
            except (OSError, ValueError) as error:
                failures.append({"providerId": provider.provider_id,
                                 "file": str(path.relative_to(provider.root)),
                                 "problem": str(error)[:300]})
    ordered = sorted(by_logical_path.values(),
                     key=lambda document: (document.get("providerOrder", 0),
                                           document.get("logicalPath", "")))
    for document in ordered:
        item_id = next((document.get(key) for key in id_keys if document.get(key)), None)
        if not isinstance(item_id, str):
            item_id = Path(str(document["logicalPath"])).stem
        providers_by_id[item_id].append(str(document["providerId"]))
        winners[item_id] = document
    return winners, {
        "files": files,
        "winnerIds": len(winners),
        "parseFailures": failures,
        "overriddenLogicalPaths": {key: value for key, value in path_providers.items()
                                   if len(value) > 1},
        "overriddenIds": {key: value for key, value in providers_by_id.items() if len(value) > 1},
    }


def deep_merge(base: Any, overlay: Any) -> Any:
    """Match Starsector-style object overlays: objects recurse; arrays and scalars replace."""
    if not isinstance(base, dict) or not isinstance(overlay, dict):
        return overlay
    result = dict(base)
    for key, value in overlay.items():
        result[key] = deep_merge(result.get(key), value) if key in result else value
    return result


def apply_hull_skins(hulls: dict[str, dict[str, Any]],
                     hull_specs: dict[str, dict[str, Any]],
                     skins: dict[str, dict[str, Any]]) -> tuple[dict[str, dict[str, Any]],
                                                                 dict[str, dict[str, Any]],
                                                                 dict[str, Any]]:
    """Materialize skin hulls from their base CSV row and .ship specification.

    Starsector skins are real player-selectable hulls but do not have their own ship_data.csv row.
    Keeping them out loses exactly the kind of special package the balance audit needs to expose,
    such as the Brawler (LP)'s built-in Safety Overrides.
    """
    rendered_hulls = dict(hulls)
    rendered_specs = dict(hull_specs)
    missing_base_hulls: list[str] = []
    missing_base_specs: list[str] = []
    materialized = 0
    csv_overrides = {
        "hullName": "name",
        "ordnancePoints": "ordnance points",
        "suppliesPerMonth": "supplies/mo",
        "fighterBays": "fighter bays",
        "fighterbays": "fighter bays",
        "shieldEfficiency": "shield efficiency",
        "maxSpeed": "max speed",
        "baseValue": "base value",
        "systemId": "system id",
    }
    for skin_id, skin in sorted(skins.items(), key=lambda item: (
            int(item[1].get("providerOrder", 0)), item[0])):
        base_id = str(skin.get("baseHullId") or "")
        base_hull = rendered_hulls.get(base_id)
        base_spec = rendered_specs.get(base_id)
        if base_hull is None:
            missing_base_hulls.append(f"{skin_id}:{base_id or '(blank)'}")
            continue
        if base_spec is None:
            missing_base_specs.append(f"{skin_id}:{base_id or '(blank)'}")
            continue

        hull = dict(base_hull)
        for skin_key, csv_key in csv_overrides.items():
            if skin_key in skin:
                hull[csv_key] = skin[skin_key]
        if "baseValueMult" in skin:
            hull["base value"] = number(base_hull, "base value") * number(
                skin, "baseValueMult", 1.0)
        base_hints = set(re.findall(r"[a-z0-9_]+", str(base_hull.get("hints") or "").lower()))
        base_hints.update(str(value).lower() for value in (skin.get("addHints") or []))
        base_hints.difference_update(str(value).lower() for value in (skin.get("removeHints") or []))
        hull["hints"] = ", ".join(sorted(base_hints))
        base_tags = set(re.findall(r"[a-z0-9_]+", str(base_hull.get("tags") or "").lower()))
        base_tags.update(str(value).lower() for value in (skin.get("tags") or []))
        hull["tags"] = ", ".join(sorted(base_tags))
        hull.update({
            "providerId": skin.get("providerId"),
            "providerName": skin.get("providerName"),
            "providerOrder": skin.get("providerOrder"),
            "skinBaseHullId": base_id,
        })

        spec = copy.deepcopy(base_spec)
        spec["hullId"] = skin_id
        spec["hullName"] = skin.get("hullName") or spec.get("hullName") or skin_id
        spec["providerId"] = skin.get("providerId")
        spec["providerName"] = skin.get("providerName")
        spec["providerOrder"] = skin.get("providerOrder")
        spec["skinBaseHullId"] = base_id
        removed_slots = {str(value) for value in (skin.get("removeWeaponSlots") or [])}
        slot_changes = skin.get("weaponSlotChanges") or {}
        updated_slots: list[dict[str, Any]] = []
        for original in spec.get("weaponSlots") or []:
            if not isinstance(original, dict) or str(original.get("id")) in removed_slots:
                continue
            slot_id = str(original.get("id") or "")
            change = slot_changes.get(slot_id, {}) if isinstance(slot_changes, dict) else {}
            updated_slots.append(deep_merge(original, change))
        spec["weaponSlots"] = updated_slots

        base_mods = [str(value) for value in (spec.get("builtInMods") or [])]
        removed_mods = {str(value) for value in (skin.get("removeBuiltInMods") or [])}
        added_mods = [str(value) for value in (skin.get("builtInMods") or [])]
        spec["builtInMods"] = list(dict.fromkeys(
            [value for value in base_mods if value not in removed_mods] + added_mods))
        base_weapons = dict(spec.get("builtInWeapons") or {})
        for slot_id in skin.get("removeBuiltInWeapons") or []:
            base_weapons.pop(str(slot_id), None)
        base_weapons.update({str(key): value for key, value in
                             (skin.get("builtInWeapons") or {}).items()})
        spec["builtInWeapons"] = base_weapons

        rendered_hulls[skin_id] = hull
        rendered_specs[skin_id] = spec
        materialized += 1
    return rendered_hulls, rendered_specs, {
        "materialized": materialized,
        "missingBaseHulls": missing_base_hulls,
        "missingBaseSpecs": missing_base_specs,
    }


def number(row: dict[str, Any], key: str, default: float = 0.0) -> float:
    value = row.get(key)
    if value is None or value == "":
        return default
    try:
        result = float(str(value).rstrip("fFdDlL"))
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default


def truthy(value: Any) -> bool:
    return str(value or "").strip().lower() in {"true", "1", "yes"}


def java_system_signals(text: str) -> tuple[list[str], dict[str, float]]:
    text = strip_starsector_comments(text)
    signals = sorted(set(re.findall(
        r"\.get([A-Za-z0-9_]+)\(\)\.modify(?:Mult|Percent|Flat)", text)))
    constants: dict[str, float] = {}
    for name, value in re.findall(
            r"\b(?:public\s+)?static\s+(?:final\s+)?float\s+([A-Z][A-Z0-9_]*)\s*=\s*"
            r"(-?(?:\d+(?:\.\d*)?|\.\d+))f?\s*;", text):
        constants[name] = float(value)
    return signals, constants


def system_capability_groups(row: dict[str, Any], spec: dict[str, Any],
                             signals: Iterable[str]) -> list[str]:
    metadata = " ".join(str(value) for value in (
        row.get("name"), row.get("id"), row.get("tags"), spec.get("type"),
        spec.get("aiType"), spec.get("statsScript")))
    words = set(re.findall(r"[a-z0-9_]+", re.sub(
        r"(?<=[a-z0-9])(?=[A-Z])", " ", metadata).lower()))
    signal_words = {signal.lower() for signal in signals}
    system_id = str(row.get("id") or "").lower()
    groups: list[str] = []
    if (words.intersection({"offensive", "weapon", "ammo", "strike", "rof", "targeting"})
            or any("weapon" in value or "rof" in value for value in signal_words)):
        groups.append("offense")
    if (words.intersection({"defensive", "shield", "damper", "armor", "repair"})
            or any(value.startswith(("shield", "armor", "hull"))
                   or "damagetaken" in value for value in signal_words)):
        groups.append("defense")
    if (words.intersection({"movement", "engine", "burn", "jet", "jets", "teleport",
                            "skimmer", "speed", "acceleration", "temporal"})
            or any(value.startswith(("maxspeed", "acceleration", "deceleration", "turnrate"))
                   for value in signal_words)):
        groups.append("mobility")
    if (words.intersection({"emp", "interdict", "mine", "disrupt", "tractor", "mote"})
            or system_id in {"drone_strike", "emp"}):
        groups.append("control")
    if words.intersection({"sensor", "flare", "reservewing", "recall", "construction", "drone"}):
        groups.append("support")
    if any(truthy(row.get(key)) for key in ("nofiring", "noturning", "nostrafing",
                                            "noaccel", "noshield")):
        groups.append("commitment")
    return groups


def system_rows(systems: dict[str, dict[str, Any]], specs: dict[str, dict[str, Any]],
                providers: Iterable[Provider]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    provider_roots = {provider.provider_id: provider.root for provider in providers}
    rows: list[dict[str, Any]] = []
    missing_specs: list[str] = []
    source_inspected = 0
    constraint_keys = ("nofiring", "noturning", "nostrafing", "noaccel", "noshield",
                       "novent", "nodissipation", "noharddissipation", "hardflux")
    for system_id, source in systems.items():
        spec = specs.get(system_id, {})
        if not spec:
            missing_specs.append(system_id)
        script = str(spec.get("statsScript") or "")
        root = provider_roots.get(str(spec.get("providerId") or source.get("providerId")))
        script_path = root.joinpath(*script.split(".")).with_suffix(".java") if root and script else None
        signals: list[str] = []
        constants: dict[str, float] = {}
        script_source = ""
        if script_path and script_path.is_file():
            script_source = str(script_path.relative_to(root))
            signals, constants = java_system_signals(
                script_path.read_text(encoding="utf-8", errors="replace"))
            source_inspected += 1
        charge_up = number(source, "charge up")
        active = number(source, "active")
        down = number(source, "down")
        cooldown = number(source, "cooldown")
        cycle = charge_up + active + down + cooldown
        constraints = [key for key in constraint_keys if truthy(source.get(key))]
        ai_hints = spec.get("aiHints") if isinstance(spec.get("aiHints"), dict) else {}
        row = {
            "id": system_id,
            "name": source.get("name") or system_id,
            "providerId": source.get("providerId"),
            "providerName": source.get("providerName"),
            "type": str(spec.get("type") or ""),
            "aiType": str(spec.get("aiType") or ""),
            "statsScript": script,
            "scriptSource": script_source,
            "scriptSignals": signals,
            "scriptConstants": constants,
            "tags": sorted(set(re.findall(r"[a-z0-9_]+", str(source.get("tags") or "").lower()))),
            "capabilityGroups": system_capability_groups(source, spec, signals),
            "constraints": constraints,
            "chargeUp": charge_up,
            "active": active,
            "down": down,
            "cooldown": cooldown,
            "uptimeProxy": round(active / cycle, 4) if active > 0 and cycle > 0 else 0.0,
            "maxUses": number(source, "max uses"),
            "regen": number(source, "regen"),
            "fluxPerSecond": number(source, "flux/second"),
            "fluxPerUse": number(source, "flux/use"),
            "threatRange": number(ai_hints, "threatRange"),
            "threatDamage": number(ai_hints, "threatDamage"),
            "threatAmount": number(ai_hints, "threatAmount"),
            "activeSpeedIncrease": number(ai_hints, "activeSpeedIncrease"),
            "averageSpeedIncrease": number(ai_hints, "averageSpeedIncrease"),
            "averageSpeedMult": number(ai_hints, "averageSpeedMult"),
        }
        rows.append(row)
    return rows, {
        "scoredSystems": len(rows),
        "missingSystemSpecs": missing_specs,
        "scriptSourcesInspected": source_inspected,
    }


def percentile(values: list[float], value: float) -> float:
    if len(values) <= 1:
        return 0.5
    ordered = sorted(values)
    left = bisect.bisect_left(ordered, value)
    right = bisect.bisect_right(ordered, value)
    return ((left + right - 1) / 2) / (len(ordered) - 1)


def slot_features(spec: dict[str, Any]) -> tuple[float, int, dict[str, int]]:
    capacity = 0.0
    usable = 0
    counts: collections.Counter[str] = collections.Counter()
    for slot in spec.get("weaponSlots") or []:
        if not isinstance(slot, dict):
            continue
        slot_type = str(slot.get("type") or "UNKNOWN").upper()
        size = str(slot.get("size") or "UNKNOWN").upper()
        if slot_type in {"DECORATIVE", "SYSTEM"}:
            continue
        usable += 1
        capacity += SIZE_WEIGHT.get(size, 0.0)
        counts[f"{size}_{slot_type}"] += 1
    return capacity, usable, dict(sorted(counts.items()))


def mechanic_flags(system_id: str, built_in_mods: Iterable[str],
                   fitted_mods: Iterable[str] = ()) -> list[str]:
    flags: list[str] = []
    if system_id:
        flags.append(f"ship-system:{system_id}")
    built_ins = {str(value) for value in built_in_mods}
    fitted = {str(value) for value in fitted_mods}
    if "safetyoverrides" in built_ins:
        flags.append("built-in:safety-overrides")
    elif "safetyoverrides" in fitted:
        flags.append("fitted:safety-overrides")
    return flags


def hull_rows(hulls: dict[str, dict[str, Any]], specs: dict[str, dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    missing_specs: list[str] = []
    for hull_id, source in hulls.items():
        spec = specs.get(hull_id, {})
        if not spec:
            missing_specs.append(hull_id)
        dp = number(source, "supplies/rec")
        if dp <= 0:
            continue
        slots, slot_count, slot_counts = slot_features(spec)
        hp = number(source, "hitpoints")
        armor = number(source, "armor rating")
        max_flux = number(source, "max flux")
        dissipation = number(source, "flux dissipation")
        shield_efficiency = number(source, "shield efficiency")
        shield_type = str(source.get("shield type") or "NONE").upper()
        shield_flux_ehp = max_flux / shield_efficiency if shield_efficiency > 0 else 0.0
        bays = number(source, "fighter bays")
        hints = str(source.get("hints") or "").lower()
        hint_tokens = set(re.findall(r"[a-z0-9_]+", hints))
        tag_tokens = set(re.findall(r"[a-z0-9_]+", str(source.get("tags") or "").lower()))
        role = "phase" if shield_type == "PHASE" or "phase" in hints else (
            "carrier" if bays >= 2 else "combat")
        hull_size = str(spec.get("hullSize") or "UNKNOWN").upper()
        unavailable_reason = str(source.get("logistics n/a reason") or "").strip()
        if hull_size == "FIGHTER":
            availability = "fighter"
        elif hull_size not in {"FRIGATE", "DESTROYER", "CRUISER", "CAPITAL_SHIP"}:
            availability = "nonstandard-hull"
        elif unavailable_reason or "unboardable" in hint_tokens:
            availability = "unavailable"
        elif hint_tokens.intersection({"station", "module", "ship_with_modules", "hide_in_codex",
                                       "under_parent"}) or "station" in tag_tokens:
            availability = "station-or-module"
        else:
            availability = "player-ship"
        acquisition = "rare-or-limited" if tag_tokens.intersection({
            "rare_bp", "unique", "restricted", "no_bp", "boss", "limited_tooltip_if_locked",
            "codex_unlockable"}) else "ordinary-or-unknown"
        built_in_mods = [str(value) for value in (spec.get("builtInMods") or [])]
        system_id = str(source.get("system id") or spec.get("systemId") or "")
        row = {
            "id": hull_id,
            "name": source.get("name") or spec.get("hullName") or hull_id,
            "providerId": source.get("providerId"),
            "providerName": source.get("providerName"),
            "hullSize": hull_size,
            "role": role,
            "availabilityClass": availability,
            "acquisitionClass": acquisition,
            "shieldType": shield_type,
            "deploymentPoints": dp,
            "ordnancePoints": number(source, "ordnance points"),
            "hitpoints": hp,
            "armor": armor,
            "maxFlux": max_flux,
            "fluxDissipation": dissipation,
            "shieldEfficiency": shield_efficiency,
            "shieldFluxEhp": shield_flux_ehp,
            "speed": number(source, "max speed"),
            "acceleration": number(source, "acceleration"),
            "turnRate": number(source, "max turn rate"),
            "maxBurn": number(source, "max burn"),
            "fighterBays": bays,
            "slotCapacity": slots,
            "weaponSlotCount": slot_count,
            "slotCounts": slot_counts,
            "suppliesPerMonth": number(source, "supplies/mo"),
            "baseValue": number(source, "base value"),
            "systemId": system_id,
            "builtInMods": built_in_mods,
            "specialMechanics": mechanic_flags(system_id, built_in_mods),
            "skinBaseHullId": source.get("skinBaseHullId") or spec.get("skinBaseHullId") or "",
            "codexVariantId": source.get("codex variant id") or "",
        }
        row["effectiveDurability"] = hp + 2.0 * armor + 0.35 * shield_flux_ehp
        row["durabilityPerDp"] = row["effectiveDurability"] / dp
        row["fluxPerDp"] = (dissipation + max_flux / 20.0) / dp
        row["firepowerPerDp"] = (row["ordnancePoints"] + 5.0 * slots + 15.0 * bays) / dp
        row["logisticsEfficiency"] = dp / max(1.0, row["suppliesPerMonth"])
        candidates.append(row)

    rows = [row for row in candidates if row["availabilityClass"] == "player-ship"]

    components = {
        "mobility": lambda row: (row["speed"] + 0.20 * row["acceleration"]
                                  + 0.30 * row["turnRate"] + 5.0 * row["maxBurn"]),
        "durability": lambda row: row["durabilityPerDp"],
        "flux": lambda row: row["fluxPerDp"],
        "firepower": lambda row: row["firepowerPerDp"],
        "logistics": lambda row: row["logisticsEfficiency"],
    }
    absolute_survival = [row["effectiveDurability"] for row in rows]
    by_peer: dict[tuple[str, str], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        by_peer[(row["hullSize"], row["role"])].append(row)
    for peer_rows in by_peer.values():
        distributions = {name: [function(row) for row in peer_rows]
                         for name, function in components.items()}
        for row in peer_rows:
            scores = {name: percentile(distributions[name], function(row))
                      for name, function in components.items()}
            scores["survival"] = percentile(absolute_survival, row["effectiveDurability"])
            row["componentScores"] = {name: round(value * 100, 3)
                                      for name, value in scores.items()}
            for profile, weights in PROFILE_WEIGHTS.items():
                row[f"score_{profile}"] = round(
                    100 * sum(scores[name] * weight for name, weight in weights.items()), 3)

    for profile in PROFILE_WEIGHTS:
        ordered = sorted(rows, key=lambda row: (-row[f"score_{profile}"], row["deploymentPoints"], row["id"]))
        for rank, row in enumerate(ordered, 1):
            row[f"rank_{profile}"] = rank
    for row in rows:
        ranks = [row[f"rank_{profile}"] for profile in PROFILE_WEIGHTS]
        row["rankMean"] = round(statistics.fmean(ranks), 3)
        row["rankSpread"] = max(ranks) - min(ranks)

    apply_pareto(rows)
    availability_counts = collections.Counter(row["availabilityClass"] for row in candidates)
    return rows, {"missingHullSpecs": missing_specs, "candidateHullRows": len(candidates),
                  "availabilityCounts": dict(sorted(availability_counts.items())),
                  "scoredPlayerShips": len(rows)}


def comparable(left: dict[str, Any], right: dict[str, Any]) -> bool:
    return (left["hullSize"], left["role"], left["shieldType"]) == (
        right["hullSize"], right["role"], right["shieldType"])


def dominates(left: dict[str, Any], right: dict[str, Any]) -> bool:
    if not comparable(left, right) or left["id"] == right["id"]:
        return False
    favorable = (
        ("deploymentPoints", -1), ("ordnancePoints", 1), ("hitpoints", 1), ("armor", 1),
        ("maxFlux", 1), ("fluxDissipation", 1), ("speed", 1), ("acceleration", 1),
        ("turnRate", 1), ("fighterBays", 1), ("slotCapacity", 1), ("shieldFluxEhp", 1),
    )
    no_worse = all(left[key] * direction >= right[key] * direction for key, direction in favorable)
    better = any(left[key] * direction > right[key] * direction for key, direction in favorable)
    return no_worse and better


def apply_pareto(rows: list[dict[str, Any]]) -> None:
    for right in rows:
        dominators = [left["id"] for left in rows if dominates(left, right)]
        right["paretoDominated"] = bool(dominators)
        right["paretoDominators"] = dominators[:10]


def weapon_dps_proxies(source: dict[str, Any]) -> dict[str, Any]:
    damage_per_second = number(source, "damage/second")
    damage_per_shot = number(source, "damage/shot")
    chargedown = number(source, "chargedown")
    chargeup = number(source, "chargeup")
    burst_size = max(1.0, number(source, "burst size", 1.0))
    burst_delay = number(source, "burst delay")
    basis = "missing"
    burst_dps = 0.0
    if damage_per_second > 0:
        burst_dps = damage_per_second
        basis = "declared-dps"
    elif damage_per_shot > 0 and max(chargedown, chargeup, burst_delay) > 0:
        cycle = max(chargedown + chargeup, burst_delay * max(0.0, burst_size - 1.0), 0.05)
        burst_dps = damage_per_shot * burst_size / cycle
        basis = "cycle-proxy"

    ammo = number(source, "ammo")
    reload_per_second = number(source, "ammo/sec")
    sustained_dps = burst_dps
    if burst_dps > 0 and damage_per_shot > 0 and ammo > 0:
        available_shots = ammo + reload_per_second * SUSTAINED_DPS_WINDOW_SECONDS
        ammo_limited = damage_per_shot * available_shots / SUSTAINED_DPS_WINDOW_SECONDS
        sustained_dps = min(burst_dps, ammo_limited)
    hints = set(re.findall(r"[a-z0-9_]+", str(source.get("hints") or "").lower()))
    is_pd = "pd" in hints or "pd_only" in hints
    anti_ship_weight = PD_ANTI_SHIP_WEIGHT if is_pd else 1.0
    return {
        "burstDpsProxy": burst_dps,
        "sustainedDpsProxy": sustained_dps,
        "antiShipDpsProxy": sustained_dps * anti_ship_weight,
        "pdDpsProxy": sustained_dps if is_pd else 0.0,
        "dpsRoleWeight": anti_ship_weight,
        "dpsBasis": basis,
        "limitedAmmo": ammo > 0,
    }


def weapon_rows(weapons: dict[str, dict[str, Any]], specs: dict[str, dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    missing_specs: list[str] = []
    direct_dps = 0
    cycle_proxy = 0
    for weapon_id, source in weapons.items():
        spec = specs.get(weapon_id, {})
        if not spec:
            missing_specs.append(weapon_id)
        op = number(source, "ops")
        damage_per_shot = number(source, "damage/shot")
        energy_per_second = number(source, "energy/second")
        energy_per_shot = number(source, "energy/shot")
        dps = weapon_dps_proxies(source)
        if dps["dpsBasis"] == "declared-dps":
            direct_dps += 1
        elif dps["dpsBasis"] == "cycle-proxy":
            cycle_proxy += 1
        flux_proxy = energy_per_second or (
            energy_per_shot * dps["sustainedDpsProxy"] / damage_per_shot
            if damage_per_shot > 0 else 0.0)
        row = {
            "id": weapon_id,
            "name": source.get("name") or weapon_id,
            "providerId": source.get("providerId"),
            "providerName": source.get("providerName"),
            "size": str(spec.get("size") or "UNKNOWN").upper(),
            "mountType": str(spec.get("type") or "UNKNOWN").upper(),
            "damageType": str(source.get("type") or "UNKNOWN").upper(),
            "ordnancePoints": op,
            "range": number(source, "range"),
            "burstDpsProxy": round(dps["burstDpsProxy"], 4),
            "sustainedDpsProxy": round(dps["sustainedDpsProxy"], 4),
            "antiShipDpsProxy": round(dps["antiShipDpsProxy"], 4),
            "pdDpsProxy": round(dps["pdDpsProxy"], 4),
            "dpsRoleWeight": dps["dpsRoleWeight"],
            "dpsBasis": dps["dpsBasis"],
            "limitedAmmo": dps["limitedAmmo"],
            "fluxPerSecondProxy": round(flux_proxy, 4),
            "damagePerShot": damage_per_shot,
            "ammo": number(source, "ammo"),
            "reloadPerSecond": number(source, "ammo/sec"),
            "projectileSpeed": number(source, "proj speed"),
        }
        rows.append(row)
    peers: dict[tuple[str, str, str], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        peers[(row["size"], row["mountType"], row["damageType"])].append(row)
    for peer_rows in peers.values():
        dps_op = [row["antiShipDpsProxy"] / max(1.0, row["ordnancePoints"])
                  for row in peer_rows]
        ranges = [row["range"] for row in peer_rows]
        efficiency = [row["antiShipDpsProxy"] / max(1.0, row["fluxPerSecondProxy"])
                      for row in peer_rows]
        for row in peer_rows:
            row["paperScore"] = round(100 * (
                0.50 * percentile(
                    dps_op, row["antiShipDpsProxy"] / max(1.0, row["ordnancePoints"]))
                + 0.30 * percentile(ranges, row["range"])
                + 0.20 * percentile(efficiency,
                                    row["antiShipDpsProxy"]
                                    / max(1.0, row["fluxPerSecondProxy"]))), 3)
    return rows, {
        "missingWeaponSpecs": missing_specs,
        "scoredWeapons": len(rows),
        "declaredDpsRows": direct_dps,
        "cycleProxyRows": cycle_proxy,
        "missingDpsRows": len(rows) - direct_dps - cycle_proxy,
    }


def slot_accepts(slot: dict[str, Any], weapon: dict[str, Any]) -> bool:
    slot_size = str(slot.get("size") or "UNKNOWN").upper()
    weapon_size = str(weapon.get("size") or "UNKNOWN").upper()
    if SIZE_ORDER.get(weapon_size, 99) > SIZE_ORDER.get(slot_size, -1):
        return False
    slot_type = str(slot.get("type") or "UNKNOWN").upper()
    weapon_type = str(weapon.get("type") or "UNKNOWN").upper()
    if slot_type in {"BUILT_IN", "DECORATIVE", "SYSTEM"}:
        return True
    accepted = {
        "UNIVERSAL": {"BALLISTIC", "ENERGY", "MISSILE"},
        "HYBRID": {"BALLISTIC", "ENERGY"},
        "SYNERGY": {"ENERGY", "MISSILE"},
        "COMPOSITE": {"BALLISTIC", "MISSILE"},
    }.get(slot_type, {slot_type})
    return weapon_type in accepted


def hullmod_cost(row: dict[str, Any], hull_size: str) -> float:
    column = {"FRIGATE": "cost_frigate", "DESTROYER": "cost_dest",
              "CRUISER": "cost_cruiser", "CAPITAL_SHIP": "cost_capital"}.get(hull_size)
    return number(row, column) if column else 0.0


def variant_rows(variants: dict[str, dict[str, Any]], hull_rankings: list[dict[str, Any]],
                 hull_specs: dict[str, dict[str, Any]], weapon_rankings: list[dict[str, Any]],
                 weapon_specs: dict[str, dict[str, Any]], hullmods: dict[str, dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    hull_by_id = {row["id"]: row for row in hull_rankings}
    weapon_by_id = {row["id"]: row for row in weapon_rankings}
    rows: list[dict[str, Any]] = []
    missing_hulls: collections.Counter[str] = collections.Counter()
    missing_weapons: collections.Counter[str] = collections.Counter()
    missing_hullmods: collections.Counter[str] = collections.Counter()
    incompatible: list[dict[str, str]] = []
    over_budget: list[dict[str, Any]] = []
    for fallback_id, source in variants.items():
        variant_id = str(source.get("variantId") or fallback_id)
        hull_id = str(source.get("hullId") or "")
        hull = hull_by_id.get(hull_id)
        hull_spec = hull_specs.get(hull_id)
        if hull is None or hull_spec is None:
            missing_hulls[hull_id or "(blank)"] += 1
            continue
        slots = {str(slot.get("id")): slot for slot in (hull_spec.get("weaponSlots") or [])
                 if isinstance(slot, dict) and slot.get("id")}
        built_in_weapons = {str(slot_id): str(weapon_id) for slot_id, weapon_id in
                            (hull_spec.get("builtInWeapons") or {}).items()}
        built_in_slots = set(built_in_weapons)
        fitted_by_slot = dict(built_in_weapons)
        for group in source.get("weaponGroups") or []:
            if not isinstance(group, dict) or not isinstance(group.get("weapons"), dict):
                continue
            fitted_by_slot.update((str(slot_id), str(weapon_id))
                                  for slot_id, weapon_id in group["weapons"].items())
        fitted = list(fitted_by_slot.items())
        weapon_op = 0.0
        burst_dps = 0.0
        sustained_dps = 0.0
        anti_ship_dps = 0.0
        pd_dps = 0.0
        weapon_flux = 0.0
        ranges: list[float] = []
        paper_scores: list[float] = []
        compatibility_problems = 0
        for slot_id, weapon_id in fitted:
            weapon = weapon_by_id.get(weapon_id)
            weapon_spec = weapon_specs.get(weapon_id)
            if weapon is None or weapon_spec is None:
                missing_weapons[weapon_id] += 1
                continue
            slot = slots.get(slot_id)
            slot_type = str(slot.get("type") or "UNKNOWN").upper() if slot else "UNKNOWN"
            if (slot_id not in built_in_slots
                    and slot_type not in {"BUILT_IN", "DECORATIVE", "SYSTEM"}):
                weapon_op += weapon["ordnancePoints"]
            burst_dps += weapon["burstDpsProxy"]
            sustained_dps += weapon["sustainedDpsProxy"]
            anti_ship_dps += weapon["antiShipDpsProxy"]
            pd_dps += weapon["pdDpsProxy"]
            weapon_flux += weapon["fluxPerSecondProxy"]
            ranges.append(weapon["range"])
            paper_scores.append(weapon["paperScore"])
            if (slot_id not in built_in_slots
                    and (slot is None or not slot_accepts(slot, weapon_spec))):
                compatibility_problems += 1
                if len(incompatible) < 500:
                    incompatible.append({"variantId": variant_id, "slotId": slot_id,
                                         "weaponId": weapon_id})
        regular_mods = [str(value) for value in (source.get("hullMods") or [])]
        hullmod_op = 0.0
        for hullmod_id in regular_mods:
            hullmod = hullmods.get(hullmod_id)
            if hullmod is None:
                missing_hullmods[hullmod_id] += 1
            else:
                hullmod_op += hullmod_cost(hullmod, hull["hullSize"])
        vents = number(source, "fluxVents")
        capacitors = number(source, "fluxCapacitors")
        spent_op = weapon_op + hullmod_op + vents + capacitors
        remaining_op = hull["ordnancePoints"] - spent_op
        if remaining_op < -0.01 and len(over_budget) < 500:
            over_budget.append({"variantId": variant_id, "hullId": hull_id,
                                "remainingOp": round(remaining_op, 3)})
        rows.append({
            "id": variant_id,
            "displayName": source.get("displayName") or variant_id,
            "providerId": source.get("providerId"),
            "hullId": hull_id,
            "hullName": hull["name"],
            "hullSize": hull["hullSize"],
            "role": hull["role"],
            "deploymentPoints": hull["deploymentPoints"],
            "hullPaperScore": hull["score_balanced"],
            "goalVariant": bool(source.get("goalVariant")),
            "weaponCount": len(fitted),
            "weaponOp": round(weapon_op, 3),
            "hullmodOp": round(hullmod_op, 3),
            "vents": vents,
            "capacitors": capacitors,
            "remainingOp": round(remaining_op, 3),
            "burstDpsProxy": round(burst_dps, 3),
            "sustainedDpsProxy": round(sustained_dps, 3),
            "antiShipDpsProxy": round(anti_ship_dps, 3),
            "antiShipDpsPerDp": round(anti_ship_dps / hull["deploymentPoints"], 4),
            "pdDpsProxy": round(pd_dps, 3),
            "weaponFluxPerSecondProxy": round(weapon_flux, 3),
            "fluxHeadroomProxy": round(hull["fluxDissipation"] - weapon_flux, 3),
            "meanRange": round(statistics.fmean(ranges), 3) if ranges else 0.0,
            "meanWeaponPaperScore": round(statistics.fmean(paper_scores), 3) if paper_scores else 0.0,
            "builtInHullmods": hull["builtInMods"],
            "fittedHullmods": regular_mods,
            "specialMechanics": mechanic_flags(
                hull["systemId"], hull["builtInMods"], regular_mods),
            "compatibilityProblems": compatibility_problems,
            "overBudget": remaining_op < -0.01,
        })
    peers: dict[tuple[str, str], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        if not row["overBudget"] and row["compatibilityProblems"] == 0 and row["weaponCount"] > 0:
            peers[(row["hullSize"], row["role"])].append(row)
    ranked: list[dict[str, Any]] = []
    for peer_rows in peers.values():
        distributions = {
            "hull": [row["hullPaperScore"] for row in peer_rows],
            "dps": [row["antiShipDpsPerDp"] for row in peer_rows],
            "range": [row["meanRange"] for row in peer_rows],
            "headroom": [row["fluxHeadroomProxy"] for row in peer_rows],
            "weapon": [row["meanWeaponPaperScore"] for row in peer_rows],
        }
        for row in peer_rows:
            row["loadoutPaperScore"] = round(100 * (
                0.45 * percentile(distributions["hull"], row["hullPaperScore"])
                + 0.25 * percentile(distributions["dps"], row["antiShipDpsPerDp"])
                + 0.10 * percentile(distributions["range"], row["meanRange"])
                + 0.10 * percentile(distributions["headroom"], row["fluxHeadroomProxy"])
                + 0.10 * percentile(distributions["weapon"], row["meanWeaponPaperScore"])), 3)
            ranked.append(row)
    ranked.sort(key=lambda row: (-row["loadoutPaperScore"], row["deploymentPoints"], row["id"]))
    for rank, row in enumerate(ranked, 1):
        row["loadoutRank"] = rank
    quality = {
        "candidateVariants": len(variants),
        "resolvedPlayerShipVariants": len(rows),
        "rankedLoadouts": len(ranked),
        "missingHullReferences": dict(missing_hulls.most_common(100)),
        "missingWeaponReferences": dict(missing_weapons.most_common(100)),
        "missingHullmodReferences": dict(missing_hullmods.most_common(100)),
        "compatibilityProblemCount": sum(row["compatibilityProblems"] for row in rows),
        "compatibilityProblemExamples": incompatible[:100],
        "overBudgetCount": sum(row["overBudget"] for row in rows),
        "overBudgetExamples": over_budget[:100],
    }
    return ranked, quality


def robust_outliers(rows: list[dict[str, Any]], score_key: str, peer_keys: tuple[str, ...]) -> list[dict[str, Any]]:
    peers: dict[tuple[Any, ...], list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        peers[tuple(row.get(key) for key in peer_keys)].append(row)
    findings: list[dict[str, Any]] = []
    for peer, peer_rows in peers.items():
        if len(peer_rows) < 5:
            continue
        values = [float(row[score_key]) for row in peer_rows]
        median = statistics.median(values)
        mad = statistics.median(abs(value - median) for value in values)
        scale = max(1.0, 1.4826 * mad)
        for row in peer_rows:
            robust_z = (float(row[score_key]) - median) / scale
            if abs(robust_z) >= 2.5:
                findings.append({"id": row["id"], "name": row["name"],
                                 "providerId": row["providerId"],
                                 "peer": list(peer), "score": row[score_key],
                                 "robustZ": round(robust_z, 3)})
    return sorted(findings, key=lambda item: -abs(item["robustZ"]))


def write_csv(path: Path, rows: list[dict[str, Any]], fields: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            rendered = dict(row)
            for key, value in rendered.items():
                if isinstance(value, (dict, list)):
                    rendered[key] = json.dumps(value, ensure_ascii=False, sort_keys=True)
            writer.writerow(rendered)


def build(game: Path, output: Path) -> dict[str, Any]:
    providers, provider_diagnostics = resolve_providers(game)
    hulls, hull_csv = merged_csv(providers, "data/hulls/ship_data.csv", "id")
    weapons, weapon_csv = merged_csv(providers, "data/weapons/weapon_data.csv", "id")
    hullmods, hullmod_csv = merged_csv(providers, "data/hullmods/hull_mods.csv", "id")
    systems, system_csv = merged_csv(providers, "data/shipsystems/ship_systems.csv", "id")
    hull_specs, hull_spec_quality = merged_json_specs(providers, "data/hulls", ".ship", ("hullId",))
    skins, skin_quality = merged_json_specs(providers, "data/hulls/skins", ".skin",
                                            ("skinHullId",))
    hulls, hull_specs, skin_model_quality = apply_hull_skins(hulls, hull_specs, skins)
    weapon_specs, weapon_spec_quality = merged_json_specs(providers, "data/weapons", ".wpn", ("id",))
    system_specs, system_spec_quality = merged_json_specs(
        providers, "data/shipsystems", ".system", ("id",))
    variants, variant_quality = merged_json_specs(providers, "data/variants", ".variant",
                                                  ("variantId",))
    hull_rankings, hull_quality = hull_rows(hulls, hull_specs)
    weapon_rankings, weapon_quality = weapon_rows(weapons, weapon_specs)
    system_rankings, system_quality = system_rows(systems, system_specs, providers)
    loadout_rankings, loadout_quality = variant_rows(
        variants, hull_rankings, hull_specs, weapon_rankings, weapon_specs, hullmods)
    hull_outliers = robust_outliers(hull_rankings, "score_balanced", ("hullSize", "role"))
    weapon_outliers = robust_outliers(weapon_rankings, "paperScore",
                                      ("size", "mountType", "damageType"))
    stable_hulls = sorted(hull_rankings, key=lambda row: (row["rankMean"], row["rankSpread"], row["id"]))
    dominated = [row for row in hull_rankings if row["paretoDominated"]]
    summary = {
        "format": OUTPUT_FORMAT,
        "game": str(game),
        "model": {
            "weights": PROFILE_WEIGHTS,
            "hullPeerGroup": ["hullSize", "role"],
            "paretoPeerGroup": ["hullSize", "role", "shieldType"],
            "warning": "Paper scores are model-dependent hypotheses; simulator calibration is required.",
        },
        "scope": {
            "enabledMods": provider_diagnostics["enabledCount"],
            "resolvedMods": provider_diagnostics["resolvedCount"],
            "providers": len(providers),
            "rankedPlayerShips": len(hull_rankings),
            "candidateHullRows": hull_quality["candidateHullRows"],
            "materializedHullSkins": skin_model_quality["materialized"],
            "weapons": len(weapon_rankings),
            "shipSystems": len(system_rankings),
            "hullmods": len(hullmods),
            "variants": len(variants),
            "rankedLoadouts": len(loadout_rankings),
        },
        "dataQuality": {
            "providers": provider_diagnostics,
            "hullCsv": hull_csv,
            "weaponCsv": weapon_csv,
            "hullmodCsv": hullmod_csv,
            "systemCsv": system_csv,
            "hullSpecs": hull_spec_quality,
            "hullSkins": skin_quality,
            "hullSkinModel": skin_model_quality,
            "weaponSpecs": weapon_spec_quality,
            "systemSpecs": system_spec_quality,
            "variants": variant_quality,
            "hullModel": hull_quality,
            "weaponModel": weapon_quality,
            "systemModel": system_quality,
            "loadoutModel": loadout_quality,
        },
        "findings": {
            "paretoDominatedHulls": len(dominated),
            "hullOutliers": hull_outliers[:100],
            "weaponOutliers": weapon_outliers[:100],
            "stableTopHulls": [{key: row[key] for key in (
                "id", "name", "providerId", "hullSize", "role", "deploymentPoints",
                "score_balanced", "rankMean", "rankSpread", "paretoDominated",
                "systemId", "specialMechanics")}
                for row in stable_hulls[:100]],
            "topLoadouts": [{key: row[key] for key in (
                "id", "displayName", "providerId", "hullId", "hullName", "hullSize", "role",
                "deploymentPoints", "loadoutPaperScore", "loadoutRank", "remainingOp",
                "antiShipDpsProxy", "pdDpsProxy", "specialMechanics")}
                for row in loadout_rankings[:100]],
        },
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2,
                                                     sort_keys=True) + "\n", encoding="utf-8")
    hull_fields = ["id", "name", "providerId", "providerName", "hullSize", "role", "shieldType",
                   "availabilityClass", "acquisitionClass",
                   "deploymentPoints", "ordnancePoints", "hitpoints", "armor", "maxFlux",
                   "fluxDissipation", "shieldEfficiency", "speed", "acceleration", "turnRate",
                   "fighterBays", "slotCapacity", "weaponSlotCount", "systemId", "builtInMods",
                   "specialMechanics", "skinBaseHullId", "codexVariantId", "effectiveDurability",
                   "durabilityPerDp", "fluxPerDp", "firepowerPerDp", "logisticsEfficiency",
                   "componentScores", "score_balanced", "score_mobility", "score_durability",
                   "score_firepower", "rank_balanced", "rank_mobility", "rank_durability",
                   "rank_firepower", "rankMean", "rankSpread", "paretoDominated", "paretoDominators"]
    weapon_fields = ["id", "name", "providerId", "providerName", "size", "mountType", "damageType",
                     "ordnancePoints", "range", "burstDpsProxy", "sustainedDpsProxy",
                     "antiShipDpsProxy", "pdDpsProxy", "dpsRoleWeight", "dpsBasis", "limitedAmmo",
                     "fluxPerSecondProxy",
                     "damagePerShot", "ammo", "reloadPerSecond", "projectileSpeed", "paperScore"]
    loadout_fields = ["id", "displayName", "providerId", "hullId", "hullName", "hullSize", "role",
                      "deploymentPoints", "goalVariant", "weaponCount", "weaponOp", "hullmodOp",
                      "vents", "capacitors", "remainingOp", "burstDpsProxy", "sustainedDpsProxy",
                      "antiShipDpsProxy", "antiShipDpsPerDp", "pdDpsProxy",
                      "weaponFluxPerSecondProxy", "fluxHeadroomProxy", "meanRange",
                      "meanWeaponPaperScore", "builtInHullmods", "fittedHullmods",
                      "specialMechanics", "loadoutPaperScore", "loadoutRank"]
    system_fields = ["id", "name", "providerId", "providerName", "type", "aiType",
                     "statsScript", "scriptSource", "scriptSignals", "scriptConstants", "tags",
                     "capabilityGroups", "constraints", "chargeUp", "active", "down", "cooldown",
                     "uptimeProxy", "maxUses", "regen", "fluxPerSecond", "fluxPerUse",
                     "threatRange", "threatDamage", "threatAmount", "activeSpeedIncrease",
                     "averageSpeedIncrease", "averageSpeedMult"]
    write_csv(output / "hulls.csv", sorted(hull_rankings, key=lambda row: row["rank_balanced"]), hull_fields)
    write_csv(output / "weapons.csv", sorted(weapon_rankings, key=lambda row: -row["paperScore"]), weapon_fields)
    write_csv(output / "variants.csv", loadout_rankings, loadout_fields)
    write_csv(output / "systems.csv", sorted(system_rankings, key=lambda row: row["id"]), system_fields)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game", type=Path, default=Path("/Applications/Starsector.app"))
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    game = arguments.game.resolve()
    if not (game / ENABLED_RELATIVE).is_file():
        raise SystemExit(f"not a Starsector installation with enabled mods: {game}")
    summary = build(game, arguments.output.resolve())
    print(json.dumps({"format": summary["format"], "scope": summary["scope"],
                      "output": str(arguments.output.resolve()),
                      "warning": summary["model"]["warning"]}, indent=2))


if __name__ == "__main__":
    main()
