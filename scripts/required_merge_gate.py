#!/usr/bin/env python3
"""One stable required check over the base-known PR workflows that actually apply."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterable
from pathlib import Path

ALWAYS_REQUIRED_PATHS = {".github/workflows/source-boundary.yml"}
SUCCESS_CONCLUSIONS = {"success"}
BOOTSTRAP_STEP_MARKERS = (
    "set up job",
    "checkout",
    "actions/checkout",
    "setup-build-jdk",
    "setup-java",
    "setup-node",
    "setup-python",
    "rust-toolchain",
    "download action repository",
)


def trusted_workflow_paths(root: Path = Path(".github/workflows")) -> frozenset[str]:
    if not root.is_dir():
        raise RuntimeError(f"trusted workflow directory is missing: {root}")
    paths = frozenset(
        path.as_posix()
        for path in root.iterdir()
        if path.is_file() and path.suffix.lower() in {".yml", ".yaml"}
    )
    missing = ALWAYS_REQUIRED_PATHS - paths
    if missing:
        raise RuntimeError("required base workflow is missing: " + ", ".join(sorted(missing)))
    return paths


def latest_by_path(workflow_runs: Iterable[dict], trusted_paths: frozenset[str]) -> dict[str, dict]:
    latest: dict[str, dict] = {}
    for run in workflow_runs:
        path = run.get("path")
        if not isinstance(path, str) or path not in trusted_paths:
            # Workflow display names are PR-controlled. Only a workflow file that already exists in
            # the checked-out base revision can contribute to the aggregate required check.
            continue
        current = latest.get(path)
        if current is None or int(run.get("id", 0)) > int(current.get("id", 0)):
            latest[path] = run
    return latest


def belongs_to_pr(run: dict, pull_request: dict) -> bool:
    head = pull_request.get("head")
    if not isinstance(head, dict):
        return False
    head_repo = head.get("repo")
    run_repo = run.get("head_repository")
    return (
        run.get("head_sha") == head.get("sha")
        and run.get("head_branch") == head.get("ref")
        and isinstance(head_repo, dict)
        and isinstance(run_repo, dict)
        and run_repo.get("id") == head_repo.get("id")
    )


def applicable_runs(workflow_runs: Iterable[dict], pull_request: dict) -> list[dict]:
    return [run for run in workflow_runs if belongs_to_pr(run, pull_request)]


def trusted_workflow_definition_changes(
    changed_files: Iterable[dict], trusted_paths: frozenset[str]
) -> list[str]:
    changed: set[str] = set()
    for item in changed_files:
        if not isinstance(item, dict):
            continue
        filename = item.get("filename")
        previous = item.get("previous_filename")
        if isinstance(filename, str) and filename in trusted_paths:
            changed.add(filename)
        if isinstance(previous, str) and previous in trusted_paths:
            changed.add(previous)
    return sorted(changed)


def run_label(path: str, run: dict | None) -> str:
    if run is None:
        return path
    name = run.get("name")
    return f"{name} [{path}]" if isinstance(name, str) and name else path


def evaluate(
    workflow_runs: Iterable[dict], trusted_paths: frozenset[str]
) -> tuple[str, list[str], dict[str, dict]]:
    latest = latest_by_path(workflow_runs, trusted_paths)
    expected = ALWAYS_REQUIRED_PATHS | set(latest)
    pending: list[str] = []
    failed: list[str] = []
    for path in sorted(expected):
        run = latest.get(path)
        if run is None:
            pending.append(path)
            continue
        status = run.get("status")
        conclusion = run.get("conclusion")
        if status != "completed":
            pending.append(path)
        elif conclusion in SUCCESS_CONCLUSIONS:
            continue
        elif conclusion:
            failed.append(path)
        else:
            pending.append(path)
    if failed:
        return "failed", failed, latest
    if pending:
        return "pending", pending, latest
    return "success", [], latest


def api_json(url: str, token: str) -> tuple[dict, str]:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "api.github.com":
        raise RuntimeError(f"Refusing non-GitHub API URL: {url}")
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "preflight-required-merge-gate",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response), response.headers.get("Link", "")


def api_list(url: str, token: str) -> tuple[list[dict], str]:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "api.github.com":
        raise RuntimeError(f"Refusing non-GitHub API URL: {url}")
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "preflight-required-merge-gate",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
        if not isinstance(payload, list):
            raise RuntimeError("GitHub API response is not a list")
        return payload, response.headers.get("Link", "")


def github_pull_request(repo: str, pr_number: int, token: str) -> dict:
    payload, _ = api_json(f"https://api.github.com/repos/{repo}/pulls/{pr_number}", token)
    if not isinstance(payload.get("head"), dict):
        raise RuntimeError("GitHub pull-request response has no head object")
    return payload


def github_changed_files(repo: str, pr_number: int, token: str) -> list[dict]:
    url: str | None = f"https://api.github.com/repos/{repo}/pulls/{pr_number}/files?per_page=100"
    files: list[dict] = []
    pages = 0
    while url:
        pages += 1
        if pages > 30:
            raise RuntimeError("pull-request file pagination exceeded 30 pages")
        page, link = api_list(url, token)
        files.extend(page)
        url = next_link(link)
    return files


def github_workflow_runs(repo: str, sha: str, token: str) -> list[dict]:
    query = urllib.parse.urlencode({"head_sha": sha, "event": "pull_request", "per_page": 100})
    url: str | None = f"https://api.github.com/repos/{repo}/actions/runs?{query}"
    runs: list[dict] = []
    pages = 0
    while url:
        pages += 1
        if pages > 10:
            raise RuntimeError("workflow-run pagination exceeded 10 pages")
        payload, link = api_json(url, token)
        page_runs = payload.get("workflow_runs")
        if not isinstance(page_runs, list):
            raise RuntimeError("GitHub workflow-runs response has no workflow_runs list")
        runs.extend(page_runs)
        url = next_link(link)
    return runs


def github_jobs(run: dict, token: str) -> list[dict]:
    jobs_url = run.get("jobs_url")
    if not isinstance(jobs_url, str) or not jobs_url:
        return []
    separator = "&" if "?" in jobs_url else "?"
    url: str | None = f"{jobs_url}{separator}filter=latest&per_page=100"
    jobs: list[dict] = []
    pages = 0
    while url:
        pages += 1
        if pages > 10:
            raise RuntimeError("workflow-job pagination exceeded 10 pages")
        payload, link = api_json(url, token)
        page_jobs = payload.get("jobs")
        if not isinstance(page_jobs, list):
            raise RuntimeError("GitHub workflow-jobs response has no jobs list")
        jobs.extend(page_jobs)
        url = next_link(link)
    return jobs


def next_link(link: str) -> str | None:
    for part in link.split(","):
        section = part.strip()
        if 'rel="next"' not in section:
            continue
        start = section.find("<")
        end = section.find(">", start + 1)
        if start >= 0 and end > start:
            candidate = section[start + 1 : end]
            parsed = urllib.parse.urlparse(candidate)
            if parsed.scheme == "https" and parsed.hostname == "api.github.com":
                return candidate
    return None


def failed_step_names(jobs: Iterable[dict]) -> list[str]:
    result: list[str] = []
    for job in jobs:
        if job.get("conclusion") not in {"failure", "startup_failure", "timed_out"}:
            continue
        for step in job.get("steps") or []:
            if isinstance(step, dict) and step.get("conclusion") == "failure":
                name = step.get("name")
                if isinstance(name, str) and name:
                    result.append(name)
    return result


def failure_kind(run: dict, jobs: Iterable[dict]) -> tuple[str, list[str]]:
    conclusion = run.get("conclusion")
    steps = failed_step_names(jobs)
    if conclusion == "cancelled":
        return "workflow cancellation; rerun required", steps
    if conclusion == "startup_failure":
        return "runner/action bootstrap failure", steps
    if steps and all(any(marker in step.lower() for marker in BOOTSTRAP_STEP_MARKERS) for step in steps):
        return "runner/action bootstrap failure", steps
    return "product/check failure", steps


def describe_failures(failed_paths: Iterable[str], latest: dict[str, dict], token: str) -> list[str]:
    details: list[str] = []
    for path in failed_paths:
        run = latest.get(path, {})
        try:
            jobs = github_jobs(run, token)
            kind, steps = failure_kind(run, jobs)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            kind, steps = "failure; job detail unavailable", [str(exc)]
        url = run.get("html_url") or ""
        detail = f"{run_label(path, run)}: {kind}; conclusion={run.get('conclusion') or 'unknown'}"
        if steps:
            detail += "; failed steps=" + ", ".join(steps[:6])
        if url:
            detail += f"; {url}"
        details.append(detail)
    return details


def wait_for_workflows(
    repo: str,
    sha: str,
    pr_number: int,
    timeout: int,
    poll: int,
    settle: int,
) -> int:
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        print("GITHUB_TOKEN is required to read GitHub Actions runs", file=sys.stderr)
        return 2
    try:
        trusted_paths = trusted_workflow_paths()
        pull_request = github_pull_request(repo, pr_number, token)
        changed_files = github_changed_files(repo, pr_number, token)
    except (OSError, urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
        print(f"Merge gate could not establish trusted workflow/PR identity: {exc}", file=sys.stderr)
        return 2
    head = pull_request.get("head", {})
    if head.get("sha") != sha:
        print(
            f"Merge gate head moved before evaluation: expected {sha}, current {head.get('sha') or 'unknown'}",
            file=sys.stderr,
        )
        return 1

    workflow_changes = trusted_workflow_definition_changes(changed_files, trusted_paths)
    if workflow_changes:
        print(
            "Merge gate refuses pull-request edits to trusted workflow definitions; use the reviewed "
            "repository bypass path for workflow-policy changes:",
            file=sys.stderr,
        )
        for path in workflow_changes:
            print(f"- {path}", file=sys.stderr)
        return 1

    deadline = time.monotonic() + timeout
    last_state: tuple[str, tuple[str, ...]] | None = None
    last_paths: frozenset[str] | None = None
    stable_since = time.monotonic()
    api_errors = 0
    while True:
        try:
            runs = applicable_runs(github_workflow_runs(repo, sha, token), pull_request)
            api_errors = 0
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            api_errors += 1
            state = ("api", (f"GitHub Actions API unavailable: {exc}",))
            if state != last_state:
                print(state[1][0], file=sys.stderr)
                last_state = state
            if time.monotonic() >= deadline:
                print(f"Merge gate timed out after {api_errors} consecutive API errors", file=sys.stderr)
                return 1
            time.sleep(poll)
            continue

        status, paths, latest = evaluate(runs, trusted_paths)
        observed_paths = frozenset(latest)
        if observed_paths != last_paths:
            stable_since = time.monotonic()
            last_paths = observed_paths
            print("Observed base-known in-scope PR workflows:")
            for path in sorted(observed_paths):
                print(f"- {run_label(path, latest[path])}")
        state = (status, tuple(paths))
        if state != last_state:
            print(f"Merge gate state: {status}")
            for path in paths:
                run = latest.get(path)
                if run is None:
                    print(f"- {path}: missing")
                elif run.get("status") != "completed":
                    print(f"- {run_label(path, run)}: {run.get('status') or 'pending'}")
                else:
                    print(f"- {run_label(path, run)}: {run.get('conclusion') or 'awaiting conclusion'}")
            last_state = state

        if status == "failed":
            print("One or more in-scope PR workflows failed:", file=sys.stderr)
            for detail in describe_failures(paths, latest, token):
                print(f"- {detail}", file=sys.stderr)
            return 1
        if status == "success" and time.monotonic() - stable_since >= settle:
            print("All observed base-known in-scope PR workflows passed after the workflow set settled.")
            return 0
        if time.monotonic() >= deadline:
            print("Merge gate timed out waiting for in-scope PR workflows:", file=sys.stderr)
            for path in paths:
                print(f"- {run_label(path, latest.get(path))}", file=sys.stderr)
            return 1
        time.sleep(poll)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--timeout", type=int, default=3600)
    parser.add_argument("--poll", type=int, default=15)
    parser.add_argument("--settle", type=int, default=45)
    args = parser.parse_args()
    if args.pr <= 0 or args.timeout <= 0 or args.poll <= 0 or args.settle < 0:
        parser.error("--pr, --timeout and --poll must be positive; --settle cannot be negative")
    return wait_for_workflows(args.repo, args.sha, args.pr, args.timeout, args.poll, args.settle)


if __name__ == "__main__":
    raise SystemExit(main())
