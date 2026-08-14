#!/usr/bin/env python3
"""Validate the machine-readable current-claim index against retained evidence and publications."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path, PurePosixPath

FORMAT = "preflight-claims-v1"
STATUSES = {"accepted", "rejected", "superseded", "diagnostic", "exploratory"}
PUBLIC_ELIGIBILITY = {"development-context-only", "candidate", "not-public"}
BOLD = re.compile(r"\*\*(.+?)\*\*", re.DOTALL)
SECONDS = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\s*(?:s\b|seconds?\b)", re.IGNORECASE)


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


def approved_numbers(root: Path, payload: dict) -> set[str]:
    """
    The numbers a maintainer has looked at and accepted, each naming where it came from.

    A published number is usually not a copy of a measured one. The README's `~4.8s net
    combined` is the sum of three scorecard rows, `1.613s -> 0.452s` is a before/after pair
    whose difference is a fourth, and `7.44s` is the top of a range. No rule derives those,
    so this does not try: a person decides, and the entry records the decision and its
    source so a reader can go and check it.
    """
    entries = payload.get("publishedNumbers", [])
    if not isinstance(entries, list):
        raise ClaimError("publishedNumbers must be a list")
    approved: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ClaimError("each publishedNumbers entry must be an object")
        value = required_string(entry.get("value"), "publishedNumbers value")
        if not SECONDS.fullmatch(f"{value}s"):
            raise ClaimError(f"publishedNumbers value is not a second value: {value}")
        repository_path(
            root,
            required_string(entry.get("evidence"), f"publishedNumbers {value} evidence"),
            prefix="docs/evidence/",
        )
        required_string(entry.get("note"), f"publishedNumbers {value} note")
        approved.add(value)
    return approved


def verify_published_seconds(
    publications: dict[str, Path], approved: set[str], claimed_results: set[str]
) -> int:
    """
    The index checks that each claim's number is still published. This checks the other
    direction, which is what lets an unbacked number in: a bold number in a publication is a
    headline claim, so it has to be one somebody accepted.
    """
    checked = 0
    for publication, path in sorted(publications.items()):
        published = {
            number.group(1)
            for emphasis in BOLD.finditer(path.read_text(encoding="utf-8"))
            for number in SECONDS.finditer(emphasis.group(1))
        }
        unapproved = sorted(published - approved - claimed_results, key=float)
        if unapproved:
            raise ClaimError(
                f"{publication} publishes second values that no claim or reviewed entry "
                f"accounts for: {unapproved}"
            )
        checked += len(published)
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
    claimed_results: set[str] = set()
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
        claimed_results.add(seconds)
        mod_count = profile["modCount"]
        mod_pattern = re.compile(rf"(?<!\d){mod_count}(?:-mod|\s+mods?)(?!\d)", re.IGNORECASE)
        for publication in publications:
            path = repository_path(root, required_string(publication, f"{claim_id} publication"))
            text = path.read_text(encoding="utf-8")
            if seconds not in text:
                raise ClaimError(f"{publication} no longer carries {claim_id} result {seconds}")
            if not mod_pattern.search(text):
                raise ClaimError(f"{publication} no longer carries {claim_id} {mod_count}-mod scope")
            published_files[publication] = path
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

    return {
        "claims": len(claims),
        "acceptedClaims": accepted,
        "evidenceAssertionsChecked": assertions_checked,
        "publicationsChecked": publications_checked,
        "mentionsChecked": mentions_checked,
        "publishedSecondsChecked": verify_published_seconds(
            published_files, approved_numbers(root, payload), claimed_results
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
