import { fireEvent, render, screen } from "@testing-library/react";
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

  const select = screen.getByRole("combobox", { name: "Display ship" });
  expect(select).toHaveTextContent("Odyssey");
  expect(select).not.toHaveTextContent("Modded Hull");
  expect(screen.getByRole("button", { name: /Modded Hull/ })).toBeInTheDocument();
});

test("motion toggles immediately and persists without an Apply step", () => {
  render(<HangarPage instrumentHull={state()} />);

  const rotate = screen.getByRole("button", { name: "Motion: Rotate" });
  expect(rotate).toHaveAttribute("title", "Stop decorative hull rotation");
  expect(rotate).not.toHaveAttribute("aria-pressed");
  fireEvent.click(rotate);

  const still = screen.getByRole("button", { name: "Motion: Still" });
  expect(still).toHaveAttribute("title", "Resume decorative hull rotation");
  expect(window.localStorage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY)).toBe("still");
});

test("interior tuning remains independently editable after the shared appearance dials", () => {
  const instrumentHull = state();
  render(<HangarPage instrumentHull={instrumentHull} />);

  fireEvent.change(screen.getByRole("slider", { name: "Detail" }), { target: { value: "0.04" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ outerDetail: 0.04, innerDetail: 0.04 });

  fireEvent.change(screen.getByRole("slider", { name: "Interior detail" }), { target: { value: "0.05" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ innerDetail: 0.05 });

  fireEvent.change(screen.getByRole("slider", { name: "Smooth" }), { target: { value: "0.4" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ outerSmooth: 0.4, innerSmooth: 0.4 });

  fireEvent.change(screen.getByRole("slider", { name: "Interior smooth" }), { target: { value: "0.6" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ innerSmooth: 0.6 });
});
