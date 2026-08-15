#!/usr/bin/env python3
"""Validate the machine-readable current-claim index against retained evidence and publications."""

from __future__ import annotations

import argparse
import json
import re
from decimal import Decimal, InvalidOperation
from pathlib import Path, PurePosixPath

FORMAT = "preflight-claims-v1"
STATUSES = {"accepted", "rejected", "superseded", "diagnostic", "exploratory"}
PUBLIC_ELIGIBILITY = {"development-context-only", "candidate", "not-public"}
QUANTITY_TYPES = {"seconds", "storage", "percent", "count"}
BOLD = re.compile(r"\*\*(.+?)\*\*", re.DOTALL)
SECONDS = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\s*(?:s\b|seconds?\b)", re.IGNORECASE)
STORAGE = re.compile(
    r"(?<![\w.])(\d+(?:\.\d+)?)\s*(GiB|GB|MiB|MB|KiB|KB|bytes?)\b",
    re.IGNORECASE,
)
PERCENT = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\s*(?:%|percent\b)", re.IGNORECASE)
COUNT = re.compile(
    r"(?<![\w.])(\d[\d,]*)\s*(?:-|\s+)(mods?|runs?|hits?|operations?|packages?|files?|artifacts?|classes?|launches?|replays?)\b",
    re.IGNORECASE,
)
COUNT_UNITS = {
    "mod": "mod",
    "mods": "mod",
    "run": "run",
    "runs": "run",
    "hit": "hit",
    "hits": "hit",
    "operation": "operation",
    "operations": "operation",
    "package": "package",
    "packages": "package",
    "file": "file",
    "files": "file",
    "artifact": "artifact",
    "artifacts": "artifact",
    "class": "class",
    "classes": "class",
    "launch": "launch",
    "launches": "launch",
    "replay": "replay",
    "replays": "replay",
}
STORAGE_UNITS = {
    "gib": "GiB",
    "gb": "GB",
    "mib": "MiB",
    "mb": "MB",
    "kib": "KiB",
    "kb": "KB",
    "byte": "B",
    "bytes": "B",
}

Quantity = tuple[str, str, str]


class ClaimError(ValueError):
    pass


def repository_path(root: Path, value: str, *, prefix: str | None = None) -> Path:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise ClaimError(f"unsafe repository path: {value!r}")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ClaimError(f"unsafe repository path: {value!r}")
    if prefix is not None and not value.startswith(prefix):
        raise ClaimError(f"path must stay under {prefix}: {value}")
    resolved = (root / path).resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as error:
        raise ClaimError(f"path escapes repository: {value}") from error
    if not resolved.is_file():
        raise ClaimError(f"referenced file does not exist: {value}")
    return resolved


