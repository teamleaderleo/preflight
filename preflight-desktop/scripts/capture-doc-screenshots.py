#!/usr/bin/env python3
"""Capture Preflight's canonical README screenshots from an already-built frontend."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path

from playwright.sync_api import Browser, BrowserContext, Page, sync_playwright

CANONICAL_SCREENSHOTS = (
    "desktop-home-dark.png",
    "desktop-home-light.png",
    "desktop-profiles-light.png",
    "walkthrough-benchmark.png",
    "walkthrough-ready.png",
    "walkthrough-setup.png",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:4173")
    parser.add_argument("--output-dir", type=Path, default=Path("docs/images"))
    parser.add_argument(
        "--source-boundary-file",
        type=Path,
        help="Append the captured PNG Git blob fingerprints to verify_source_boundary.py.",
    )
    return parser.parse_args()


def git_blob_sha1(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def python_int_literal(value: int) -> str:
    return f"{value:_}"


def record_source_boundary_fingerprints(source_file: Path, output_dir: Path) -> None:
    source = source_file.read_text(encoding="utf-8")
    for filename in CANONICAL_SCREENSHOTS:
        repo_path = f"docs/images/{filename}"
        data = (output_dir / filename).read_bytes()
        fingerprint = f'            ({python_int_literal(len(data))}, "{git_blob_sha1(data)}"),'
        key = f'    "{repo_path}": frozenset('
        start = source.find(key)
        if start < 0:
            raise RuntimeError(f"source-boundary allowlist is missing {repo_path}")
        end = source.find("        }\n    ),", start)
        if end < 0:
            raise RuntimeError(f"could not locate end of source-boundary allowlist for {repo_path}")
        block = source[start:end]
        if git_blob_sha1(data) in block:
            continue
        source = source[:end] + fingerprint + "\n" + source[end:]
    source_file.write_text(source, encoding="utf-8")


def open_preview(
    browser: Browser,
    base_url: str,
    *,
    width: int,
    height: int,
    scenario: str = "ready",
    theme: str = "light",
) -> tuple[BrowserContext, Page, list[str]]:
    context = browser.new_context(
        viewport={"width": width, "height": height},
        device_scale_factor=1,
        color_scheme=theme,
        locale="en-US",
        timezone_id="UTC",
        reduced_motion="reduce",
    )
    context.add_init_script(
        script=f"""
        window.localStorage.clear();
        window.localStorage.setItem("preflight.theme", {json.dumps(theme)});
        window.localStorage.setItem("preflight.palette", "blueprint");
        window.localStorage.setItem("preflight.sidebar", "expanded");
        """
    )
    # Chromium asks for a conventional favicon even though the packaged Tauri frontend does not
    # use one. Keep that browser-owned request out of the artifact server so a missing favicon
    # cannot masquerade as a product console failure.
    context.route("**/favicon.ico", lambda route: route.fulfill(status=204, body=""))
    page = context.new_page()
    errors: list[str] = []
    page.on("pageerror", lambda error: errors.append(f"page error: {error}"))
    page.on(
        "console",
        lambda message: errors.append(f"console error: {message.text}")
        if message.type == "error"
        else None,
    )
    page.goto(f"{base_url}/?scenario={scenario}", wait_until="networkidle")
    page.evaluate("document.fonts.ready")
    page.wait_for_timeout(180)
    return context, page, errors


def assert_clean(errors: list[str], label: str) -> None:
    if errors:
        raise RuntimeError(f"{label} emitted browser errors: {' | '.join(errors)}")


def capture(page: Page, output: Path) -> None:
    page.screenshot(path=str(output), animations="disabled", full_page=False)


def main() -> int:
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    executable_path = os.environ.get("PREFLIGHT_CHROMIUM_EXECUTABLE")
    launch_options: dict[str, object] = {"headless": True}
    if executable_path:
        launch_options["executable_path"] = executable_path
        launch_options["args"] = ["--no-sandbox"]

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(**launch_options)
        try:
            context, page, errors = open_preview(
                browser, args.base_url, width=1040, height=700, theme="light"
            )
            page.get_by_role("button", name="Launch Starsector").wait_for()
            capture(page, output_dir / "desktop-home-light.png")
            assert_clean(errors, "default Home light")
            context.close()

            context, page, errors = open_preview(
                browser, args.base_url, width=1040, height=700, theme="dark"
            )
            page.get_by_role("button", name="Launch Starsector").wait_for()
            capture(page, output_dir / "desktop-home-dark.png")
            assert_clean(errors, "default Home dark")
            context.close()

            context, page, errors = open_preview(
                browser, args.base_url, width=1040, height=700, theme="light"
            )
            page.get_by_role("button", name="Mods").click()
            # React Activity retains the hidden Home tree, which carries the same profile name.
            # Bind the readiness check to the visible destination instead of a duplicated page-wide
            # string so a retained page cannot make the strict locator ambiguous.
            mods_page = page.locator(".profiles-page:visible")
            mods_page.get_by_text("Main campaign", exact=True).wait_for()
            page.wait_for_timeout(100)
            capture(page, output_dir / "desktop-profiles-light.png")
            assert_clean(errors, "Mods/profile view")
            context.close()

            context, page, errors = open_preview(
                browser,
                args.base_url,
                width=720,
                height=560,
                scenario="setup",
                theme="light",
            )
            page.get_by_role("button", name="Choose game folder").wait_for()
            capture(page, output_dir / "walkthrough-setup.png")
            assert_clean(errors, "minimum-window setup")
            context.close()

            context, page, errors = open_preview(
                browser, args.base_url, width=720, height=560, theme="light"
            )
            page.get_by_role("button", name="Launch Starsector").wait_for()
            capture(page, output_dir / "walkthrough-ready.png")
            assert_clean(errors, "minimum-window ready")
            context.close()

            context, page, errors = open_preview(
                browser, args.base_url, width=1040, height=700, theme="light"
            )
            page.get_by_role("button", name="Speed").click()
            page.get_by_role("button", name="Measure speed").click()
            page.get_by_role("heading", name="Benchmark", exact=True).wait_for()
            page.get_by_role("button", name="Run benchmark").wait_for()
            page.wait_for_timeout(100)
            capture(page, output_dir / "walkthrough-benchmark.png")
            assert_clean(errors, "benchmark pre-run")
            context.close()
        finally:
            browser.close()

    missing = [name for name in CANONICAL_SCREENSHOTS if not (output_dir / name).is_file()]
    if missing:
        raise RuntimeError(f"missing canonical screenshots: {', '.join(missing)}")
    if args.source_boundary_file:
        record_source_boundary_fingerprints(args.source_boundary_file.resolve(), output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
