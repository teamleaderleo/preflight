#!/usr/bin/env python3
"""One stable required check over the PR workflows that actually apply."""

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

AGGREGATE_WORKFLOW = "Merge gate"
ALWAYS_REQUIRED = {"Source boundary"}
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


def latest_by_name(workflow_runs: Iterable[dict]) -> dict[str, dict]:
    latest: dict[str, dict] = {}
    for run in workflow_runs:
        name = run.get("name")
        if not isinstance(name, str) or name == AGGREGATE_WORKFLOW:
            continue
        current = latest.get(name)
        if current is None or int(run.get("id", 0)) > int(current.get("id", 0)):
            latest[name] = run
    return latest


def belongs_to_pr(run: dict, pr_number: int) -> bool:
    pull_requests = run.get("pull_requests")
    if not isinstance(pull_requests, list):
        return False
    return any(item.get("number") == pr_number for item in pull_requests if isinstance(item, dict))


def applicable_runs(workflow_runs: Iterable[dict], pr_number: int) -> list[dict]:
    return [run for run in workflow_runs if belongs_to_pr(run, pr_number)]


def evaluate(workflow_runs: Iterable[dict]) -> tuple[str, list[str], dict[str, dict]]:
    latest = latest_by_name(workflow_runs)
    expected = ALWAYS_REQUIRED | set(latest)
    pending: list[str] = []
    failed: list[str] = []
    for name in sorted(expected):
        run = latest.get(name)
        if run is None:
            pending.append(f"{name}: missing")
            continue
        status = run.get("status")
        conclusion = run.get("conclusion")
        if status != "completed":
            pending.append(f"{name}: {status or 'pending'}")
        elif conclusion in SUCCESS_CONCLUSIONS:
            continue
        elif conclusion:
            failed.append(f"{name}: {conclusion}")
        else:
            pending.append(f"{name}: awaiting conclusion")
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


def describe_failures(failed_names: Iterable[str], latest: dict[str, dict], token: str) -> list[str]:
    details: list[str] = []
    for item in failed_names:
        name = item.split(":", 1)[0]
        run = latest.get(name, {})
        try:
            jobs = github_jobs(run, token)
            kind, steps = failure_kind(run, jobs)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            kind, steps = "failure; job detail unavailable", [str(exc)]
        url = run.get("html_url") or ""
        detail = f"{name}: {kind}; conclusion={run.get('conclusion') or 'unknown'}"
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
    deadline = time.monotonic() + timeout
    last_state: tuple[str, tuple[str, ...]] | None = None
    last_names: frozenset[str] | None = None
    stable_since = time.monotonic()
    api_errors = 0
    while True:
        try:
            runs = applicable_runs(github_workflow_runs(repo, sha, token), pr_number)
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

        status, details, latest = evaluate(runs)
        names = frozenset(latest)
        if names != last_names:
            stable_since = time.monotonic()
            last_names = names
            print("Observed in-scope PR workflows:")
            for name in sorted(names):
                print(f"- {name}")
        state = (status, tuple(details))
        if state != last_state:
            print(f"Merge gate state: {status}")
            for detail in details:
                print(f"- {detail}")
            last_state = state

        if status == "failed":
            print("One or more in-scope PR workflows failed:", file=sys.stderr)
            for detail in describe_failures(details, latest, token):
                print(f"- {detail}", file=sys.stderr)
            return 1
        if status == "success" and time.monotonic() - stable_since >= settle:
            print("All observed in-scope PR workflows passed after the workflow set settled.")
            return 0
        if time.monotonic() >= deadline:
            print("Merge gate timed out waiting for in-scope PR workflows:", file=sys.stderr)
            for detail in details:
                print(f"- {detail}", file=sys.stderr)
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