def required_string(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ClaimError(f"{name} must be a non-empty string")
    return value


def canonical_number(value: object, name: str, *, integer: bool = False) -> str:
    if isinstance(value, bool):
        raise ClaimError(f"{name} must be numeric")
    text = str(value).replace(",", "").strip()
    try:
        number = Decimal(text)
    except InvalidOperation as error:
        raise ClaimError(f"{name} must be numeric: {value!r}") from error
    if not number.is_finite() or number < 0:
        raise ClaimError(f"{name} must be a non-negative finite number")
    if integer and number != number.to_integral_value():
        raise ClaimError(f"{name} must be an integer count")
    normalized = format(number.normalize(), "f")
    return "0" if normalized in {"-0", ""} else normalized


def canonical_unit(kind: str, unit: object, name: str) -> str:
    raw = required_string(unit, name).strip()
    lowered = raw.lower()
    if kind == "seconds":
        if lowered not in {"s", "second", "seconds"}:
            raise ClaimError(f"{name} must be seconds/s")
        return "s"
    if kind == "storage":
        if lowered not in STORAGE_UNITS:
            raise ClaimError(f"{name} is not a supported storage unit: {raw}")
        return STORAGE_UNITS[lowered]
    if kind == "percent":
        if lowered not in {"%", "percent"}:
            raise ClaimError(f"{name} must be percent/%")
        return "%"
    if kind == "count":
        if lowered not in COUNT_UNITS:
            raise ClaimError(f"{name} is not a supported count unit: {raw}")
        return COUNT_UNITS[lowered]
    raise ClaimError(f"unsupported quantity type: {kind}")


def quantity(kind: str, value: object, unit: object, name: str) -> Quantity:
    if kind not in QUANTITY_TYPES:
        raise ClaimError(f"{name} has unsupported quantity type: {kind}")
    return (
        kind,
        canonical_number(value, f"{name} value", integer=kind == "count"),
        canonical_unit(kind, unit, f"{name} unit"),
    )


def display_quantity(value: Quantity) -> str:
    kind, number, unit = value
    return f"{kind}:{number}{unit}"


def discover_quantities(text: str) -> set[Quantity]:
    """
    Find headline quantities that publication review promises to account for.

    The historical contract treated bold numeric prose as a public headline. This extends that
    same boundary to storage, percentages, and counts while preserving units in the identity. A
    `5.27 GB` review therefore cannot authorize `5.27 seconds` or `5.27%` by numeric coincidence.
    """
    discovered: set[Quantity] = set()
    for emphasis in BOLD.finditer(text):
        chunk = emphasis.group(1)
        for match in SECONDS.finditer(chunk):
            discovered.add(quantity("seconds", match.group(1), "s", "published seconds"))
        for match in STORAGE.finditer(chunk):
            discovered.add(quantity("storage", match.group(1), match.group(2), "published storage"))
        for match in PERCENT.finditer(chunk):
            discovered.add(quantity("percent", match.group(1), "%", "published percent"))
        for match in COUNT.finditer(chunk):
            discovered.add(quantity("count", match.group(1), match.group(2), "published count"))
    return discovered


def claim_quantities(raw: dict) -> set[Quantity]:
    values: set[Quantity] = set()
    result = raw["result"]
    profile = raw["profile"]
    values.add(quantity("seconds", result["seconds"], "s", "claim result"))
    values.add(quantity("count", profile["modCount"], "mod", "claim profile"))
    baseline = raw.get("baseline")
    if isinstance(baseline, dict):
        if isinstance(baseline.get("seconds"), (int, float)):
            values.add(quantity("seconds", baseline["seconds"], "s", "claim baseline"))
        if isinstance(baseline.get("deltaSeconds"), (int, float)):
            values.add(quantity("seconds", abs(baseline["deltaSeconds"]), "s", "claim delta"))
        if isinstance(baseline.get("improvementPercent"), (int, float)):
            values.add(quantity("percent", baseline["improvementPercent"], "%", "claim improvement"))
    return values


def reviewed_quantities(
    root: Path,
    payload: dict,
    publications: dict[str, Path],
    discovered: dict[str, set[Quantity]],
) -> dict[str, set[Quantity]]:
    if "publishedNumbers" in payload:
        raise ClaimError("publishedNumbers is obsolete; migrate entries to typed publishedQuantities")
    entries = payload.get("publishedQuantities", [])
    if not isinstance(entries, list):
        raise ClaimError("publishedQuantities must be a list")
    approved = {publication: set() for publication in publications}
    seen: set[tuple[str, Quantity]] = set()
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise ClaimError("each publishedQuantities entry must be an object")
        kind = required_string(entry.get("type"), f"publishedQuantities {index} type")
        value = quantity(
            kind,
            entry.get("value"),
            entry.get("unit"),
            f"publishedQuantities {index}",
        )
        evidence = required_string(entry.get("evidence"), f"publishedQuantities {index} evidence")
        repository_path(root, evidence, prefix="docs/evidence/")
        required_string(entry.get("derivation"), f"publishedQuantities {index} derivation")
        scopes = entry.get("publishedIn")
        if not isinstance(scopes, list) or not scopes:
            raise ClaimError(f"publishedQuantities {index} publishedIn must contain at least one publication")
        for raw_publication in scopes:
            publication = required_string(raw_publication, f"publishedQuantities {index} publication")
            if publication not in publications:
                raise ClaimError(
                    f"publishedQuantities {index} scopes {display_quantity(value)} to {publication}, "
                    "which is not listed by any claim"
                )
            identity = (publication, value)
            if identity in seen:
                raise ClaimError(
                    f"duplicate reviewed publication quantity: {publication} {display_quantity(value)}"
                )
            seen.add(identity)
            if value not in discovered[publication]:
                raise ClaimError(
                    f"reviewed quantity {display_quantity(value)} is no longer published as a headline in "
                    f"{publication}"
                )
            approved[publication].add(value)
    return approved


def verify_published_quantities(
    publications: dict[str, Path],
    claimed: dict[str, set[Quantity]],
    approved: dict[str, set[Quantity]],
    discovered: dict[str, set[Quantity]],
) -> int:
    checked = 0
    for publication in sorted(publications):
        unapproved = sorted(
            discovered[publication] - claimed.get(publication, set()) - approved.get(publication, set()),
            key=display_quantity,
        )
        if unapproved:
            formatted = [display_quantity(value) for value in unapproved]
            raise ClaimError(
                f"{publication} publishes headline quantities that no claim or reviewed typed entry "
                f"accounts for: {formatted}"
            )
        checked += len(discovered[publication])
    return checked


def validate_claims(root: Path, claims_file: Path | None = None) -> dict[str, int]:
    root = root.resolve()
    claims_file = claims_file or root / "docs/claims.json"
    try:
        payload = json.loads(claims_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ClaimError(f"could not read claim index: {error}") from error
    if not isinstance(payload, dict) or payload.get("format") != FORMAT:
        raise ClaimError(f"claim index must use format {FORMAT}")
    claims = payload.get("claims")
    if not isinstance(claims, list) or not claims:
        raise ClaimError("claim index must contain at least one claim")

    identifiers: set[str] = set()
    published_files: dict[str, Path] = {}
    claimed_by_publication: dict[str, set[Quantity]] = {}
    accepted = 0
    publications_checked = 0
    mentions_checked = 0
    assertions_checked = 0

    for index, raw in enumerate(claims):
        if not isinstance(raw, dict):
            raise ClaimError(f"claim {index} must be an object")
        claim_id = required_string(raw.get("id"), f"claim {index} id")
        if claim_id in identifiers:
            raise ClaimError(f"duplicate claim id: {claim_id}")
        identifiers.add(claim_id)

        status = required_string(raw.get("status"), f"{claim_id} status")
        if status not in STATUSES:
            raise ClaimError(f"unsupported status for {claim_id}: {status}")
        eligibility = required_string(
            raw.get("publicClaimEligibility"), f"{claim_id} publicClaimEligibility"
        )
        if eligibility not in PUBLIC_ELIGIBILITY:
            raise ClaimError(f"unsupported public eligibility for {claim_id}: {eligibility}")

        profile = raw.get("profile")
        if not isinstance(profile, dict) or not isinstance(profile.get("modCount"), int) or profile["modCount"] < 0:
            raise ClaimError(f"{claim_id} profile.modCount must be a non-negative integer")
        result = raw.get("result")
        if not isinstance(result, dict) or not isinstance(result.get("seconds"), (int, float)) or result["seconds"] <= 0:
            raise ClaimError(f"{claim_id} result.seconds must be positive")
        protocol = raw.get("protocol")
        if not isinstance(protocol, dict):
            raise ClaimError(f"{claim_id} protocol must be an object")
        repository_path(root, required_string(protocol.get("document"), f"{claim_id} protocol.document"))

        evidence = raw.get("evidence")
        if not isinstance(evidence, dict):
            raise ClaimError(f"{claim_id} evidence must be an object")
        evidence_path = repository_path(
            root,
            required_string(evidence.get("path"), f"{claim_id} evidence.path"),
            prefix="docs/evidence/",
        )
        evidence_text = evidence_path.read_text(encoding="utf-8")

        assertions = raw.get("evidenceAssertions")
        if not isinstance(assertions, list) or not assertions or any(
            not isinstance(value, str) or not value for value in assertions
        ):
            raise ClaimError(f"{claim_id} evidenceAssertions must contain non-empty strings")
        missing = [value for value in assertions if value not in evidence_text]
        if missing:
            raise ClaimError(f"{claim_id} evidence is missing assertion(s): {missing}")
        assertions_checked += len(assertions)

        publications = raw.get("publishedIn")
        if not isinstance(publications, list) or not publications:
            raise ClaimError(f"{claim_id} publishedIn must contain at least one path")
        mentions = raw.get("mentionedIn", [])
        if not isinstance(mentions, list):
            raise ClaimError(f"{claim_id} mentionedIn must be a list")
        seconds = format(float(result["seconds"]), "g")
        mod_count = profile["modCount"]
        mod_pattern = re.compile(rf"(?<!\d){mod_count}(?:-mod|\s+mods?)(?!\d)", re.IGNORECASE)
        typed_claim = claim_quantities(raw)
        for publication in publications:
            path = repository_path(root, required_string(publication, f"{claim_id} publication"))
            text = path.read_text(encoding="utf-8")
            if seconds not in text:
                raise ClaimError(f"{publication} no longer carries {claim_id} result {seconds}")
            if not mod_pattern.search(text):
                raise ClaimError(f"{publication} no longer carries {claim_id} {mod_count}-mod scope")
            published_files[publication] = path
            claimed_by_publication.setdefault(publication, set()).update(typed_claim)
            publications_checked += 1
        for mention in mentions:
            path = repository_path(root, required_string(mention, f"{claim_id} mention"))
            text = path.read_text(encoding="utf-8")
            if seconds not in text:
                raise ClaimError(f"{mention} no longer mentions {claim_id} result {seconds}")
            mentions_checked += 1

        if status == "accepted":
            accepted += 1
            required_string(evidence.get("runId"), f"{claim_id} evidence.runId")
            required_string(evidence.get("condition"), f"{claim_id} evidence.condition")

    discovered = {
        publication: discover_quantities(path.read_text(encoding="utf-8"))
        for publication, path in published_files.items()
    }
    approved = reviewed_quantities(root, payload, published_files, discovered)
    return {
        "claims": len(claims),
        "acceptedClaims": accepted,
        "evidenceAssertionsChecked": assertions_checked,
        "publicationsChecked": publications_checked,
        "mentionsChecked": mentions_checked,
        "publishedQuantitiesChecked": verify_published_quantities(
            published_files, claimed_by_publication, approved, discovered
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--claims", type=Path)
    args = parser.parse_args()
    claims = args.claims.resolve() if args.claims else None
    try:
        report = validate_claims(args.repository, claims)
    except ClaimError as error:
        parser.error(str(error))
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
