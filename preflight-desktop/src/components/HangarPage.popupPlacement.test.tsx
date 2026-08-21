import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import type { useInstrumentHull } from "../useInstrumentHull";
import type { WireframeHull, WireframeTuning } from "../types";
import { HangarPage } from "./HangarPage";

vi.mock("./FlightInstrument", () => ({
  FlightInstrument: ({ hull }: { hull: WireframeHull }) => <div>{hull.name}</div>,
}));

const hulls: WireframeHull[] = [
  "Odyssey",
  "Onslaught",
  "Paragon",
  "Conquest",
  "Astral",
  "Legion",
].map((name, index) => ({
  id: name.toLowerCase(),
  name,
  hullSize: "CAPITAL_SHIP",
  style: index % 2 === 0 ? "HIGH_TECH" : "LOW_TECH",
  featured: true,
  bounds: [{ x: 1, y: 0 }, { x: -1, y: 1 }, { x: -1, y: -1 }],
  engines: [],
  mounts: [],
}));

const tuning: WireframeTuning = {
  outerSmooth: 0.2,
  outerDetail: 0.02,
  innerSmooth: 0.35,
  innerDetail: 0.03,
  height: 1,
};

function state() {
  return {
    catalog: { format: "preflight-wireframe-hulls-v1" as const, hulls, skipped: 0 },
    catalogLoaded: true,
    hulls,
    selected: hulls[0],
    selectedId: hulls[0].id,
    tuning,
    customized: false,
    choose: vi.fn(),
    customize: vi.fn(),
    resetCustomization: vi.fn(),
  } as ReturnType<typeof useInstrumentHull>;
}

function rect(top: number, bottom: number, left = 0, right = 720): DOMRect {
  return {
    x: left,
    y: top,
    width: right - left,
    height: bottom - top,
    top,
    right,
    bottom,
    left,
    toJSON: () => ({}),
  } as DOMRect;
}

const originalInnerHeight = window.innerHeight;

beforeEach(() => {
  window.localStorage.clear();
  Object.defineProperty(window, "innerHeight", { configurable: true, value: 560 });
});

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(window, "innerHeight", { configurable: true, value: originalInnerHeight });
});

test("bottom-scrolled minimum Hangar opens the chooser into visible space below", () => {
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function () {
    if (this.classList.contains("page-viewport")) return rect(117, 560);
    if (this.getAttribute("role") === "combobox") return rect(247, 291, 120, 500);
    if (this.classList.contains("hangar-hull-combobox__list")) return rect(50, 246, 120, 500);
    return rect(0, 0);
  });

  render(<div className="page-viewport"><HangarPage instrumentHull={state()} /></div>);
  const chooser = screen.getByRole("combobox", { name: "Display ship" });
  const workspace = chooser.closest(".page-viewport") as HTMLElement;
  workspace.scrollTop = 83;

  fireEvent.focus(chooser);

  const combobox = chooser.closest(".hangar-hull-combobox") as HTMLElement;
  const list = screen.getByRole("listbox", { name: "Display ships" });
  expect(combobox).toHaveAttribute("data-placement", "down");
  expect(list.style.top).toBe("calc(100% + 9px)");
  expect(list.style.bottom).toBe("auto");
  expect(list.style.maxHeight).toBe("196px");

  fireEvent.keyDown(chooser, { key: "ArrowDown" });
  expect(workspace.scrollTop).toBe(83);
});

test("chooser keeps the established upward placement when the list fits above", () => {
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function () {
    if (this.classList.contains("page-viewport")) return rect(117, 560);
    if (this.getAttribute("role") === "combobox") return rect(400, 444, 120, 500);
    if (this.classList.contains("hangar-hull-combobox__list")) return rect(195, 391, 120, 500);
    return rect(0, 0);
  });

  render(<div className="page-viewport"><HangarPage instrumentHull={state()} /></div>);
  const chooser = screen.getByRole("combobox", { name: "Display ship" });
  fireEvent.focus(chooser);

  const combobox = chooser.closest(".hangar-hull-combobox") as HTMLElement;
  const list = screen.getByRole("listbox", { name: "Display ships" });
  expect(combobox).toHaveAttribute("data-placement", "up");
  expect(list.style.top).toBe("auto");
  expect(list.style.bottom).toBe("calc(100% + 9px)");
  expect(list.style.maxHeight).toBe("196px");
});
