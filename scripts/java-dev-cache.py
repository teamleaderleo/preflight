#!/usr/bin/env python3
"""Inspect Preflight's opt-in Java result cache without mutating it."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import stat
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Sequence


REUSE_FORMAT = "preflight-java-dev-reuse-v1"
APACHE_EXTENSION_VERSION = "1.3.0"
APACHE_IMPLEMENTATION_VERSION = "v1.2"
INVENTORY_SCHEMA = "preflight-java-dev-cache-inventory-v1"
IDENTITY = re.compile(r"[0-9a-f]{64}")
LOCK_FILE = re.compile(r"(cache|worktree)-([0-9a-f]{64})\.lock")
MAX_ENTRIES = 100_000
MAX_DEPTH = 32
MAX_ANOMALY_DETAILS = 200
DIRECTORY_FLAGS = os.O_RDONLY | os.O_CLOEXEC | os.O_DIRECTORY | os.O_NOFOLLOW
LOCK_FLAGS = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Inspect Java exact-result cache structure; never deletes or repairs data."
    )
    result.add_argument(
        "--root",
        type=Path,
        help="cache root to inspect; defaults to PREFLIGHT_JAVA_DEV_CACHE or the platform cache",
    )
    result.add_argument("action", choices=("inspect",))
    return result


def cache_root(environment: dict[str, str] | os._Environ[str] = os.environ) -> Path:
    override = environment.get("PREFLIGHT_JAVA_DEV_CACHE")
    if override:
        root = Path(override)
    elif sys.platform == "darwin":
        root = Path.home() / "Library" / "Caches" / "dev.starsector.preflight" / "java-dev"
    elif environment.get("XDG_CACHE_HOME"):
        root = Path(environment["XDG_CACHE_HOME"]) / "preflight" / "java-dev"
    else:
        root = Path.home() / ".cache" / "preflight" / "java-dev"
    if not root.is_absolute():
        raise ValueError("the Java reuse cache root must be absolute")
    return root


def normalized_absolute(path: Path) -> Path:
    if not path.is_absolute():
        raise ValueError("the Java reuse cache root must be absolute")
    return Path(os.path.normpath(os.fspath(path)))


def iso_time(nanoseconds: int | None) -> str | None:
    if nanoseconds is None:
        return None
    try:
        return datetime.fromtimestamp(nanoseconds / 1_000_000_000, timezone.utc).isoformat()
    except (OSError, OverflowError, ValueError):
        return None


@dataclass
class Totals:
    directories: int = 0
    regular_files: int = 0
    symlinks: int = 0
    other_entries: int = 0
    logical_file_bytes: int = 0
    allocated_bytes: int = 0
    hardlink_paths: int = 0
    oldest_mtime_ns: int | None = None
    newest_mtime_ns: int | None = None
    _allocated_inodes: set[tuple[int, int]] = field(default_factory=set, repr=False)

    def observe(self, attributes: os.stat_result) -> None:
        mode = attributes.st_mode
        if stat.S_ISLNK(mode):
            self.symlinks += 1
            return
        if stat.S_ISREG(mode):
            self.regular_files += 1
            self.logical_file_bytes += attributes.st_size
        elif stat.S_ISDIR(mode):
            self.directories += 1
        else:
            self.other_entries += 1
            return
        modified = attributes.st_mtime_ns
        self.oldest_mtime_ns = (
            modified if self.oldest_mtime_ns is None else min(self.oldest_mtime_ns, modified)
        )
        self.newest_mtime_ns = (
            modified if self.newest_mtime_ns is None else max(self.newest_mtime_ns, modified)
        )
        inode = (attributes.st_dev, attributes.st_ino)
        if inode in self._allocated_inodes:
            self.hardlink_paths += 1
            return
        self._allocated_inodes.add(inode)
        self.allocated_bytes += getattr(attributes, "st_blocks", 0) * 512

    def payload(self) -> dict[str, object]:
        return {
            "allocatedBytes": self.allocated_bytes,
            "directories": self.directories,
            "hardlinkPathsNotDoubleCountedForAllocation": self.hardlink_paths,
            "logicalFileBytes": self.logical_file_bytes,
            "newestMtime": iso_time(self.newest_mtime_ns),
            "newestMtimeUnixNs": self.newest_mtime_ns,
            "oldestMtime": iso_time(self.oldest_mtime_ns),
            "oldestMtimeUnixNs": self.oldest_mtime_ns,
            "otherEntries": self.other_entries,
            "regularFiles": self.regular_files,
            "symlinks": self.symlinks,
        }


@dataclass
class InventoryState:
    root: Path
    totals: Totals = field(default_factory=Totals)
    anomaly_count: int = 0
    anomalies: list[dict[str, str]] = field(default_factory=list)
    scanned_entries: int = 0
    complete: bool = True
    limit_exceeded: bool = False

    def anomaly(self, code: str, relative: str, detail: str = "") -> None:
        self.anomaly_count += 1
        self.complete = False
        if len(self.anomalies) < MAX_ANOMALY_DETAILS:
            item = {"code": code, "path": relative}
            if detail:
                item["detail"] = detail
            self.anomalies.append(item)

    def observe(self, relative: str, attributes: os.stat_result) -> bool:
        self.scanned_entries += 1
        if self.scanned_entries > MAX_ENTRIES:
            self.anomaly("entryLimitExceeded", relative, str(MAX_ENTRIES))
            return False
        self.totals.observe(attributes)
        if stat.S_ISLNK(attributes.st_mode):
            self.anomaly("symlinkRefused", relative)
            return False
        if not (stat.S_ISREG(attributes.st_mode) or stat.S_ISDIR(attributes.st_mode)):
            self.anomaly("nonRegularEntryRefused", relative)
            return False
        return True


@dataclass
class ProjectSummary:
    group_id: str
    artifact_id: str
    generations: set[str] = field(default_factory=set)
    build_info_generations: set[str] = field(default_factory=set)

    def payload(self) -> dict[str, object]:
        return {
            "artifactId": self.artifact_id,
            "buildInfoFiles": len(self.build_info_generations),
            "generationCount": len(self.generations),
            "generationsWithoutBuildInfo": len(self.generations - self.build_info_generations),
            "groupId": self.group_id,
        }


@dataclass
class Namespace:
    identity: str
    lock_state: str
    totals: Totals = field(default_factory=Totals)
    apache_versions: set[str] = field(default_factory=set)
    projects: dict[tuple[str, str], ProjectSummary] = field(default_factory=dict)

    def project(self, group_id: str, artifact_id: str) -> ProjectSummary:
        key = (group_id, artifact_id)
        return self.projects.setdefault(key, ProjectSummary(group_id, artifact_id))

    def payload(self) -> dict[str, object]:
        projects = [project.payload() for _, project in sorted(self.projects.items())]
        return {
            "apacheImplementationVersions": sorted(self.apache_versions),
            "buildInfoFiles": sum(
                len(project.build_info_generations) for project in self.projects.values()
            ),
            "generationCount": sum(len(project.generations) for project in self.projects.values()),
            "identity": self.identity,
            "lockState": self.lock_state,
            "projectCount": len(projects),
            "projects": projects,
            "totals": self.totals.payload(),
        }


def stat_identity(attributes: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        attributes.st_dev,
        attributes.st_ino,
        attributes.st_mode,
        attributes.st_mtime_ns,
        attributes.st_ctime_ns,
    )


def validate_path_chain(root: Path, state: InventoryState) -> os.stat_result | None:
    current = Path(root.anchor)
    parts = root.parts[1:] if root.anchor else root.parts
    for index, part in enumerate(parts):
        current /= part
        try:
            attributes = os.lstat(current)
        except FileNotFoundError:
            return None
        except OSError as error:
            state.anomaly(
                "rootComponentUnreadable",
                f"ancestor:{current}",
                error.__class__.__name__,
            )
            return None
        relative = "." if current == root else f"ancestor:{current}"
        if stat.S_ISLNK(attributes.st_mode):
            state.anomaly("symlinkedRootComponentRefused", relative)
            return attributes if current == root else None
        if current != root and not stat.S_ISDIR(attributes.st_mode):
            state.anomaly("nonDirectoryRootAncestor", relative)
            return None
        if current == root:
            return attributes
    return os.lstat(root)


def directory_entries(
    path: Path, state: InventoryState, relative: str
) -> list[tuple[str, os.stat_result]]:
    if state.limit_exceeded:
        return []
    try:
        descriptor = os.open(path, DIRECTORY_FLAGS)
    except OSError as error:
        state.anomaly("directoryOpenRefused", relative, error.__class__.__name__)
        return []
    try:
        with os.scandir(descriptor) as entries:
            observed = []
            for entry in entries:
                if len(observed) >= MAX_ENTRIES - state.scanned_entries:
                    state.limit_exceeded = True
                    state.anomaly("entryLimitExceeded", relative, str(MAX_ENTRIES))
                    break
                display = f"{relative}/{entry.name}" if relative != "." else entry.name
                try:
                    attributes = entry.stat(follow_symlinks=False)
                except OSError as error:
                    state.anomaly("entryChangedDuringScan", display, error.__class__.__name__)
                    continue
                observed.append((entry.name, attributes))
            return sorted(observed, key=lambda item: item[0])
    except OSError as error:
        state.anomaly("directoryScanFailed", relative, error.__class__.__name__)
        return []
    finally:
        os.close(descriptor)


def acquire_shared_lock(path: Path) -> tuple[str, int | None]:
    try:
        descriptor = os.open(path, LOCK_FLAGS)
    except OSError as error:
        return f"unreadable:{error.__class__.__name__}", None
    try:
        fcntl.flock(descriptor, fcntl.LOCK_SH | fcntl.LOCK_NB)
        return "sharedLockAcquired", descriptor
    except BlockingIOError:
        os.close(descriptor)
        return "exclusiveWriterObserved", None
    except OSError as error:
        os.close(descriptor)
        return f"lockProbeFailed:{error.__class__.__name__}", None


def scan_tree(
    path: Path,
    relative: tuple[str, ...],
    state: InventoryState,
    namespace: Namespace,
    validator: Callable[[tuple[str, ...], os.stat_result, Namespace, InventoryState], None],
    depth: int = 0,
) -> None:
    if depth > MAX_DEPTH:
        state.anomaly("depthLimitExceeded", "/".join(relative), str(MAX_DEPTH))
        return
    before = None
    try:
        before = stat_identity(os.lstat(path))
    except OSError as error:
        state.anomaly("directoryChangedDuringScan", "/".join(relative), error.__class__.__name__)
        return
    for name, attributes in directory_entries(path, state, "/".join(relative)):
        parts = (*relative, name)
        display = "/".join(parts)
        accepted = state.observe(display, attributes)
        namespace.totals.observe(attributes)
        validator(parts, attributes, namespace, state)
        if accepted and stat.S_ISDIR(attributes.st_mode):
            scan_tree(path / name, parts, state, namespace, validator, depth + 1)
    try:
        after = stat_identity(os.lstat(path))
    except OSError as error:
        state.anomaly("directoryChangedDuringScan", "/".join(relative), error.__class__.__name__)
        return
    if before != after:
        state.anomaly("directoryChangedDuringScan", "/".join(relative))


def validate_namespace_entry(
    parts: tuple[str, ...],
    attributes: os.stat_result,
    namespace: Namespace,
    state: InventoryState,
) -> None:
    local = parts[3:]
    display = "/".join(parts)
    depth = len(local)
    is_directory = stat.S_ISDIR(attributes.st_mode)
    is_file = stat.S_ISREG(attributes.st_mode)
    if depth == 1:
        namespace.apache_versions.add(local[0])
        if local[0] != APACHE_IMPLEMENTATION_VERSION or not is_directory:
            state.anomaly("unknownApacheImplementation", display)
    elif depth in (2, 3):
        if not is_directory:
            state.anomaly("malformedApacheProjectPath", display)
        if depth == 3 and is_directory:
            namespace.project(local[1], local[2])
    elif depth == 4:
        if not is_directory or IDENTITY.fullmatch(local[3]) is None:
            state.anomaly("malformedApacheGeneration", display)
        else:
            namespace.project(local[1], local[2]).generations.add(local[3])
    elif depth == 5:
        if local[4] != "local" or not is_directory:
            state.anomaly("unknownApacheCacheSource", display)
    elif depth >= 6:
        if depth == 6 and local[5] == "buildinfo.xml" and is_file:
            namespace.project(local[1], local[2]).build_info_generations.add(local[3])
        elif local[-1] == "buildinfo.xml":
            state.anomaly("misplacedBuildInfo", display)
    else:
        state.anomaly("malformedApachePath", display)


def base_report(root: Path) -> dict[str, object]:
    return {
        "allocatedBytesSemantics": (
            "unique (device,inode) st_blocks*512 for observed regular files/directories; "
            "shared reflink extents are not deducted"
        ),
        "apacheExtensionVersion": APACHE_EXTENSION_VERSION,
        "apacheImplementationVersion": APACHE_IMPLEMENTATION_VERSION,
        "cacheRoot": str(root),
        "deletionCandidates": [],
        "filesystemReadCaveat": (
            "the inspector writes no cache bytes, but directory reads may update atime according "
            "to mount policy and shared lock probes briefly change kernel lock state"
        ),
        "format": REUSE_FORMAT,
        "inventorySchema": INVENTORY_SCHEMA,
        "retentionAuthority": False,
        "selectorNamesAvailable": False,
        "timestampSemantics": (
            "filesystem mtime observation only; pinned Apache 1.3.0 does not refresh checksum "
            "directory mtime on a cache hit, so this is not last access or LRU"
        ),
    }


def empty_lock_report() -> dict[str, dict[str, int]]:
    return {
        "cache": {
            "exclusiveWriterObserved": 0,
            "missingForNamespace": 0,
            "probeFailed": 0,
            "sharedLockAcquired": 0,
            "withoutNamespace": 0,
        },
        "worktree": {
            "exclusiveWriterObserved": 0,
            "probeFailed": 0,
            "sharedLockAcquired": 0,
        },
    }


def inspect_cache(
    requested_root: Path,
    lock_probe: Callable[[Path], tuple[str, int | None]] = acquire_shared_lock,
) -> tuple[dict[str, object], int]:
    root = normalized_absolute(requested_root)
    state = InventoryState(root)
    report = base_report(root)
    attributes = validate_path_chain(root, state)
    if attributes is None and state.anomaly_count == 0:
        report.update(
            {
                "anomalies": [],
                "anomalyCount": 0,
                "anomalyDetailsTruncated": False,
                "inventoryComplete": True,
                "currentFormatPresent": False,
                "locks": empty_lock_report(),
                "namespaces": [],
                "scannedEntries": 0,
                "status": "absent",
                "totals": state.totals.payload(),
            }
        )
        return report, 0
    if attributes is None or not stat.S_ISDIR(attributes.st_mode):
        if attributes is not None and not stat.S_ISDIR(attributes.st_mode):
            state.anomaly("cacheRootIsNotDirectory", ".")
        report.update(
            {
                "anomalies": state.anomalies,
                "anomalyCount": state.anomaly_count,
                "anomalyDetailsTruncated": state.anomaly_count > len(state.anomalies),
                "inventoryComplete": False,
                "currentFormatPresent": False,
                "locks": empty_lock_report(),
                "namespaces": [],
                "scannedEntries": state.scanned_entries,
                "status": "refused",
                "totals": state.totals.payload(),
            }
        )
        return report, 2

    root_before = stat_identity(attributes)
    format_path = root / REUSE_FORMAT
    format_attributes: os.stat_result | None = None
    for name, child in directory_entries(root, state, "."):
        relative = name
        accepted = state.observe(relative, child)
        if name != REUSE_FORMAT:
            state.anomaly("unknownCacheFormat", relative)
        elif accepted and stat.S_ISDIR(child.st_mode):
            format_attributes = child
        else:
            state.anomaly("cacheFormatIsNotDirectory", relative)

    namespaces: list[Namespace] = []
    cache_locks: dict[str, tuple[str, int | None]] = {}
    lock_report = empty_lock_report()
    worktree_lock_counts = lock_report["worktree"]
    cache_lock_counts = lock_report["cache"]
    entries_path = format_path / "entries"
    locks_path = format_path / "locks"
    entries_attributes = None
    locks_attributes = None

    if format_attributes is not None:
        for name, child in directory_entries(format_path, state, REUSE_FORMAT):
            relative = f"{REUSE_FORMAT}/{name}"
            accepted = state.observe(relative, child)
            if name == "entries" and accepted and stat.S_ISDIR(child.st_mode):
                entries_attributes = child
            elif name == "locks" and accepted and stat.S_ISDIR(child.st_mode):
                locks_attributes = child
            else:
                state.anomaly("unknownCurrentFormatEntry", relative)
        if entries_attributes is None:
            state.anomaly("entriesDirectoryMissing", f"{REUSE_FORMAT}/entries")
        if locks_attributes is None:
            state.anomaly("locksDirectoryMissing", f"{REUSE_FORMAT}/locks")

    held_descriptors: list[int] = []
    try:
        if locks_attributes is not None:
            for name, child in directory_entries(locks_path, state, f"{REUSE_FORMAT}/locks"):
                relative = f"{REUSE_FORMAT}/locks/{name}"
                accepted = state.observe(relative, child)
                if not accepted or not stat.S_ISREG(child.st_mode):
                    continue
                if child.st_size != 0:
                    state.anomaly("nonemptyLockFile", relative)
                lock_match = LOCK_FILE.fullmatch(name)
                if lock_match is None:
                    state.anomaly("unknownLockFile", relative)
                    continue
                kind, identity = lock_match.groups()
                lock_state, descriptor = lock_probe(locks_path / name)
                if kind == "cache":
                    cache_locks[identity] = (lock_state, descriptor)
                    if lock_state in cache_lock_counts:
                        cache_lock_counts[lock_state] += 1
                else:
                    if lock_state in worktree_lock_counts:
                        worktree_lock_counts[lock_state] += 1
                    if descriptor is not None:
                        os.close(descriptor)
                if lock_state.startswith("unreadable") or lock_state.startswith("lockProbeFailed"):
                    if kind == "cache":
                        cache_lock_counts["probeFailed"] += 1
                    else:
                        worktree_lock_counts["probeFailed"] += 1
                    state.anomaly("lockProbeFailed", relative, lock_state)
                elif lock_state == "exclusiveWriterObserved":
                    state.complete = False

        namespace_identities: set[str] = set()
        if entries_attributes is not None:
            for name, child in directory_entries(entries_path, state, f"{REUSE_FORMAT}/entries"):
                relative = f"{REUSE_FORMAT}/entries/{name}"
                accepted = state.observe(relative, child)
                if (
                    IDENTITY.fullmatch(name) is None
                    or not accepted
                    or not stat.S_ISDIR(child.st_mode)
                ):
                    state.anomaly("malformedNamespace", relative)
                    continue
                namespace_identities.add(name)
                lock_state, descriptor = cache_locks.get(name, ("missing", None))
                namespace = Namespace(name, lock_state)
                namespaces.append(namespace)
                if lock_state == "missing":
                    cache_lock_counts["missingForNamespace"] += 1
                    state.anomaly("namespaceLockMissing", relative)
                    continue
                if lock_state != "sharedLockAcquired" or descriptor is None:
                    state.anomaly("namespaceBusyOrUnobservable", relative, lock_state)
                    continue
                held_descriptors.append(descriptor)
                scan_tree(
                    entries_path / name,
                    (REUSE_FORMAT, "entries", name),
                    state,
                    namespace,
                    validate_namespace_entry,
                )
                if not namespace.apache_versions:
                    state.anomaly("emptyNamespace", relative)
                for project in namespace.projects.values():
                    missing = project.generations - project.build_info_generations
                    if missing:
                        state.anomaly(
                            "generationWithoutBuildInfo",
                            relative,
                            f"{project.group_id}:{project.artifact_id} count={len(missing)}",
                        )

        for identity, (lock_state, descriptor) in cache_locks.items():
            if identity not in namespace_identities:
                cache_lock_counts["withoutNamespace"] += 1
                state.anomaly(
                    "cacheLockWithoutNamespace",
                    f"{REUSE_FORMAT}/locks/cache-{identity}.lock",
                    lock_state,
                )
                if descriptor is not None:
                    os.close(descriptor)
            elif descriptor is not None and descriptor not in held_descriptors:
                os.close(descriptor)
    finally:
        for descriptor in held_descriptors:
            os.close(descriptor)

    for path, before, relative in (
        (root, root_before, "."),
        (
            format_path,
            stat_identity(format_attributes) if format_attributes else None,
            REUSE_FORMAT,
        ),
        (
            entries_path,
            stat_identity(entries_attributes) if entries_attributes else None,
            f"{REUSE_FORMAT}/entries",
        ),
        (
            locks_path,
            stat_identity(locks_attributes) if locks_attributes else None,
            f"{REUSE_FORMAT}/locks",
        ),
    ):
        if before is None:
            continue
        try:
            after = stat_identity(os.lstat(path))
        except OSError:
            after = None
        if before != after:
            state.anomaly("directoryChangedDuringScan", relative)

    report.update(
        {
            "anomalies": state.anomalies,
            "anomalyCount": state.anomaly_count,
            "anomalyDetailsTruncated": state.anomaly_count > len(state.anomalies),
            "currentFormatPresent": format_attributes is not None,
            "inventoryComplete": state.complete and state.anomaly_count == 0,
            "locks": {
                "cache": cache_lock_counts,
                "worktree": worktree_lock_counts,
            },
            "namespaces": [namespace.payload() for namespace in namespaces],
            "scannedEntries": state.scanned_entries,
            "status": (
                "ok"
                if format_attributes is not None and state.complete and state.anomaly_count == 0
                else "empty"
                if format_attributes is None and state.complete and state.anomaly_count == 0
                else "refused"
            ),
            "totals": state.totals.payload(),
        }
    )
    return report, 0 if report["status"] in {"ok", "empty"} else 2


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        root = args.root if args.root is not None else cache_root()
        report, status = inspect_cache(root)
    except (OSError, ValueError) as error:
        report = {
            "inventorySchema": INVENTORY_SCHEMA,
            "retentionAuthority": False,
            "status": "refused",
            "error": str(error),
        }
        status = 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return status


if __name__ == "__main__":
    sys.exit(main())
