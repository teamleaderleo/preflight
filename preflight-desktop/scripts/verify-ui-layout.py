#!/usr/bin/env python3
"""Exercise Home transitions and responsive geometry in a real browser."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from functools import partial
import html
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
from threading import Thread
from typing import Iterator

from playwright.sync_api import Browser, BrowserContext, Page, sync_playwright


VIEWPORTS = (
    (720, 560),
    (800, 600),
    (880, 640),
    (960, 680),
    (1040, 700),
    (1120, 700),
    (1280, 720),
    (1440, 800),
)

GEOMETRY_SELECTORS = (
    ".home-playtime",
    ".launch-console__status-line",
    ".home-launch-identity",
    ".home-ship-picker",
    ".launch-console__actions",
    ".last-run-health",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url")
    parser.add_argument("--dist-dir", type=Path, default=Path("dist"))
    parser.add_argument("--output-dir", type=Path)
    return parser.parse_args()


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        pass


@contextmanager
def frontend_url(base_url: str | None, dist_dir: Path) -> Iterator[str]:
    if base_url is not None:
        yield base_url
        return
    if not (dist_dir / "index.html").is_file():
        raise RuntimeError(f"built frontend not found under {dist_dir}; run npm run build first")
    handler = partial(QuietHandler, directory=str(dist_dir.resolve()))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        thread.join()
        server.server_close()


def open_ready(browser: Browser, base_url: str, width: int, height: int) -> tuple[BrowserContext, Page, list[str]]:
    context = browser.new_context(
        viewport={"width": width, "height": height},
        device_scale_factor=1,
        color_scheme="dark",
        locale="en-US",
        timezone_id="UTC",
        reduced_motion="no-preference",
    )
    context.add_init_script(
        script="""
        window.localStorage.clear();
        window.localStorage.setItem("preflight.theme", "dark");
        window.localStorage.setItem("preflight.palette", "blueprint");
        window.localStorage.setItem("preflight.sidebar", "expanded");
        """
    )
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
    page.goto(f"{base_url}/?scenario=ready", wait_until="networkidle")
    page.evaluate("document.fonts.ready")
    page.get_by_role("button", name="Launch Starsector").wait_for()
    return context, page, errors


def visible_rects(page: Page) -> dict[str, dict[str, float]]:
    return page.evaluate(
        """(selectors) => Object.fromEntries(selectors.flatMap((selector) => {
          const element = document.querySelector(selector);
          if (!(element instanceof HTMLElement)) return [];
          const style = getComputedStyle(element);
          const rect = element.getBoundingClientRect();
          if (style.display === "none" || style.visibility === "hidden" || rect.width === 0 || rect.height === 0) return [];
          return [[selector, { x: rect.x, y: rect.y, width: rect.width, height: rect.height }]];
        }))""",
        list(GEOMETRY_SELECTORS),
    )


def overlaps(left: dict[str, float], right: dict[str, float], tolerance: float = 1.0) -> bool:
    return not (
        left["x"] + left["width"] <= right["x"] + tolerance
        or right["x"] + right["width"] <= left["x"] + tolerance
        or left["y"] + left["height"] <= right["y"] + tolerance
        or right["y"] + right["height"] <= left["y"] + tolerance
    )


def assert_home_geometry(page: Page, label: str) -> dict[str, object]:
    measurement = page.evaluate(
        """() => {
          const primary = document.querySelector(".launch-console__primary")?.getBoundingClientRect();
          const launch = document.querySelector(".button--launch")?.getBoundingClientRect();
          const workspace = document.querySelector(".page-viewport--home");
          if (!primary || !launch || !(workspace instanceof HTMLElement)) return null;
          return {
            viewportWidth: innerWidth,
            viewportHeight: innerHeight,
            documentScrollWidth: document.documentElement.scrollWidth,
            workspaceClientWidth: workspace.clientWidth,
            workspaceScrollWidth: workspace.scrollWidth,
            primary: { x: primary.x, y: primary.y, width: primary.width, height: primary.height },
            launch: { x: launch.x, y: launch.y, width: launch.width, height: launch.height },
            launchConnected: document.querySelector(".button--launch")?.isConnected === true,
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: Home launch geometry is missing")
    if measurement["documentScrollWidth"] > measurement["viewportWidth"] + 1:
        raise RuntimeError(f"{label}: document has horizontal overflow: {measurement}")
    if measurement["workspaceScrollWidth"] > measurement["workspaceClientWidth"] + 1:
        raise RuntimeError(f"{label}: Home workspace has horizontal overflow: {measurement}")
    if not measurement["launchConnected"] or measurement["launch"]["width"] < 260:
        raise RuntimeError(f"{label}: primary launch action disappeared or collapsed: {measurement}")
    if measurement["launch"]["y"] < 0 or measurement["launch"]["y"] + measurement["launch"]["height"] > measurement["viewportHeight"] - 4:
        raise RuntimeError(f"{label}: primary launch action is outside the initial viewport: {measurement}")
    primary_center = measurement["primary"]["x"] + measurement["primary"]["width"] / 2
    launch_center = measurement["launch"]["x"] + measurement["launch"]["width"] / 2
    if abs(primary_center - launch_center) > 1:
        raise RuntimeError(f"{label}: launch action is not centered: {measurement}")

    rects = visible_rects(page)
    names = list(rects)
    for index, name in enumerate(names):
        for other in names[index + 1 :]:
            if overlaps(rects[name], rects[other]):
                raise RuntimeError(f"{label}: {name} overlaps {other}: {rects}")
    return {"layout": measurement, "elements": rects}


def assert_focus_stable(page: Page, label: str) -> None:
    result = page.evaluate(
        """async (selectors) => {
          const launch = document.querySelector(".button--launch");
          const read = () => Object.fromEntries(selectors.flatMap((selector) => {
            const element = document.querySelector(selector);
            if (!(element instanceof HTMLElement)) return [];
            const rect = element.getBoundingClientRect();
            if (getComputedStyle(element).display === "none" || rect.width === 0 || rect.height === 0) return [];
            return [[selector, [rect.x, rect.y, rect.width, rect.height]]];
          }));
          const before = read();
          window.dispatchEvent(new Event("blur"));
          window.dispatchEvent(new Event("focus"));
          await new Promise((resolve) => setTimeout(resolve, 80));
          return { before, after: read(), sameLaunch: launch === document.querySelector(".button--launch") && launch?.isConnected === true };
        }""",
        list(GEOMETRY_SELECTORS),
    )
    if not result["sameLaunch"] or result["before"] != result["after"]:
        raise RuntimeError(f"{label}: refocus replaced or moved Home controls: {result}")


def assert_ship_moves(page: Page, label: str) -> None:
    canvas = page.locator(".home-flight-instrument canvas")
    canvas.wait_for(state="visible")
    first = canvas.evaluate("canvas => canvas.toDataURL()")
    page.wait_for_timeout(160)
    second = canvas.evaluate("canvas => canvas.toDataURL()")
    if first == second:
        raise RuntimeError(f"{label}: ship did not rotate")

    page.get_by_role("button", name="Hide ship").click()
    canvas.wait_for(state="hidden")
    page.wait_for_timeout(160)
    page.get_by_role("button", name="Show ship").click()
    canvas.wait_for(state="visible")
    resumed = canvas.evaluate("canvas => canvas.toDataURL()")
    page.wait_for_timeout(160)
    resumed_next = canvas.evaluate("canvas => canvas.toDataURL()")
    if resumed == resumed_next:
        raise RuntimeError(f"{label}: reopened ship did not resume rotation")


def capture(page: Page, output_dir: Path | None, name: str) -> None:
    if output_dir is None:
        return
    output_dir.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(output_dir / name), animations="disabled", full_page=False)


def render_contact_sheet(browser: Browser, output_dir: Path) -> None:
    cards: list[str] = []
    for width, height in VIEWPORTS:
        label = f"{width}x{height}"
        for state in ("full", "idle", "compact", "minimal"):
            filename = f"home-{state}-{label}.png"
            cards.append(
                f'<figure><figcaption>{html.escape(label)} · {state}</figcaption>'
                f'<img src="{html.escape(filename)}" alt="Home {html.escape(state)} at {html.escape(label)}"></figure>'
            )
    document = f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><title>Preflight UI matrix</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; padding: 20px; color: #dce5f7; background: #080c16; font: 14px system-ui, sans-serif; }}
  h1 {{ margin: 0 0 16px; font-size: 22px; }}
  main {{ display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }}
  figure {{ margin: 0; overflow: hidden; border: 1px solid #273149; border-radius: 8px; background: #101727; }}
  figcaption {{ padding: 8px 10px; font-weight: 650; }}
  img {{ display: block; width: 100%; height: auto; background: #070b14; }}
</style>
<body><h1>Home viewport and visibility matrix</h1><main>{''.join(cards)}</main></body></html>"""
    index = output_dir / "index.html"
    index.write_text(document, encoding="utf-8")

    context = browser.new_context(viewport={"width": 1500, "height": 900}, device_scale_factor=1)
    try:
        page = context.new_page()
        page.goto(index.resolve().as_uri(), wait_until="load")
        page.screenshot(path=str(output_dir / "overview.png"), full_page=True)
    finally:
        context.close()


def main() -> int:
    args = parse_args()
    executable_path = os.environ.get("PREFLIGHT_CHROMIUM_EXECUTABLE")
    launch_options: dict[str, object] = {"headless": True}
    if executable_path:
        launch_options["executable_path"] = executable_path
        launch_options["args"] = ["--no-sandbox"]

    with frontend_url(args.base_url, args.dist_dir) as base_url:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(**launch_options)
            geometry: dict[str, object] = {}
            try:
                for width, height in VIEWPORTS:
                    label = f"{width}x{height}"
                    context, page, errors = open_ready(browser, base_url, width, height)
                    try:
                        geometry[f"{label}-full"] = assert_home_geometry(page, f"{label} full")
                        assert_focus_stable(page, f"{label} full")
                        if (width, height) in ((1040, 700), (1280, 720)):
                            assert_ship_moves(page, f"{label} full")
                        capture(page, args.output_dir, f"home-full-{label}.png")

                        page.evaluate("document.activeElement instanceof HTMLElement && document.activeElement.blur()")
                        page.mouse.move(width - 2, height - 2)
                        page.wait_for_timeout(2400)
                        idle = page.evaluate(
                            """() => ({
                              idle: document.querySelector('.launch-console--layout-settled')?.classList.contains('home-hud--idle'),
                              hudOpacity: getComputedStyle(document.querySelector('.home-playtime')).opacity,
                              launchOpacity: getComputedStyle(document.querySelector('.button--launch')).opacity,
                            })"""
                        )
                        if idle != {"idle": True, "hudOpacity": "0", "launchOpacity": "1"}:
                            raise RuntimeError(f"{label} idle: HUD did not recede while launch remained visible: {idle}")
                        capture(page, args.output_dir, f"home-idle-{label}.png")

                        page.mouse.move(width / 2, height / 2)
                        page.wait_for_function(
                            "document.querySelector('.launch-console--layout-settled')?.classList.contains('home-hud--visible')",
                        )
                        page.get_by_role("button", name="Hide ship").click()
                        page.locator(".home-flight-instrument").wait_for(state="hidden")
                        if not page.locator(".home-playtime").is_visible():
                            raise RuntimeError(f"{label} compact: hiding the ship also hid playtime")
                        geometry[f"{label}-compact"] = assert_home_geometry(page, f"{label} compact")
                        assert_focus_stable(page, f"{label} compact")
                        capture(page, args.output_dir, f"home-compact-{label}.png")

                        page.get_by_role("button", name="Hide time").click()
                        page.locator(".home-playtime").wait_for(state="hidden")
                        geometry[f"{label}-minimal"] = assert_home_geometry(page, f"{label} minimal")
                        assert_focus_stable(page, f"{label} minimal")
                        capture(page, args.output_dir, f"home-minimal-{label}.png")

                        if errors:
                            raise RuntimeError(f"{label}: browser errors: {' | '.join(errors)}")
                    finally:
                        context.close()
                if args.output_dir is not None:
                    args.output_dir.mkdir(parents=True, exist_ok=True)
                    (args.output_dir / "geometry.json").write_text(
                        json.dumps(geometry, indent=2, sort_keys=True) + "\n",
                        encoding="utf-8",
                    )
                    render_contact_sheet(browser, args.output_dir)
            finally:
                browser.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
