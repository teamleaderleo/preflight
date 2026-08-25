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
import re
from threading import Thread
from typing import Iterator

from playwright.sync_api import Browser, BrowserContext, Page, sync_playwright


VIEWPORTS = (
    (480, 640),
    (600, 560),
    (720, 560),
    (800, 600),
    (880, 640),
    (960, 680),
    (1040, 700),
    (1120, 700),
    (1280, 720),
    (1440, 800),
)

PAGE_SWEEP_WIDTHS = {480, 720, 1040, 1440}
PRIMARY_PAGES = ("Speed", "Mods", "Help", "Settings")
HOME_RECOVERY_SCENARIOS = ("setup", "cache-repair", "cache-unsafe", "run-failure", "running")
PAGE_RECOVERY_SCENARIOS = (
    "benchmark-unavailable",
    "mod-problems",
    "profile-mismatch",
    "update-error",
    "report-error",
)
PAGE_DATA_SCENARIOS = ("frame-pacing",)

GEOMETRY_SELECTORS = (
    ".home-playtime",
    ".launch-console__status-line",
    ".launch-console__note",
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


def open_preview(
    browser: Browser,
    base_url: str,
    width: int,
    height: int,
    scenario: str = "ready",
    wait_selector: str = ".button--launch",
) -> tuple[BrowserContext, Page, list[str]]:
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
    page.goto(f"{base_url}/?scenario={scenario}", wait_until="networkidle")
    page.evaluate("document.fonts.ready")
    page.locator(wait_selector).wait_for()
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
            workspaceBottom: workspace.getBoundingClientRect().bottom,
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
    if measurement["launch"]["y"] < 0 or measurement["launch"]["y"] + measurement["launch"]["height"] > measurement["workspaceBottom"] - 4:
        raise RuntimeError(f"{label}: primary launch action is outside the initial viewport: {measurement}")
    primary_center = measurement["primary"]["x"] + measurement["primary"]["width"] / 2
    launch_center = measurement["launch"]["x"] + measurement["launch"]["width"] / 2
    if abs(primary_center - launch_center) > 1:
        raise RuntimeError(f"{label}: launch action is not centered: {measurement}")

    rects = visible_rects(page)
    compact = page.evaluate("document.documentElement.dataset.homeMode === 'compact'")
    if compact and ".home-launch-identity" in rects:
        identity = rects[".home-launch-identity"]
        gap = measurement["launch"]["y"] - (identity["y"] + identity["height"])
        if gap < 6 or gap > 28:
            raise RuntimeError(f"{label}: hidden-ship launch caption gap is awkward ({gap:.1f}px): {rects}")
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


def assert_home_toggle_stability(page: Page, label: str) -> None:
    """Display preferences may change the canvas, never the launch/control scaffold."""
    page.mouse.move(page.viewport_size["width"] / 2, page.viewport_size["height"] / 2)
    page.wait_for_function(
        "document.querySelector('.launch-console--layout-settled')?.classList.contains('home-hud--visible')"
    )

    def scaffold() -> dict[str, list[float]]:
        return page.evaluate(
            """() => Object.fromEntries(['.button--launch', '.launch-console__status-line'].map((selector) => {
              const rect = document.querySelector(selector).getBoundingClientRect();
              return [selector, [rect.x, rect.y, rect.width, rect.height]];
            }))"""
        )

    before = scaffold()
    ship = page.get_by_role("button", name="Ship", exact=True)
    time = page.get_by_role("button", name="Playtime", exact=True)
    ship.click()
    page.locator(".home-flight-instrument").wait_for(state="hidden")
    after_ship = scaffold()
    time.click()
    page.locator(".home-playtime").wait_for(state="hidden")
    after_both = scaffold()

    for state, measurement in (("ship hidden", after_ship), ("ship and time hidden", after_both)):
        for selector in before:
            if any(abs(one - two) > 1 for one, two in zip(before[selector], measurement[selector])):
                raise RuntimeError(
                    f"{label}: {state} moved {selector}: before={before[selector]}, after={measurement[selector]}"
                )

    time.click()
    page.locator(".home-playtime").wait_for(state="visible")
    ship.click()
    page.locator(".home-flight-instrument").wait_for(state="visible")


def assert_quick_settings_geometry(page: Page, label: str) -> dict[str, object]:
    measurement = page.evaluate(
        """() => {
          const panel = document.querySelector('.quick-settings');
          const launch = document.querySelector('.button--launch');
          const toggle = document.querySelector('.home-options-toggle');
          const playtime = document.querySelector('.home-playtime');
          if (!(panel instanceof HTMLElement) || !(launch instanceof HTMLElement) || !(toggle instanceof HTMLElement)) return null;
          const rect = (element) => {
            const value = element.getBoundingClientRect();
            return { x: value.x, y: value.y, width: value.width, height: value.height,
              right: value.right, bottom: value.bottom };
          };
          return {
            viewportWidth: innerWidth,
            viewportHeight: innerHeight,
            panel: rect(panel),
            launch: rect(launch),
            toggle: rect(toggle),
            clientWidth: panel.clientWidth,
            scrollWidth: panel.scrollWidth,
            clientHeight: panel.clientHeight,
            scrollHeight: panel.scrollHeight,
            playtimeVisible: playtime instanceof HTMLElement
              && getComputedStyle(playtime).visibility !== 'hidden'
              && getComputedStyle(playtime).opacity !== '0',
            fields: [...panel.querySelectorAll('input:not([type="checkbox"]), select, button')].map(rect),
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: quick settings geometry is missing")
    if measurement["scrollWidth"] > measurement["clientWidth"] + 1:
        raise RuntimeError(f"{label}: quick settings scroll horizontally: {measurement}")
    if measurement["scrollHeight"] > measurement["clientHeight"] + 1:
        raise RuntimeError(f"{label}: quick settings require a nested scrollbar: {measurement}")
    if measurement["playtimeVisible"]:
        raise RuntimeError(f"{label}: playtime remains visible behind quick settings: {measurement}")
    panel = measurement["panel"]
    launch = measurement["launch"]
    toggle = measurement["toggle"]
    if panel["x"] < -1 or panel["right"] > measurement["viewportWidth"] + 1:
        raise RuntimeError(f"{label}: quick settings leave the viewport: {measurement}")
    if panel["bottom"] > launch["y"] - 4:
        raise RuntimeError(f"{label}: quick settings crowd the launch action: {measurement}")
    if toggle["width"] < 44 or toggle["height"] < 44:
        raise RuntimeError(f"{label}: Options toggle is undersized: {measurement}")
    if any(field["height"] < 44 for field in measurement["fields"]):
        raise RuntimeError(f"{label}: quick settings contain an undersized control: {measurement}")
    return measurement


def assert_ship_moves(page: Page, label: str) -> None:
    canvas = page.locator(".home-flight-instrument canvas")
    canvas.wait_for(state="visible")
    first = canvas.evaluate("canvas => canvas.toDataURL()")
    page.wait_for_timeout(160)
    second = canvas.evaluate("canvas => canvas.toDataURL()")
    if first == second:
        raise RuntimeError(f"{label}: ship did not rotate")

    page.get_by_role("button", name="Ship", exact=True).click()
    canvas.wait_for(state="hidden")
    page.wait_for_timeout(160)
    page.get_by_role("button", name="Ship", exact=True).click()
    canvas.wait_for(state="visible")
    resumed = canvas.evaluate("canvas => canvas.toDataURL()")
    page.wait_for_timeout(160)
    resumed_next = canvas.evaluate("canvas => canvas.toDataURL()")
    if resumed == resumed_next:
        raise RuntimeError(f"{label}: reopened ship did not resume rotation")


def assert_hangar_geometry(page: Page, label: str) -> dict[str, object]:
    measurement = page.evaluate(
        """() => {
          const display = document.querySelector('.hangar-display')?.getBoundingClientRect();
          const controls = document.querySelector('.hangar-stage-controls')?.getBoundingClientRect();
          const reset = document.querySelector('.hangar-reset-action')?.getBoundingClientRect();
          const groups = [...document.querySelectorAll('.hangar-control-group')]
            .map((element) => element.getBoundingClientRect());
          if (!display || !controls || !reset || groups.length !== 3) return null;
          const rect = (value) => ({ x: value.x, y: value.y, width: value.width, height: value.height,
            right: value.right, bottom: value.bottom });
          return {
            viewportWidth: innerWidth,
            viewportHeight: innerHeight,
            documentScrollWidth: document.documentElement.scrollWidth,
            display: rect(display),
            controls: rect(controls),
            reset: rect(reset),
            groups: groups.map(rect),
            sliderCount: document.querySelectorAll('.hangar-control-group input[type="range"]').length,
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: Hangar geometry is missing")
    if measurement["documentScrollWidth"] > measurement["viewportWidth"] + 1:
        raise RuntimeError(f"{label}: document has horizontal overflow: {measurement}")
    display = measurement["display"]
    if display["right"] > measurement["viewportWidth"] + 1 or display["bottom"] > measurement["viewportHeight"] + 1:
        raise RuntimeError(f"{label}: Hangar is clipped by the initial viewport: {measurement}")
    if measurement["sliderCount"] != 7:
        raise RuntimeError(f"{label}: expected seven independent Hangar sliders: {measurement}")
    reset = measurement["reset"]
    if reset["width"] < 44 or reset["height"] < 44 or reset["right"] > display["right"] or reset["bottom"] > display["bottom"]:
        raise RuntimeError(f"{label}: reset is clipped or undersized: {measurement}")
    for group in measurement["groups"]:
        if group["right"] > display["right"] + 1 or group["bottom"] > display["bottom"] + 1:
            raise RuntimeError(f"{label}: a Hangar control group is clipped: {measurement}")

    for outline, interior in (("Outline detail", "Interior detail"), ("Outline smoothing", "Interior smoothing")):
        first = page.get_by_role("slider", name=outline).bounding_box()
        second = page.get_by_role("slider", name=interior).bounding_box()
        if first is None or second is None or first["y"] >= second["y"]:
            raise RuntimeError(f"{label}: {outline} is not stacked above {interior}")
    return measurement


def assert_hangar_interaction(page: Page, label: str) -> None:
    canvas = page.locator(".hangar-stage__instrument canvas")
    canvas.wait_for(state="visible")
    bounds = canvas.bounding_box()
    if bounds is None:
        raise RuntimeError(f"{label}: interactive ship canvas is missing")
    before = page.evaluate("localStorage.getItem('preflight.instrumentHullView.v1')")
    page.mouse.move(bounds["x"] + bounds["width"] / 2, bounds["y"] + bounds["height"] / 2)
    page.mouse.wheel(0, -120)
    after_zoom = page.evaluate("localStorage.getItem('preflight.instrumentHullView.v1')")
    if before == after_zoom:
        raise RuntimeError(f"{label}: scrolling the ship did not save zoom")
    page.mouse.move(bounds["x"] + bounds["width"] * .45, bounds["y"] + bounds["height"] * .5)
    page.mouse.down()
    page.mouse.move(bounds["x"] + bounds["width"] * .60, bounds["y"] + bounds["height"] * .42, steps=4)
    page.mouse.up()
    after_drag = page.evaluate("localStorage.getItem('preflight.instrumentHullView.v1')")
    if after_zoom == after_drag:
        raise RuntimeError(f"{label}: dragging the ship did not save its view")


def assert_hangar_chooser(page: Page, label: str) -> dict[str, object]:
    add_button = page.get_by_role("button", name="Add a display ship", exact=True)
    add_box = add_button.bounding_box()
    remove_button = page.get_by_role("button", name=re.compile(r"^Remove .+ from Home ships$"))
    remove_box = remove_button.bounding_box()
    if add_box is None or min(add_box["width"], add_box["height"]) < 44:
        raise RuntimeError(f"{label}: Add ship has an undersized target: {add_box}")
    if remove_box is None or min(remove_box["width"], remove_box["height"]) < 44:
        raise RuntimeError(f"{label}: Remove ship has an undersized target: {remove_box}")
    add_button.click()
    page.get_by_role("listbox", name="Ships to add", exact=True).wait_for()
    measurement = page.evaluate(
        """() => {
          const workspace = document.querySelector('.page-viewport');
          const input = document.querySelector('.hangar-hull-combobox__input');
          const list = document.querySelector('.hangar-hull-combobox__list');
          if (!(workspace instanceof HTMLElement) || !(input instanceof HTMLElement) || !(list instanceof HTMLElement)) return null;
          const rect = (element) => {
            const value = element.getBoundingClientRect();
            return { x: value.x, y: value.y, width: value.width, height: value.height,
              right: value.right, bottom: value.bottom };
          };
          return {
            workspace: rect(workspace),
            input: rect(input),
            list: rect(list),
            optionHeights: [...list.querySelectorAll('[role="option"]')]
              .map((option) => option.getBoundingClientRect().height),
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: ship chooser is missing")
    workspace = measurement["workspace"]
    popup = measurement["list"]
    if popup["x"] < workspace["x"] - 1 or popup["right"] > workspace["right"] + 1:
        raise RuntimeError(f"{label}: ship chooser leaves the workspace horizontally: {measurement}")
    if popup["y"] < workspace["y"] - 1 or popup["bottom"] > workspace["bottom"] + 1:
        raise RuntimeError(f"{label}: ship chooser leaves the workspace vertically: {measurement}")
    if popup["height"] < 44 or any(height < 44 for height in measurement["optionHeights"]):
        raise RuntimeError(f"{label}: ship chooser has undersized results: {measurement}")
    page.keyboard.press("Escape")
    page.get_by_role("listbox", name="Ships to add", exact=True).wait_for(state="hidden")
    return measurement


def assert_keyboard_controls(page: Page, label: str) -> None:
    launch = page.get_by_role("button", name="Launch Starsector", exact=True)
    launch.focus()
    focus = launch.evaluate(
        """element => ({
          active: document.activeElement === element,
          outlineWidth: getComputedStyle(element).outlineWidth,
          outlineStyle: getComputedStyle(element).outlineStyle,
        })"""
    )
    if not focus["active"] or focus["outlineStyle"] == "none" or focus["outlineWidth"] == "0px":
        raise RuntimeError(f"{label}: Launch has no visible keyboard focus: {focus}")

    page.get_by_role("button", name="Hangar", exact=True).click()
    chooser = page.get_by_role("combobox", name="Display ship")
    chooser.focus()
    chooser.fill("Para")
    results = page.get_by_role("listbox", name="Display ships")
    results.wait_for()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Enter")
    if not chooser.input_value().strip() or results.is_visible():
        raise RuntimeError(f"{label}: the ship chooser did not complete a keyboard selection")

    slider = page.get_by_role("slider", name="Ship zoom")
    before = slider.input_value()
    slider.focus()
    page.keyboard.press("ArrowRight")
    if slider.input_value() == before:
        raise RuntimeError(f"{label}: Hangar slider ignored the keyboard")


def install_long_content(page: Page) -> None:
    page.evaluate(
        """() => {
          const replacements = {
            '.home-launch-profile strong': 'Outer Rim Expedition With Every Faction And Several Extremely Long Mod Names Enabled',
            '.home-launch-path__short': '/Volumes/Starsector Installations/Extremely Long Named Collection/Starsector.app',
            '.home-ship-name': 'INTERNATIONALIZED EXPERIMENTAL COMMAND CARRIER',
          };
          for (const [selector, value] of Object.entries(replacements)) {
            const element = document.querySelector(selector);
            if (element) element.textContent = value;
          }
        }"""
    )


def assert_page_width(page: Page, label: str) -> dict[str, object]:
    measurement = page.evaluate(
        """() => {
          const workspace = document.querySelector('#page-workspace');
          if (!(workspace instanceof HTMLElement)) return null;
          const workspaceRect = workspace.getBoundingClientRect();
          const controls = [...workspace.querySelectorAll('button, input, select, textarea, summary, [role="slider"]')]
            .flatMap((element, index) => {
              if (!(element instanceof HTMLElement)) return [];
              const style = getComputedStyle(element);
              const rect = element.getBoundingClientRect();
              if (style.display === 'none' || style.visibility === 'hidden' || rect.width === 0 || rect.height === 0) return [];
              if (rect.right <= workspaceRect.left || rect.left >= workspaceRect.right) return [];
              return [{
                index,
                element,
                tag: element.tagName,
                type: element instanceof HTMLInputElement ? element.type : '',
                text: element.getAttribute('aria-label') || element.textContent?.trim().slice(0, 80),
                left: rect.left,
                top: rect.top,
                right: rect.right,
                bottom: rect.bottom,
                width: rect.width,
                height: rect.height,
              }];
            });
          const clippedControls = controls
            .filter((control) => control.left < workspaceRect.left - 1 || control.right > workspaceRect.right + 1)
            .map(({ element, ...control }) => control);
          const individuallyTargeted = controls.filter((control) =>
            control.tag === 'BUTTON'
            || control.tag === 'SUMMARY'
            || control.tag === 'SELECT'
            || control.tag === 'TEXTAREA'
            || (control.tag === 'INPUT' && !['checkbox', 'radio', 'hidden'].includes(control.type))
          );
          const undersizedControls = individuallyTargeted
            .filter((control) => control.height < 43 || (['BUTTON', 'SUMMARY'].includes(control.tag) && control.width < 43))
            .map(({ element, ...control }) => control);
          const overlappingControls = [];
          for (let at = 0; at < individuallyTargeted.length; at += 1) {
            const one = individuallyTargeted[at];
            for (let next = at + 1; next < individuallyTargeted.length; next += 1) {
              const two = individuallyTargeted[next];
              if (one.element.contains(two.element) || two.element.contains(one.element)) continue;
              const overlapWidth = Math.min(one.right, two.right) - Math.max(one.left, two.left);
              const overlapHeight = Math.min(one.bottom, two.bottom) - Math.max(one.top, two.top);
              if (overlapWidth > 2 && overlapHeight > 2) {
                overlappingControls.push({
                  one: { tag: one.tag, text: one.text, left: one.left, top: one.top, right: one.right, bottom: one.bottom },
                  two: { tag: two.tag, text: two.text, left: two.left, top: two.top, right: two.right, bottom: two.bottom },
                });
              }
            }
          }
          return {
            viewportWidth: innerWidth,
            documentScrollWidth: document.documentElement.scrollWidth,
            workspaceClientWidth: workspace.clientWidth,
            workspaceScrollWidth: workspace.scrollWidth,
            workspace: { left: workspaceRect.left, right: workspaceRect.right },
            clippedControls,
            undersizedControls,
            overlappingControls,
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: page workspace is missing")
    if measurement["documentScrollWidth"] > measurement["viewportWidth"] + 1:
        raise RuntimeError(f"{label}: document has horizontal overflow: {measurement}")
    if measurement["workspaceScrollWidth"] > measurement["workspaceClientWidth"] + 1:
        raise RuntimeError(f"{label}: workspace has horizontal overflow: {measurement}")
    if measurement["clippedControls"]:
        raise RuntimeError(f"{label}: interactive controls are clipped horizontally: {measurement}")
    if measurement["undersizedControls"]:
        raise RuntimeError(f"{label}: interactive controls have undersized targets: {measurement}")
    if measurement["overlappingControls"]:
        raise RuntimeError(f"{label}: interactive controls overlap: {measurement}")
    return measurement


def assert_help_actions(page: Page, label: str) -> dict[str, object]:
    measurement = page.evaluate(
        """() => {
          const group = document.querySelector('.support-card__main .report-actions');
          if (!(group instanceof HTMLElement)) return null;
          const groupRect = group.getBoundingClientRect();
          const actions = [...group.querySelectorAll('button')].flatMap((button) => {
            if (!(button instanceof HTMLElement)) return [];
            const rect = button.getBoundingClientRect();
            return [{ x: rect.x, y: rect.y, width: rect.width, height: rect.height,
              right: rect.right, bottom: rect.bottom }];
          });
          return {
            display: getComputedStyle(group).display,
            group: { x: groupRect.x, y: groupRect.y, width: groupRect.width,
              right: groupRect.right, bottom: groupRect.bottom },
            actions,
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: report actions are missing")
    if measurement["display"] != "grid" or len(measurement["actions"]) < 3:
        raise RuntimeError(f"{label}: report actions are not one deliberate group: {measurement}")
    group = measurement["group"]
    for action in measurement["actions"]:
        if action["x"] < group["x"] - 1 or action["right"] > group["right"] + 1:
            raise RuntimeError(f"{label}: report action leaves its group: {measurement}")
        if action["height"] < 44:
            raise RuntimeError(f"{label}: report action has an undersized target: {measurement}")
    return measurement


def assert_benchmark_composition(page: Page, label: str) -> dict[str, object]:
    measurement = page.evaluate(
        """() => {
          const card = document.querySelector('.benchmark-card');
          const intro = card?.querySelector(':scope > div:first-child');
          const actions = card?.querySelector('.benchmark-card__actions');
          if (!(card instanceof HTMLElement) || !(intro instanceof HTMLElement) || !(actions instanceof HTMLElement)) return null;
          const rect = (element) => {
            const value = element.getBoundingClientRect();
            return { x: value.x, y: value.y, width: value.width, height: value.height, right: value.right };
          };
          return {
            direction: getComputedStyle(card).flexDirection,
            card: rect(card),
            intro: rect(intro),
            actions: rect(actions),
          };
        }"""
    )
    if measurement is None:
        raise RuntimeError(f"{label}: benchmark composition is missing")
    if measurement["direction"] == "row":
        if measurement["intro"]["width"] < 220:
            raise RuntimeError(f"{label}: benchmark explanation collapsed into a narrow column: {measurement}")
        if measurement["actions"]["width"] > 310:
            raise RuntimeError(f"{label}: benchmark recovery actions consumed the card: {measurement}")
    return measurement


def exercise_recovery_state(
    browser: Browser,
    base_url: str,
    width: int,
    height: int,
    scenario: str,
    output_dir: Path | None,
) -> tuple[dict[str, object], list[str]]:
    label = f"{width}x{height} {scenario}"
    context, page, errors = open_preview(
        browser,
        base_url,
        width,
        height,
        scenario,
        "#page-workspace",
    )
    try:
        if scenario == "benchmark-unavailable":
            page.get_by_role("button", name="Speed", exact=True).click()
            page.get_by_role("button", name="Measure speed", exact=True).click()
            page.get_by_role("button", name="Run benchmark", exact=True).click()
            page.get_by_text("Benchmark files are missing.", exact=False).wait_for()
            result = assert_page_width(page, label)
            result["composition"] = assert_benchmark_composition(page, label)
        elif scenario == "mod-problems":
            page.get_by_role("button", name="Mods", exact=True).click()
            page.get_by_role("button", name="Check setup", exact=True).click()
            page.get_by_text("1 problem found", exact=True).wait_for()
            result = assert_page_width(page, label)
        elif scenario == "profile-mismatch":
            page.get_by_role("button", name="Mods", exact=True).click()
            page.get_by_text("Missing: graphicslib", exact=True).wait_for()
            result = assert_page_width(page, label)
        elif scenario == "update-error":
            page.get_by_role("button", name="Settings", exact=True).click()
            page.get_by_role("button", name="Check for updates", exact=True).click()
            page.get_by_role("alert").wait_for()
            if page.get_by_text("Update status hasn’t been checked yet.", exact=True).count() != 0:
                raise RuntimeError(f"{label}: stale update status remained beside the failure")
            result = assert_page_width(page, label)
        elif scenario == "report-error":
            page.get_by_role("button", name="Help", exact=True).click()
            page.get_by_role("button", name="Make a support file", exact=True).click()
            page.get_by_role("button", name="Review and send", exact=True).click()
            page.get_by_role("button", name="Send file", exact=True).click()
            page.get_by_text("It wasn’t sent", exact=True).wait_for()
            page.get_by_role("button", name="Try sending again", exact=True).wait_for()
            result = assert_page_width(page, label)
        else:
            raise RuntimeError(f"unknown recovery scenario: {scenario}")
        capture(page, output_dir, f"state-{scenario}-{width}x{height}.png")
        return result, errors
    finally:
        context.close()


def exercise_frame_pacing_state(
    browser: Browser,
    base_url: str,
    width: int,
    height: int,
    output_dir: Path | None,
) -> tuple[dict[str, object], list[str]]:
    scenario = "frame-pacing"
    label = f"{width}x{height} {scenario}"
    context, page, errors = open_preview(
        browser,
        base_url,
        width,
        height,
        scenario,
        "#page-workspace",
    )
    try:
        page.get_by_role("button", name="Speed", exact=True).click()
        card = page.get_by_role("region", name="Latest frame pacing", exact=True)
        card.wait_for()
        coverage = card.get_by_text(re.compile(r"frames · .+ active"))
        if coverage.count() != 3:
            raise RuntimeError(
                f"{label}: expected active duration beside all three pacing distributions"
            )
        result = assert_page_width(page, label)
        capture(page, output_dir, f"state-{scenario}-{width}x{height}.png")
        return result, errors
    finally:
        context.close()


def settle_hangar_for_capture(page: Page) -> None:
    """Put every captured Hangar at the same saved view and animation phase."""
    page.evaluate("localStorage.removeItem('preflight.instrumentHullView.v1')")
    page.emulate_media(reduced_motion="reduce")
    page.reload(wait_until="networkidle")
    page.get_by_role("button", name="Hangar", exact=True).click()
    page.get_by_role("slider", name="Ship zoom").wait_for()
    page.evaluate("document.fonts.ready")


def capture(page: Page, output_dir: Path | None, name: str) -> None:
    if output_dir is None:
        return
    output_dir.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(output_dir / name), animations="disabled", full_page=False)


def render_contact_sheet(browser: Browser, output_dir: Path) -> None:
    cards: list[str] = []
    for width, height in VIEWPORTS:
        label = f"{width}x{height}"
        for state in ("full", "options", "idle", "compact", "minimal", "first-run", "low-disk"):
            filename = f"home-{state}-{label}.png"
            cards.append(
                f'<figure><figcaption>{html.escape(label)} · {state}</figcaption>'
                f'<img src="{html.escape(filename)}" alt="Home {html.escape(state)} at {html.escape(label)}"></figure>'
            )
        filename = f"hangar-{label}.png"
        cards.append(
            f'<figure><figcaption>{html.escape(label)} · Hangar</figcaption>'
            f'<img src="{html.escape(filename)}" alt="Hangar at {html.escape(label)}"></figure>'
        )
        filename = f"hangar-chooser-{label}.png"
        cards.append(
            f'<figure><figcaption>{html.escape(label)} · ship chooser</figcaption>'
            f'<img src="{html.escape(filename)}" alt="Ship chooser at {html.escape(label)}"></figure>'
        )
        if width in PAGE_SWEEP_WIDTHS:
            for state in ("long-content", "light"):
                filename = f"home-{state}-{label}.png"
                cards.append(
                    f'<figure><figcaption>{html.escape(label)} · {html.escape(state)}</figcaption>'
                    f'<img src="{html.escape(filename)}" alt="Home {html.escape(state)} at {html.escape(label)}"></figure>'
                )
            for page_name in (*PRIMARY_PAGES, "Benchmark"):
                slug = page_name.lower()
                filename = f"page-{slug}-{label}.png"
                cards.append(
                    f'<figure><figcaption>{html.escape(label)} · {html.escape(page_name)}</figcaption>'
                    f'<img src="{html.escape(filename)}" alt="{html.escape(page_name)} at {html.escape(label)}"></figure>'
                )
            if width <= 720:
                filename = f"page-mods-menu-{label}.png"
                cards.append(
                    f'<figure><figcaption>{html.escape(label)} · profile menu</figcaption>'
                    f'<img src="{html.escape(filename)}" alt="Open profile menu at {html.escape(label)}"></figure>'
                )
            for scenario in (
                *HOME_RECOVERY_SCENARIOS,
                *PAGE_RECOVERY_SCENARIOS,
                *PAGE_DATA_SCENARIOS,
            ):
                filename = f"state-{scenario}-{label}.png"
                cards.append(
                    f'<figure><figcaption>{html.escape(label)} · {html.escape(scenario)}</figcaption>'
                    f'<img src="{html.escape(filename)}" alt="{html.escape(scenario)} at {html.escape(label)}"></figure>'
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
<body><h1>Home and Hangar viewport matrix</h1><main>{''.join(cards)}</main></body></html>"""
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
                    context, page, errors = open_preview(browser, base_url, width, height)
                    try:
                        geometry[f"{label}-full"] = assert_home_geometry(page, f"{label} full")
                        assert_focus_stable(page, f"{label} full")
                        assert_home_toggle_stability(page, f"{label} display toggles")
                        if (width, height) in ((1040, 700), (1280, 720)):
                            assert_ship_moves(page, f"{label} full")
                        capture(page, args.output_dir, f"home-full-{label}.png")

                        page.get_by_role("button", name="Options", exact=True).click()
                        geometry[f"{label}-options"] = assert_quick_settings_geometry(
                            page,
                            f"{label} Options",
                        )
                        capture(page, args.output_dir, f"home-options-{label}.png")
                        page.get_by_role("button", name="Hide options", exact=True).click()

                        page.evaluate("document.activeElement instanceof HTMLElement && document.activeElement.blur()")
                        page.mouse.move(width - 2, height - 2)
                        page.wait_for_timeout(2400)
                        page.wait_for_function(
                            """Number.parseFloat(getComputedStyle(document.querySelector('.home-playtime')).opacity) < 0.01
                            && Number.parseFloat(getComputedStyle(document.querySelector('.topbar__actions')).opacity) < 0.01""",
                        )
                        idle = page.evaluate(
                            """() => ({
                              idle: document.querySelector('.launch-console--layout-settled')?.classList.contains('home-hud--idle'),
                              hudOpacity: getComputedStyle(document.querySelector('.home-playtime')).opacity,
                              appearanceOpacity: getComputedStyle(document.querySelector('.topbar__actions')).opacity,
                              launchOpacity: getComputedStyle(document.querySelector('.button--launch')).opacity,
                            })"""
                        )
                        if (
                            idle["idle"] is not True
                            or float(idle["hudOpacity"]) >= 0.01
                            or float(idle["appearanceOpacity"]) >= 0.01
                            or idle["launchOpacity"] != "1"
                        ):
                            raise RuntimeError(f"{label} idle: HUD did not recede while launch remained visible: {idle}")
                        capture(page, args.output_dir, f"home-idle-{label}.png")

                        page.mouse.move(width / 2, height / 2)
                        page.wait_for_function(
                            "document.querySelector('.launch-console--layout-settled')?.classList.contains('home-hud--visible')",
                        )
                        page.get_by_role("button", name="Ship", exact=True).click()
                        page.locator(".home-flight-instrument").wait_for(state="hidden")
                        if not page.locator(".home-playtime").is_visible():
                            raise RuntimeError(f"{label} compact: hiding the ship also hid playtime")
                        geometry[f"{label}-compact"] = assert_home_geometry(page, f"{label} compact")
                        assert_focus_stable(page, f"{label} compact")
                        capture(page, args.output_dir, f"home-compact-{label}.png")

                        page.get_by_role("button", name="Playtime", exact=True).click()
                        page.locator(".home-playtime").wait_for(state="hidden")
                        geometry[f"{label}-minimal"] = assert_home_geometry(page, f"{label} minimal")
                        assert_focus_stable(page, f"{label} minimal")
                        capture(page, args.output_dir, f"home-minimal-{label}.png")

                        page.get_by_role("button", name="Hangar", exact=True).click()
                        page.get_by_role("slider", name="Ship zoom").wait_for()
                        geometry[f"{label}-hangar"] = assert_hangar_geometry(page, f"{label} Hangar")
                        if (width, height) in ((720, 560), (1040, 700)):
                            assert_hangar_interaction(page, f"{label} Hangar")
                        geometry[f"{label}-hangar-chooser"] = assert_hangar_chooser(page, f"{label} Hangar")
                        page.get_by_role("button", name="Add a display ship", exact=True).click()
                        page.get_by_role("listbox", name="Ships to add", exact=True).wait_for()
                        capture(page, args.output_dir, f"hangar-chooser-{label}.png")
                        page.keyboard.press("Escape")
                        settle_hangar_for_capture(page)
                        geometry[f"{label}-hangar-capture"] = assert_hangar_geometry(page, f"{label} Hangar capture")
                        capture(page, args.output_dir, f"hangar-{label}.png")

                        for page_name in PRIMARY_PAGES:
                            page.get_by_role("button", name=page_name, exact=True).click()
                            page.get_by_role("heading", name=page_name, exact=True).wait_for()
                            geometry[f"{label}-page-{page_name.lower()}"] = assert_page_width(
                                page,
                                f"{label} {page_name}",
                            )
                            if page_name == "Help":
                                geometry[f"{label}-page-help-actions"] = assert_help_actions(
                                    page,
                                    f"{label} Help",
                                )
                            if page_name == "Mods" and width <= 720:
                                manage = page.locator("summary[aria-label^='Manage ']").first
                                manage.click()
                                geometry[f"{label}-page-mods-menu"] = assert_page_width(
                                    page,
                                    f"{label} open profile menu",
                                )
                                capture(page, args.output_dir, f"page-mods-menu-{label}.png")
                                manage.click()
                            if width in PAGE_SWEEP_WIDTHS:
                                capture(page, args.output_dir, f"page-{page_name.lower()}-{label}.png")

                        page.get_by_role("button", name="Speed", exact=True).click()
                        page.get_by_role("button", name="Measure speed").click()
                        page.get_by_role("heading", name="Benchmark", exact=True).wait_for()
                        geometry[f"{label}-page-benchmark"] = assert_page_width(
                            page,
                            f"{label} Benchmark",
                        )
                        if width in PAGE_SWEEP_WIDTHS:
                            capture(page, args.output_dir, f"page-benchmark-{label}.png")

                        if errors:
                            raise RuntimeError(f"{label}: browser errors: {' | '.join(errors)}")
                    finally:
                        context.close()

                    if width in PAGE_SWEEP_WIDTHS:
                        context, page, errors = open_preview(browser, base_url, width, height)
                        try:
                            install_long_content(page)
                            geometry[f"{label}-long-content"] = assert_home_geometry(page, f"{label} long content")
                            geometry[f"{label}-long-content"]["page"] = assert_page_width(page, f"{label} long content")
                            capture(page, args.output_dir, f"home-long-content-{label}.png")
                            if errors:
                                raise RuntimeError(f"{label} long content: browser errors: {' | '.join(errors)}")
                        finally:
                            context.close()

                        context, page, errors = open_preview(browser, base_url, width, height)
                        try:
                            page.get_by_role("button", name="Use light theme").click()
                            page.wait_for_function("document.documentElement.dataset.theme === 'light'")
                            geometry[f"{label}-light"] = assert_home_geometry(page, f"{label} light")
                            page.mouse.move(2, height - 2)
                            capture(page, args.output_dir, f"home-light-{label}.png")
                            if errors:
                                raise RuntimeError(f"{label} light: browser errors: {' | '.join(errors)}")
                        finally:
                            context.close()

                    if (width, height) == (1040, 700):
                        context, page, errors = open_preview(browser, base_url, width, height)
                        try:
                            assert_keyboard_controls(page, f"{label} keyboard")
                            if errors:
                                raise RuntimeError(f"{label} keyboard: browser errors: {' | '.join(errors)}")
                        finally:
                            context.close()

                    for scenario in ("first-run", "low-disk"):
                        context, page, errors = open_preview(browser, base_url, width, height, scenario)
                        try:
                            geometry[f"{label}-{scenario}"] = assert_home_geometry(
                                page,
                                f"{label} {scenario}",
                            )
                            assert_focus_stable(page, f"{label} {scenario}")
                            capture(page, args.output_dir, f"home-{scenario}-{label}.png")
                            if errors:
                                raise RuntimeError(
                                    f"{label} {scenario}: browser errors: {' | '.join(errors)}"
                                )
                        finally:
                            context.close()

                    if width in PAGE_SWEEP_WIDTHS:
                        for scenario in HOME_RECOVERY_SCENARIOS:
                            context, page, errors = open_preview(
                                browser,
                                base_url,
                                width,
                                height,
                                scenario,
                                "#page-workspace",
                            )
                            try:
                                page.get_by_role("heading", name={
                                    "setup": "Setup",
                                    "cache-repair": "Fast launch",
                                    "cache-unsafe": "Fast launch",
                                    "run-failure": "Needs attention",
                                    "running": "Running",
                                }[scenario], exact=True).wait_for()
                                key = f"{label}-{scenario}"
                                geometry[key] = assert_page_width(page, f"{label} {scenario}")
                                if scenario == "run-failure" and page.locator(".home-ship-picker").count() != 0:
                                    raise RuntimeError(
                                        f"{label} {scenario}: recovery left the ship picker on screen"
                                    )
                                if scenario in ("cache-repair", "cache-unsafe", "running"):
                                    geometry[key]["home"] = assert_home_geometry(page, f"{label} {scenario}")
                                capture(page, args.output_dir, f"state-{scenario}-{label}.png")
                                if errors:
                                    raise RuntimeError(
                                        f"{label} {scenario}: browser errors: {' | '.join(errors)}"
                                    )
                            finally:
                                context.close()

                        for scenario in PAGE_RECOVERY_SCENARIOS:
                            result, errors = exercise_recovery_state(
                                browser,
                                base_url,
                                width,
                                height,
                                scenario,
                                args.output_dir,
                            )
                            geometry[f"{label}-{scenario}"] = result
                            if errors:
                                raise RuntimeError(
                                    f"{label} {scenario}: browser errors: {' | '.join(errors)}"
                                )

                        result, errors = exercise_frame_pacing_state(
                            browser,
                            base_url,
                            width,
                            height,
                            args.output_dir,
                        )
                        geometry[f"{label}-frame-pacing"] = result
                        if errors:
                            raise RuntimeError(
                                f"{label} frame-pacing: browser errors: {' | '.join(errors)}"
                            )
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
