import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import styles from "../styles.css?raw";
import { INFO_TIP_MARGIN, INFO_TIP_MAX_WIDTH, InfoTip } from "./InfoTip";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function box(left: number, top: number, width: number, height: number): DOMRect {
  return {
    x: left,
    y: top,
    left,
    top,
    right: left + width,
    bottom: top + height,
    width,
    height,
    toJSON: () => ({}),
  } as DOMRect;
}

function renderTip() {
  render(
    <div data-testid="scroller" className="page-viewport">
      <InfoTip label="Optimization details">Keeps the original code.</InfoTip>
    </div>,
  );
  return {
    trigger: screen.getByRole("button", { name: "Optimization details" }),
    tooltip: screen.getByRole("tooltip"),
    scroller: screen.getByTestId("scroller"),
  };
}

test("keeps the described tooltip mounted while closed", () => {
  const { trigger, tooltip } = renderTip();

  expect(trigger.getAttribute("aria-describedby")).toBe(tooltip.id);
  expect(tooltip.classList.contains("info-tip__content--open")).toBe(false);
  expect(styles).toMatch(/\.info-tip__content\s*\{[^}]*position:\s*fixed;[^}]*visibility:\s*hidden;/s);
});

test("registers scroll and resize placement work only while open", () => {
  const { trigger } = renderTip();
  const addEventListener = vi.spyOn(window, "addEventListener");
  const removeEventListener = vi.spyOn(window, "removeEventListener");

  addEventListener.mockClear();
  removeEventListener.mockClear();
  fireEvent.focus(trigger);

  expect(addEventListener).toHaveBeenCalledWith("resize", expect.any(Function));
  expect(addEventListener).toHaveBeenCalledWith("scroll", expect.any(Function), true);

  fireEvent.blur(trigger);

  expect(removeEventListener).toHaveBeenCalledWith("resize", expect.any(Function));
  expect(removeEventListener).toHaveBeenCalledWith("scroll", expect.any(Function), true);
});

test("repositions an open tooltip after internal scrolling and window resize", () => {
  const { trigger, tooltip, scroller } = renderTip();
  let anchor = box(700, 250, 20, 20);
  const getBoundingClientRect = vi
    .spyOn(trigger, "getBoundingClientRect")
    .mockImplementation(() => anchor);

  Object.defineProperty(tooltip, "offsetHeight", { configurable: true, value: 60 });
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 800 });
  Object.defineProperty(window, "innerHeight", { configurable: true, value: 300 });

  // jsdom supplies no layout geometry. Explicit rectangles/heights prove the event-to-placement
  // arithmetic; real CSS box measurement and page-viewport scrolling still require a browser.
  fireEvent.focus(trigger);
  expect(tooltip.style.top).toBe("193px");
  expect(tooltip.style.left).toBe("498px");

  anchor = box(40, 100, 20, 20);
  fireEvent.scroll(scroller);
  expect(tooltip.style.top).toBe("117px");
  expect(tooltip.style.left).toBe("48px");

  anchor = box(300, 100, 20, 20);
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 360 });
  fireEvent(window, new Event("resize"));
  expect(tooltip.style.left).toBe("58px");

  fireEvent.blur(trigger);
  const callsAfterClose = getBoundingClientRect.mock.calls.length;
  fireEvent.scroll(scroller);
  fireEvent(window, new Event("resize"));
  expect(getBoundingClientRect).toHaveBeenCalledTimes(callsAfterClose);
});

test("pins CSS width arithmetic to the JS placement constants", () => {
  expect(styles).toContain(
    `width: min(${INFO_TIP_MAX_WIDTH}px, calc(100vw - ${INFO_TIP_MARGIN * 4}px));`,
  );
});
