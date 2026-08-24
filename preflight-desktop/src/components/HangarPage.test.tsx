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
    catalogHulls: [featured, installed],
    hulls: [featured, installed],
    selected: featured,
    selectedId: featured.id,
    tuning,
    customized: true,
    choose: vi.fn(),
    remove: vi.fn(),
    customize: vi.fn(),
    resetCustomization: vi.fn(),
    ...overrides,
  } as ReturnType<typeof useInstrumentHull>;
}

beforeEach(() => {
  window.localStorage.clear();
});

test("the Orbitron ship identity is the typeable hull chooser for the full catalog", () => {
  const instrumentHull = state({ hulls: [featured] });
  render(<HangarPage instrumentHull={instrumentHull} />);

  const chooser = screen.getByRole("combobox", { name: "Display ship" });
  expect(chooser).toHaveValue("Odyssey");
  expect(screen.getByText("capital")).toBeInTheDocument();
  expect(screen.queryByText("capital ship")).not.toBeInTheDocument();

  fireEvent.focus(chooser);
  expect(chooser).toHaveAttribute("aria-expanded", "true");
  const initialList = screen.getByRole("listbox", { name: "Display ships" });
  expect(within(initialList).getByRole("option", { name: "Odyssey" })).toBeInTheDocument();
  expect(within(initialList).getByText("capital")).toBeInTheDocument();
  expect(within(initialList).queryByText(/capital ship/i)).not.toBeInTheDocument();

  fireEvent.change(chooser, { target: { value: "modded" } });
  const list = screen.getByRole("listbox", { name: "Display ships" });
  expect(within(list).getByRole("option", { name: "Modded Hull" })).toBeInTheDocument();
  expect(within(list).getByText("Add")).toBeInTheDocument();

  fireEvent.keyDown(chooser, { key: "Enter" });
  expect(instrumentHull.choose).toHaveBeenCalledWith("modded-hull");
});

test("removes the selected ship from the display roster", () => {
  const instrumentHull = state();
  render(<HangarPage instrumentHull={instrumentHull} />);

  fireEvent.click(screen.getByRole("button", { name: "Remove Odyssey from display ships" }));
  expect(instrumentHull.remove).toHaveBeenCalledWith("odyssey");
});

test("refocus keeps a selected hull active even when it falls outside the ordinary result limit", () => {
  const distantHull: WireframeHull = {
    ...featured,
    id: "onslaught",
    name: "Onslaught",
    style: "LOW_TECH",
    featured: false,
  };
  const fillerHulls = Array.from({ length: 8 }, (_, index): WireframeHull => ({
    ...installed,
    id: `filler-${index}`,
    name: `Filler ${index}`,
  }));
  const hulls = [featured, ...fillerHulls, distantHull];
  const choose = vi.fn();
  const base = {
    hulls,
    catalogHulls: hulls,
    catalog: { format: "preflight-wireframe-hulls-v1" as const, hulls, skipped: 0 },
    choose,
  };
  const { rerender } = render(<HangarPage instrumentHull={state(base)} />);

  const chooser = screen.getByRole("combobox", { name: "Display ship" });
  fireEvent.focus(chooser);
  fireEvent.change(chooser, { target: { value: "Onslaught" } });
  fireEvent.keyDown(chooser, { key: "Enter" });
  expect(choose).toHaveBeenCalledTimes(1);
  expect(choose).toHaveBeenLastCalledWith("onslaught");

  rerender(
    <HangarPage
      instrumentHull={state({
        ...base,
        selected: distantHull,
        selectedId: distantHull.id,
      })}
    />,
  );
  fireEvent.blur(chooser);
  choose.mockClear();
  fireEvent.focus(chooser);

  const list = screen.getByRole("listbox", { name: "Display ships" });
  const selectedOption = within(list).getByRole("option", { name: "Onslaught" });
  expect(selectedOption).toHaveAttribute("aria-selected", "true");
  expect(chooser).toHaveAttribute("aria-activedescendant", selectedOption.id);

  fireEvent.keyDown(chooser, { key: "Enter" });
  expect(chooser).toHaveValue("Onslaught");
  expect(choose).not.toHaveBeenCalled();
});

test("invalid free text restores the current hull on blur", () => {
  const instrumentHull = state();
  render(<HangarPage instrumentHull={instrumentHull} />);

  const chooser = screen.getByRole("combobox", { name: "Display ship" });
  fireEvent.focus(chooser);
  fireEvent.change(chooser, { target: { value: "unknown ship" } });
  fireEvent.blur(chooser);

  expect(chooser).toHaveValue("Odyssey");
  expect(instrumentHull.choose).not.toHaveBeenCalled();
});

test("motion controls keep pause and one unambiguous direction toggle", () => {
  render(<HangarPage instrumentHull={state()} />);

  const controls = screen.getByRole("group", { name: "Display motion and appearance" });
  expect(controls).toHaveAttribute("data-motion", "rotate");
  expect(controls).toHaveAttribute("data-direction", "clockwise");
  const reverse = within(controls).getByRole("button", { name: "Reverse rotation" });
  fireEvent.click(reverse);
  expect(controls).toHaveAttribute("data-direction", "counter-clockwise");

  const pause = within(controls).getByRole("button", { name: "Pause rotation" });
  expect(pause).toHaveAttribute("title", "Pause decorative hull rotation");
  fireEvent.click(pause);
  expect(controls).toHaveAttribute("data-motion", "still");

  const resume = within(controls).getByRole("button", { name: "Resume rotation" });
  expect(resume).toHaveAttribute("title", "Resume decorative hull rotation");
  fireEvent.click(reverse);
  expect(controls).toHaveAttribute("data-direction", "clockwise");
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
  const detail = within(appearance).getByRole("slider", { name: "Outline detail" });
  const detailDial = detail.closest(".hangar-dial") as HTMLElement;
  expect(parseFloat(detailDial.style.getPropertyValue("--hangar-range"))).toBeCloseTo(66.667, 2);

  fireEvent.change(detail, { target: { value: "0.05" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ outerDetail: 0.01 });

  fireEvent.change(within(appearance).getByRole("slider", { name: "Interior detail" }), { target: { value: "0.05" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ innerDetail: 0.01 });

  fireEvent.change(within(appearance).getByRole("slider", { name: "Outline smoothing" }), { target: { value: "0.4" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ outerSmooth: 0.4 });

  fireEvent.change(within(appearance).getByRole("slider", { name: "Interior smoothing" }), { target: { value: "0.6" } });
  expect(instrumentHull.customize).toHaveBeenLastCalledWith({ innerSmooth: 0.6 });
});
