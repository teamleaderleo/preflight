import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "../desktopStorage";
import type { useInstrumentHull } from "../useInstrumentHull";
import type { WireframeHull, WireframeTuning } from "../types";
import { HangarPage } from "./HangarPage";

vi.mock("./FlightInstrument", () => ({
  FlightInstrument: ({ hull }: { hull: WireframeHull }) => <div data-testid="instrument">{hull.name}</div>,
}));

const featured: WireframeHull = {
  id: "odyssey",
  name: "Odyssey",
  hullSize: "CAPITAL_SHIP",
  style: "HIGH_TECH",
  featured: true,
  bounds: [{ x: 1, y: 0 }, { x: -1, y: 1 }, { x: -1, y: -1 }],
  engines: [],
  mounts: [],
};

const installed: WireframeHull = {
  ...featured,
  id: "modded-hull",
  name: "Modded Hull",
  hullSize: "CRUISER",
  featured: false,
};

const tuning: WireframeTuning = {
  outerSmooth: 0.2,
  outerDetail: 0.02,
  innerSmooth: 0.35,
  innerDetail: 0.03,
  height: 1,
};

function state(overrides: Partial<ReturnType<typeof useInstrumentHull>> = {}) {
  return {
    catalog: { format: "preflight-wireframe-hulls-v1" as const, hulls: [featured, installed], skipped: 0 },
    catalogLoaded: true,
    hulls: [featured, installed],
    selected: featured,
    selectedId: featured.id,
    tuning,
    customized: true,
    choose: vi.fn(),
    customize: vi.fn(),
    resetCustomization: vi.fn(),
    ...overrides,
  } as ReturnType<typeof useInstrumentHull>;
}

beforeEach(() => {
  window.localStorage.clear();
});

test("installed hulls stay in the searchable picker while featured selection remains compact", () => {
  const instrumentHull = state();
  render(<HangarPage instrumentHull={instrumentHull} />);

  expect(screen.getByText("Display hull")).toBeInTheDocument();
  expect(screen.getByText("2 installed")).toBeInTheDocument();
  const select = screen.getByRole("combobox", { name: "Display ship" });
  expect(select).toHaveTextContent("Odyssey");
  expect(select).not.toHaveTextContent("Modded Hull");
  expect(screen.getByRole("button", { name: /Modded Hull/ })).toBeInTheDocument();
  expect(screen.getByText("capital")).toBeInTheDocument();
  expect(screen.queryByText("capital ship")).not.toBeInTheDocument();
});

test("motion direction and reset read as one locally coherent control group", () => {
  render(<HangarPage instrumentHull={state()} />);

  const controls = screen.getByRole("group", { name: "Display motion and appearance" });
  expect(controls).toHaveAttribute("data-motion", "rotate");
  expect(controls).toHaveAttribute("data-direction", "clockwise");
  expect(within(controls).getByText("Rotating · CW")).toBeInTheDocument();

  const counterClockwise = within(controls).getByRole("button", { name: "Use counter-clockwise" });
  expect(counterClockwise).toHaveAttribute("title", "Rotate counter-clockwise");
  fireEvent.click(counterClockwise);
  expect(controls).toHaveAttribute("data-direction", "counter-clockwise");
  expect(within(controls).getByText("Rotating · CCW")).toBeInTheDocument();
  expect(within(controls).getByRole("button", { name: "Use clockwise" })).toBeEnabled();

  const pause = within(controls).getByRole("button", { name: "Pause rotation" });
  expect(pause).toHaveAttribute("title", "Pause decorative hull rotation");
  expect(within(controls).queryByText("Pause rotation")).not.toBeInTheDocument();
  fireEvent.click(pause);
  expect(controls).toHaveAttribute("data-motion", "still");
  expect(within(controls).getByText("Paused · CCW")).toBeInTheDocument();

  const resume = within(controls).getByRole("button", { name: "Resume rotation" });
  expect(resume).toHaveAttribute("title", "Resume decorative hull rotation");
  const pausedDirection = within(controls).getByRole("button", { name: "Use clockwise" });
  expect(pausedDirection).toBeEnabled();
  expect(pausedDirection).toHaveAttribute("title", "Use clockwise when rotation resumes");

  fireEvent.click(pausedDirection);
  expect(controls).toHaveAttribute("data-direction", "clockwise");
  expect(within(controls).getByText("Paused · CW")).toBeInTheDocument();
  expect(within(controls).getByRole("button", { name: "Use counter-clockwise" })).toBeEnabled();
  expect(JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY) ?? "null"))
    .toEqual({ motion: "still", direction: "clockwise" });

  const reset = within(controls).getByRole("button", { name: "Reset appearance" });
  expect(reset).toHaveAttribute("title", "Reset appearance");
  expect(reset).toHaveTextContent("Reset");
});

test("appearance dials expose palette-progress state and keep interior tuning independently editable", () => {
  const instrumentHull = state();
  render(<HangarPage instrumentHull={instrumentHull} />);

  const appearance = screen.getByRole("group", { name: "Wireframe appearance" });
  const detail = within(appearance).getByRole("slider", { name: "Detail" });
  const detailDial = detail.closest(".hangar-dial") as HTMLElement;
  expect(parseFloat(detailDial.style.getPropertyValue("--hangar-range"))).toBeCloseTo(33.333, 2);

  fireEvent.change(detail, { target: { value: "0.04" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ outerDetail: 0.04, innerDetail: 0.04 });

  fireEvent.change(within(appearance).getByRole("slider", { name: "Interior detail" }), { target: { value: "0.05" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ innerDetail: 0.05 });

  fireEvent.change(within(appearance).getByRole("slider", { name: "Smooth" }), { target: { value: "0.4" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ outerSmooth: 0.4, innerSmooth: 0.4 });

  fireEvent.change(within(appearance).getByRole("slider", { name: "Interior smooth" }), { target: { value: "0.6" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ innerSmooth: 0.6 });
});
