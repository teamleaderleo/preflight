#!/usr/bin/env python3
"""Snapshot and compare campaign saves around a human-operated gameplay pilot."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from datetime import datetime
from pathlib import Path


FORMAT = "preflight-campaign-save-state-v1"
ATTESTATION_FORMAT = "preflight-gameplay-pilot-operator-attestation-v3"
MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
MAX_ENGINE_BYTES = 256 * 1024 * 1024
MAX_RUN_REPORT_BYTES = 1024 * 1024
MAX_ADAPTER_REPORT_BYTES = 32 * 1024 * 1024
MAX_ADAPTER_HEALTH_BYTES = 1024 * 1024
MAX_DISABLED_PLANS_BYTES = 4 * 1024
SOURCE_REVISION_PATTERN = re.compile(r"[0-9a-f]{40,64}")
ADAPTER_HEALTH_FORMAT = "starsector-preflight-adapter-health-v1"
ADAPTER_HEALTH_STATUSES = {
    "ACTIVE", "PARTIAL", "SAFE_FALLBACK", "DISABLED", "PROBE_ONLY", "NO_TARGETS", "ERROR",
}


class GuardError(Exception):
    pass


def _selected_name(value: str) -> str:
    candidate = Path(value)
    if value in {"", ".", ".."} or candidate.name != value or len(candidate.parts) != 1:
        raise GuardError("selected save must be one directory name, not a path")
    if not value.startswith("save_"):
        raise GuardError("selected save must use Starsector's save_ directory prefix")
    if len(value.encode("utf-8")) > 255:
        raise GuardError("selected save directory name exceeds 255 bytes")
    return value


def _stable_file_digest(
        path: Path, *, maximum_bytes: int | None = None, label: str = "save entry"
) -> tuple[int, str]:
    before = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode):
        raise GuardError(f"{label} is not a regular file: {path}")
    if before.st_nlink != 1:
        raise GuardError(f"{label} is hard-linked and not independent: {path}")
    if maximum_bytes is not None and before.st_size > maximum_bytes:
        raise GuardError(f"{label} exceeds {maximum_bytes} bytes: {path}")
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        opened_before = os.fstat(stream.fileno())
        while chunk := stream.read(1024 * 1024):
            total += len(chunk)
            if maximum_bytes is not None and total > maximum_bytes:
                raise GuardError(f"{label} exceeds {maximum_bytes} bytes: {path}")
            digest.update(chunk)
        opened_after = os.fstat(stream.fileno())
    after = path.stat(follow_symlinks=False)
    identity_before = (
        before.st_dev, before.st_ino, before.st_nlink, before.st_size, before.st_mtime_ns
    )
    identity_opened_before = (
        opened_before.st_dev, opened_before.st_ino, opened_before.st_nlink,
        opened_before.st_size, opened_before.st_mtime_ns,
    )
    identity_opened_after = (
        opened_after.st_dev, opened_after.st_ino, opened_after.st_nlink,
        opened_after.st_size, opened_after.st_mtime_ns,
    )
    identity_after = (
        after.st_dev, after.st_ino, after.st_nlink, after.st_size, after.st_mtime_ns
    )
    if not (
            identity_before == identity_opened_before == identity_opened_after == identity_after
    ):
        raise GuardError(f"{label} changed while it was being hashed: {path}")
    return after.st_size, digest.hexdigest()


def _campaign_digest(directory: Path) -> dict[str, object]:
    canonical = hashlib.sha256()
    files = 0
    total_bytes = 0

    def walk_error(error: OSError) -> None:
        raise GuardError(f"save tree could not be read: {error}")

    for root_text, directories, names in os.walk(
            directory, followlinks=False, onerror=walk_error
    ):
        root = Path(root_text)
        directories.sort()
        names.sort()
        for name in list(directories):
            child = root / name
            if child.is_symlink():
                raise GuardError(f"save tree contains a symbolic-link directory: {child}")
            mode = child.stat(follow_symlinks=False).st_mode
            if not stat.S_ISDIR(mode):
                raise GuardError(f"save tree contains a non-directory entry: {child}")
            relative = child.relative_to(directory).as_posix()
            canonical.update(f"directory\0{relative}\n".encode())
        for name in names:
            child = root / name
            if child.is_symlink():
                raise GuardError(f"save tree contains a symbolic-link file: {child}")
            relative = child.relative_to(directory).as_posix()
            size, digest = _stable_file_digest(child)
            canonical.update(f"file\0{relative}\0{size}\0{digest}\n".encode())
            files += 1
            total_bytes += size
    return {"sha256": canonical.hexdigest(), "bytes": total_bytes, "files": files}


def snapshot(
        saves_directory: Path, selected_save: str, *, require_selected: bool = True
) -> dict[str, object]:
    selected_save = _selected_name(selected_save)
    if saves_directory.is_symlink():
        raise GuardError("saves directory must not be a symbolic link")
    try:
        resolved = saves_directory.resolve(strict=True)
    except FileNotFoundError as error:
        raise GuardError(f"saves directory is unavailable: {saves_directory}") from error
    if not resolved.is_dir():
        raise GuardError(f"saves path is not a directory: {resolved}")

    campaigns: dict[str, object] = {}
    for child in sorted(resolved.iterdir(), key=lambda path: path.name):
        if not child.name.startswith("save_"):
            continue
        if child.is_symlink():
            raise GuardError(f"campaign save must not be a symbolic link: {child}")
        if not child.is_dir():
            raise GuardError(f"campaign save is not a directory: {child}")
        campaigns[child.name] = _campaign_digest(child)
    if require_selected and selected_save not in campaigns:
        raise GuardError(f"selected disposable save is unavailable: {resolved / selected_save}")
    return {
        "format": FORMAT,
        "scope": "campaign save_* content; global saves/common state is excluded",
        "savesDirectory": str(resolved),
        "selectedSave": selected_save,
        "campaignSaves": campaigns,
    }


def _write_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_json_once(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def _stable_json_evidence(
        path: Path, label: str, maximum_bytes: int = MAX_SNAPSHOT_BYTES
) -> tuple[object, dict[str, object]]:
    before = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode):
        raise GuardError(f"{label} is not a regular file")
    if before.st_nlink != 1:
        raise GuardError(f"{label} is hard-linked and not independent evidence")
    if before.st_size > maximum_bytes:
        raise GuardError(f"{label} exceeds {maximum_bytes} bytes")
    with path.open("rb") as stream:
        opened = os.fstat(stream.fileno())
        data = stream.read(maximum_bytes + 1)
        after = os.fstat(stream.fileno())
    if len(data) > maximum_bytes:
        raise GuardError(f"{label} exceeds {maximum_bytes} bytes")
    identity_before = (
        before.st_dev, before.st_ino, before.st_nlink, before.st_size, before.st_mtime_ns
    )
    identity_opened = (
        opened.st_dev, opened.st_ino, opened.st_nlink, opened.st_size, opened.st_mtime_ns
    )
    identity_after = (
        after.st_dev, after.st_ino, after.st_nlink, after.st_size, after.st_mtime_ns
    )
    if identity_before != identity_opened or identity_opened != identity_after:
        raise GuardError(f"{label} changed while it was being read")
    return json.loads(data), {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def _load_snapshot_identity(path: Path) -> tuple[dict[str, object], dict[str, object]]:
    value, identity = _stable_json_evidence(path, "save-state snapshot")
    if not isinstance(value, dict) or value.get("format") != FORMAT:
        raise GuardError("save-state snapshot has an unsupported format")
    if not isinstance(value.get("savesDirectory"), str):
        raise GuardError("save-state snapshot lacks its saves directory")
    if not isinstance(value.get("selectedSave"), str):
        raise GuardError("save-state snapshot lacks its selected save")
    if not isinstance(value.get("campaignSaves"), dict):
        raise GuardError("save-state snapshot lacks campaign identities")
    return value, identity


def _load_snapshot(path: Path) -> dict[str, object]:
    value, _ = _load_snapshot_identity(path)
    return value


def compare(before_path: Path, saves_directory: Path) -> dict[str, object]:
    before = _load_snapshot(before_path)
    expected_directory = Path(str(before["savesDirectory"])).resolve(strict=True)
    actual_directory = saves_directory.resolve(strict=True)
    if actual_directory != expected_directory:
        raise GuardError("comparison saves directory differs from the snapshot")
    selected = str(before["selectedSave"])
    after = snapshot(actual_directory, selected, require_selected=False)
    before_campaigns = before["campaignSaves"]
    after_campaigns = after["campaignSaves"]
    assert isinstance(before_campaigns, dict)
    assert isinstance(after_campaigns, dict)

    changed = sorted(
        name for name in set(before_campaigns) | set(after_campaigns)
        if before_campaigns.get(name) != after_campaigns.get(name)
    )
    unexpected = [name for name in changed if name != selected]
    selected_present = selected in before_campaigns and selected in after_campaigns
    selected_changed = selected_present and selected in changed
    reasons: list[str] = []
    if not selected_present:
        reasons.append("the selected disposable save was removed")
    elif not selected_changed:
        reasons.append("the selected disposable save did not change; a save write was not observed")
    if unexpected:
        reasons.append("campaign saves other than the selected disposable copy changed")
    accepted = selected_present and selected_changed and not unexpected
    return {
        "format": FORMAT,
        "accepted": accepted,
        "scope": after["scope"],
        "savesDirectory": after["savesDirectory"],
        "selectedSave": selected,
        "selectedSavePresent": selected_present,
        "selectedSaveChanged": selected_changed,
        "otherCampaignSavesUnchanged": not unexpected,
        "changedCampaignSaves": changed,
        "unexpectedChangedCampaignSaves": unexpected,
        "reasons": reasons,
        "before": before_campaigns,
        "after": after_campaigns,
    }


def _evidence_identity(
        path: Path, *, maximum_bytes: int | None = None, label: str = "evidence file"
) -> dict[str, object]:
    size, digest = _stable_file_digest(
        path, maximum_bytes=maximum_bytes, label=label
    )
    return {"bytes": size, "sha256": digest}


def _load_save_boundary(path: Path) -> tuple[dict[str, object], dict[str, object]]:
    value, identity = _stable_json_evidence(path, "save-state comparison")
    if not isinstance(value, dict) or value.get("format") != FORMAT:
        raise GuardError("save-state comparison has an unsupported format")
    if not isinstance(value.get("selectedSave"), str):
        raise GuardError("save-state comparison lacks its selected save")
    if not isinstance(value.get("savesDirectory"), str):
        raise GuardError("save-state comparison lacks its saves directory")
    if not isinstance(value.get("before"), dict) or not isinstance(value.get("after"), dict):
        raise GuardError("save-state comparison lacks its campaign identities")
    if not isinstance(value.get("accepted"), bool):
        raise GuardError("save-state comparison lacks its acceptance result")
    return value, identity


def _json_object_evidence(
        path: Path, label: str, maximum_bytes: int
) -> tuple[dict[str, object], dict[str, object]]:
    value, identity = _stable_json_evidence(path, label, maximum_bytes)
    if not isinstance(value, dict):
        raise GuardError(f"{label} must be a JSON object")
    return value, identity


def _whole_number(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise GuardError(f"{label} must be a non-negative whole number")
    return value


def _frame_count(adapter: dict[str, object], section: str) -> int:
    frame_times = adapter.get("frameTimes")
    if not isinstance(frame_times, dict):
        raise GuardError("pilot adapter report lacks frame telemetry")
    distribution = frame_times.get(section)
    if not isinstance(distribution, dict):
        raise GuardError(f"pilot adapter report lacks {section} frame telemetry")
    return _whole_number(distribution.get("frames"), f"{section} frames")


def _run_evidence(
        path: Path,
        engine_identity: dict[str, object],
        process_exit_status: int,
        adapter_expected: bool,
        adapter_path: Path,
        adapter_health_path: Path,
) -> dict[str, object]:
    run, identity = _json_object_evidence(path, "pilot run report", MAX_RUN_REPORT_BYTES)
    if run.get("preflightJarSha256") != engine_identity["sha256"]:
        raise GuardError("pilot run report names a different engine JAR")
    if run.get("exitCode") != process_exit_status:
        raise GuardError("pilot run report exit status differs from the observed process")
    expected_mode = "ENABLED" if adapter_expected else "OFF"
    if run.get("adapterMode") != expected_mode:
        raise GuardError("pilot run report adapter mode differs from the requested configuration")
    if adapter_expected:
        expected_adapter = str(adapter_path.absolute())
        expected_health = str(adapter_health_path.absolute())
        if run.get("adapterReport") != expected_adapter:
            raise GuardError("pilot run report names a different adapter report")
        if run.get("adapterHealthReport") != expected_health:
            raise GuardError("pilot run report names a different adapter-health report")
    return {
        **identity,
        "outcome": run.get("outcome"),
        "adapterMode": run.get("adapterMode"),
    }


def _adapter_report_evidence(
        adapter_path: Path,
) -> tuple[dict[str, object], dict[str, object], dict[str, int]]:
    adapter, adapter_identity = _json_object_evidence(
        adapter_path, "pilot adapter report", MAX_ADAPTER_REPORT_BYTES
    )
    if adapter.get("mode") != "ENABLED":
        raise GuardError("pilot adapter report was not recorded in enabled mode")
    if not isinstance(adapter.get("transformerInstalled"), bool):
        raise GuardError("pilot adapter transformer-installed value must be boolean")
    if not isinstance(adapter.get("killSwitchActive"), bool):
        raise GuardError("pilot adapter kill-switch value must be boolean")
    applied = _whole_number(adapter.get("transformationsApplied"), "transformations applied")
    failures = _whole_number(adapter.get("containedFailures"), "contained failures")
    coverage = {
        "campaignFirst30SecondsFrames": _frame_count(
            adapter, "campaignFirst30SecondsActive"
        ),
        "campaignAfter30SecondsFrames": _frame_count(
            adapter, "campaignAfter30SecondsActive"
        ),
        "combatFrames": _frame_count(adapter, "combatActive"),
    }
    return adapter, {
        **adapter_identity,
        "mode": adapter["mode"],
        "transformerInstalled": adapter["transformerInstalled"],
        "killSwitchActive": adapter["killSwitchActive"],
        "transformationsApplied": applied,
        "containedFailures": failures,
    }, coverage


def _adapter_health_evidence(
        adapter_health_path: Path,
) -> tuple[dict[str, object], dict[str, object]]:
    health, identity = _json_object_evidence(
        adapter_health_path, "pilot adapter-health report", MAX_ADAPTER_HEALTH_BYTES
    )
    if health.get("format") != ADAPTER_HEALTH_FORMAT:
        raise GuardError("pilot adapter-health report has an unsupported format")
    if health.get("mode") != "ENABLED":
        raise GuardError("pilot adapter-health report was not recorded in enabled mode")
    if health.get("status") not in ADAPTER_HEALTH_STATUSES:
        raise GuardError("pilot adapter-health status is unsupported")
    return health, {
        **identity,
        "status": health["status"],
        "mode": health["mode"],
    }


def _require_matching_adapter_health(
        adapter: dict[str, object], health: dict[str, object]
) -> None:
    for field in (
            "transformerInstalled", "killSwitchActive", "transformationsApplied",
            "containedFailures",
    ):
        if adapter.get(field) != health.get(field):
            raise GuardError(f"pilot adapter and health reports disagree about {field}")


def pilot_attestation(
        *,
        before_path: Path,
        after_path: Path,
        engine_path: Path,
        run_path: Path,
        adapter_path: Path,
        adapter_health_path: Path,
        selected_save: str,
        source_revision: str,
        source_dirty: bool,
        process_exit_status: int,
        reload_attested: bool,
        recorded_at: str,
        configuration: dict[str, object],
) -> dict[str, object]:
    selected_save = _selected_name(selected_save)
    if SOURCE_REVISION_PATTERN.fullmatch(source_revision) is None:
        raise GuardError("pilot source revision must be a full hexadecimal Git object id")
    try:
        datetime.strptime(recorded_at, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise GuardError("pilot attestation time must be a UTC second timestamp") from error
    if not isinstance(source_dirty, bool) or not isinstance(reload_attested, bool):
        raise GuardError("pilot attestation flags must be booleans")
    if type(process_exit_status) is not int or not 0 <= process_exit_status <= 255:
        raise GuardError("pilot process exit status must be between 0 and 255")

    expected_configuration = {
        "startupCaches", "gameplayCaches", "saferJvm", "audioRepair", "profile", "adapter",
        "disabledPlans",
    }
    if set(configuration) != expected_configuration:
        raise GuardError("pilot configuration fields are incomplete or unsupported")
    for name in expected_configuration - {"disabledPlans"}:
        if not isinstance(configuration[name], bool):
            raise GuardError(f"pilot configuration {name} must be boolean")
    if not isinstance(configuration["disabledPlans"], str):
        raise GuardError("pilot disabled-plans value must be text")
    if len(configuration["disabledPlans"].encode("utf-8")) > MAX_DISABLED_PLANS_BYTES:
        raise GuardError("pilot disabled-plans value exceeds 4 KiB")

    before, before_identity = _load_snapshot_identity(before_path)
    if before["selectedSave"] != selected_save:
        raise GuardError("pilot selected save differs from its before snapshot")

    boundary = None
    boundary_identity = None
    if after_path.exists():
        boundary, boundary_identity = _load_save_boundary(after_path)
        if boundary["selectedSave"] != selected_save:
            raise GuardError("pilot selected save differs from its save-state comparison")
        if boundary["savesDirectory"] != before["savesDirectory"]:
            raise GuardError("save-state comparison directory differs from its before snapshot")
        if boundary["before"] != before["campaignSaves"]:
            raise GuardError("save-state comparison does not derive from its before snapshot")

    engine_identity = _evidence_identity(
        engine_path, maximum_bytes=MAX_ENGINE_BYTES, label="pilot engine JAR"
    )
    run_identity = None
    if run_path.exists():
        run_identity = _run_evidence(
            run_path,
            engine_identity,
            process_exit_status,
            bool(configuration["adapter"]),
            adapter_path,
            adapter_health_path,
        )

    adapter_identity = None
    adapter_health_identity = None
    route_coverage = None
    adapter = None
    health = None
    if configuration["adapter"] and adapter_path.exists():
        adapter, adapter_identity, route_coverage = _adapter_report_evidence(adapter_path)
    if configuration["adapter"] and adapter_health_path.exists():
        health, adapter_health_identity = _adapter_health_evidence(adapter_health_path)
    if adapter is not None and health is not None:
        _require_matching_adapter_health(adapter, health)

    reasons = []
    if process_exit_status != 0:
        reasons.append("the pilot process did not exit successfully")
    if run_identity is None:
        reasons.append("the pilot run report was not produced")
    if boundary is None:
        reasons.append("the save-state comparison was not produced")
    elif boundary["accepted"] is not True:
        reasons.append("the save-state comparison was not accepted")
    if configuration["adapter"]:
        if adapter_identity is None:
            reasons.append("the pilot adapter report was not produced")
        if adapter_health_identity is None:
            reasons.append("the pilot adapter-health report was not produced")
        if adapter_identity is not None and (
                not adapter_identity["transformerInstalled"]
                or adapter_identity["killSwitchActive"]
                or adapter_identity["transformationsApplied"] == 0
        ):
            reasons.append("the enabled adapter did not apply the reviewed runtime stack")
        if route_coverage is not None and not all(route_coverage.values()):
            reasons.append(
                "frame telemetry did not cover campaign warm-up, settled campaign, and combat"
            )
    if not reload_attested:
        reasons.append("the operator did not attest reload, resumed play, and normal exit")

    complete = not reasons
    reload_prerequisites_met = (
        process_exit_status == 0 and boundary is not None and boundary["accepted"] is True
    )
    if reload_attested and not reload_prerequisites_met:
        raise GuardError("reload cannot be attested without a successful process and accepted save boundary")

    return {
        "format": ATTESTATION_FORMAT,
        "complete": complete,
        "attested": reload_attested,
        "statement": (
            "The named disposable save returned to the title screen, reloaded, resumed play, "
            "and exited normally."
        ),
        "selectedSave": selected_save,
        "recordedAt": recorded_at,
        "source": {"revision": source_revision, "dirty": source_dirty},
        "engineJar": engine_identity,
        "process": {"exitStatus": process_exit_status},
        "configuration": configuration,
        "evidence": {
            "saveStateBefore": before_identity,
            "saveStateAfter": boundary_identity,
            "saveBoundaryAccepted": boundary["accepted"] if boundary is not None else None,
            "run": run_identity,
            "adapter": adapter_identity,
            "adapterHealth": adapter_health_identity,
            "routeCoverage": route_coverage,
        },
        "reasons": reasons,
    }


def _bool_argument(value: str) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    snapshot_parser = subparsers.add_parser("snapshot")
    snapshot_parser.add_argument("--saves-dir", type=Path, required=True)
    snapshot_parser.add_argument("--selected", required=True)
    snapshot_parser.add_argument("--output", type=Path, required=True)
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--before", type=Path, required=True)
    compare_parser.add_argument("--saves-dir", type=Path, required=True)
    compare_parser.add_argument("--output", type=Path, required=True)
    attest_parser = subparsers.add_parser("attest")
    attest_parser.add_argument("--before", type=Path, required=True)
    attest_parser.add_argument("--after", type=Path, required=True)
    attest_parser.add_argument("--engine", type=Path, required=True)
    attest_parser.add_argument("--run", type=Path, required=True)
    attest_parser.add_argument("--adapter-report", type=Path, required=True)
    attest_parser.add_argument("--adapter-health", type=Path, required=True)
    attest_parser.add_argument("--selected", required=True)
    attest_parser.add_argument("--source-revision", required=True)
    attest_parser.add_argument("--source-dirty", type=_bool_argument, required=True)
    attest_parser.add_argument("--process-exit-status", type=int, required=True)
    attest_parser.add_argument("--reload-attested", type=_bool_argument, required=True)
    attest_parser.add_argument("--recorded-at", required=True)
    attest_parser.add_argument("--startup-caches", type=_bool_argument, required=True)
    attest_parser.add_argument("--gameplay-caches", type=_bool_argument, required=True)
    attest_parser.add_argument("--safer-jvm", type=_bool_argument, required=True)
    attest_parser.add_argument("--audio-repair", type=_bool_argument, required=True)
    attest_parser.add_argument("--profile", type=_bool_argument, required=True)
    attest_parser.add_argument("--adapter", type=_bool_argument, required=True)
    attest_parser.add_argument("--disabled-plans", default="")
    attest_parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.command == "snapshot":
            _write_json(args.output, snapshot(args.saves_dir, args.selected))
            return 0
        if args.command == "compare":
            result = compare(args.before, args.saves_dir)
            _write_json(args.output, result)
            return 0 if result["accepted"] else 1
        result = pilot_attestation(
            before_path=args.before,
            after_path=args.after,
            engine_path=args.engine,
            run_path=args.run,
            adapter_path=args.adapter_report,
            adapter_health_path=args.adapter_health,
            selected_save=args.selected,
            source_revision=args.source_revision,
            source_dirty=args.source_dirty,
            process_exit_status=args.process_exit_status,
            reload_attested=args.reload_attested,
            recorded_at=args.recorded_at,
            configuration={
                "startupCaches": args.startup_caches,
                "gameplayCaches": args.gameplay_caches,
                "saferJvm": args.safer_jvm,
                "audioRepair": args.audio_repair,
                "profile": args.profile,
                "adapter": args.adapter,
                "disabledPlans": args.disabled_plans,
            },
        )
        _write_json_once(args.output, result)
        return 0 if result["complete"] else 1
    except (GuardError, FileNotFoundError, json.JSONDecodeError, OSError) as error:
        print(f"Save-state guard: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
