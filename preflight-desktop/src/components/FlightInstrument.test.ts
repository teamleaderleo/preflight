import { fireEvent, render } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "../desktopStorage";
import { INSTRUMENT_APPEARANCE_ATTRIBUTES } from "../flightInstrumentAppearance";
import { FlightInstrument } from "./FlightInstrument";

const matchMediaDescriptor = Object.getOwnPropertyDescriptor(window, "matchMedia");
const devicePixelRatioDescriptor = Object.getOwnPropertyDescriptor(window, "devicePixelRatio");

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  if (matchMediaDescriptor) {
    Object.defineProperty(window, "matchMedia", matchMediaDescriptor);
  } else {
    Reflect.deleteProperty(window, "matchMedia");
  }
  if (devicePixelRatioDescriptor) {
    Object.defineProperty(window, "devicePixelRatio", devicePixelRatioDescriptor);
  }
});

function installMatchMediaMock() {
  const queries: Array<{
    media: string;
    addEventListener: ReturnType<typeof vi.fn>;
    removeEventListener: ReturnType<typeof vi.fn>;
    fireChange: () => void;
  }> = [];
  const matchMedia = vi.fn((media: string) => {
    let changeListener: (() => void) | null = null;
    const query = {
      matches: false,
      media,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn((type: string, listener: () => void) => {
        if (type === "change") changeListener = listener;
      }),
      removeEventListener: vi.fn((type: string, listener: () => void) => {
        if (type === "change" && changeListener === listener) changeListener = null;
      }),
      dispatchEvent: vi.fn(() => true),
      fireChange: () => changeListener?.(),
    };
    queries.push(query);
    return query as unknown as MediaQueryList;
  });
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    value: matchMedia,
  });
  return { matchMedia, queries };
}

function installCanvasMock() {
  const context = {
    arc: vi.fn(),
    beginPath: vi.fn(),
    clearRect: vi.fn(),
    closePath: vi.fn(),
    fill: vi.fn(),
    lineTo: vi.fn(),
    moveTo: vi.fn(),
    setTransform: vi.fn(),
    stroke: vi.fn(),
  } as unknown as CanvasRenderingContext2D;
  vi.spyOn(Element.prototype, "clientWidth", "get").mockReturnValue(200);
  vi.spyOn(Element.prototype, "clientHeight", "get").mockReturnValue(100);
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(context);
  return context;
}

test("the canvas redraw watches both appearance axes", () => {
  expect(INSTRUMENT_APPEARANCE_ATTRIBUTES).toEqual(["data-theme", "data-palette"]);
});

test("the Hangar stage keeps the ship and drops the small targeting reticle", () => {
  const { container } = render(createElement(FlightInstrument, { variant: "stage" }));
  expect(container.querySelector(".flight-instrument--stage canvas")).not.toBeNull();
  expect(container.querySelector(".flight-instrument--stage svg")).toBeNull();
});

test("an interactive ship display advertises direct pointer and keyboard control", () => {
  const { getByRole } = render(createElement(FlightInstrument, { variant: "stage", interactive: true }));
  const display = getByRole("group", { name: "Ship display. Drag it or use the arrow keys to change the view." });

  expect(display).toHaveAttribute("tabindex", "0");
  expect(display).toHaveClass("flight-instrument--interactive");
});

test("an interactive ship display responds to dragging and arrow keys", () => {
  window.localStorage.setItem(
    INSTRUMENT_HULL_MOTION_STORAGE_KEY,
    JSON.stringify({ motion: "still", direction: "clockwise" }),
  );
  installMatchMediaMock();
  const context = installCanvasMock();
  vi.stubGlobal("ResizeObserver", class {
    observe = vi.fn();
    unobserve = vi.fn();
    disconnect = vi.fn();
  });

  const { getByRole } = render(createElement(FlightInstrument, { variant: "stage", interactive: true }));
  const display = getByRole("group", { name: "Ship display. Drag it or use the arrow keys to change the view." });
  const strokesBefore = vi.mocked(context.stroke).mock.calls.length;

  fireEvent.pointerDown(display, { button: 0, clientX: 40, clientY: 40, pointerId: 1 });
  expect(display).toHaveAttribute("data-dragging", "true");
  fireEvent.pointerMove(display, { clientX: 80, clientY: 20, pointerId: 1 });
  expect(vi.mocked(context.stroke).mock.calls.length).toBeGreaterThan(strokesBefore);
  fireEvent.pointerUp(display, { clientX: 80, clientY: 20, pointerId: 1 });
  expect(display).not.toHaveAttribute("data-dragging");

  const strokesAfterDrag = vi.mocked(context.stroke).mock.calls.length;
  fireEvent.keyDown(display, { key: "ArrowRight" });
  expect(vi.mocked(context.stroke).mock.calls.length).toBeGreaterThan(strokesAfterDrag);
});

test("saved motion and direction preferences reach the shared renderer", () => {
  window.localStorage.setItem(
    INSTRUMENT_HULL_MOTION_STORAGE_KEY,
    JSON.stringify({ motion: "still", direction: "counter-clockwise" }),
  );
  const { container } = render(createElement(FlightInstrument, { variant: "stage" }));

  expect(container.querySelector(".flight-instrument--stage"))
    .toHaveAttribute("data-motion", "still");
  expect(container.querySelector(".flight-instrument--stage"))
    .toHaveAttribute("data-direction", "counter-clockwise");
});

test("DPR changes redraw at the same CSS size and re-arm the resolution listener", () => {
  window.localStorage.setItem(
    INSTRUMENT_HULL_MOTION_STORAGE_KEY,
    JSON.stringify({ motion: "still", direction: "clockwise" }),
  );
  Object.defineProperty(window, "devicePixelRatio", {
    configurable: true,
    value: 1,
  });
  const { queries } = installMatchMediaMock();
  installCanvasMock();
  const observe = vi.fn();
  const disconnect = vi.fn();
  vi.stubGlobal("ResizeObserver", class {
    observe = observe;
    unobserve = vi.fn();
    disconnect = disconnect;
  });

  const rendered = render(createElement(FlightInstrument, { variant: "stage" }));
  const canvas = rendered.container.querySelector("canvas");
  expect(canvas).not.toBeNull();
  if (!canvas) return;
  expect(canvas.clientWidth).toBe(200);
  expect(canvas.width).toBe(200);

  let resolutionQueries = queries.filter((query) => query.media.startsWith("(resolution:"));
  expect(resolutionQueries).toHaveLength(1);
  expect(resolutionQueries[0].media).toBe("(resolution: 1dppx)");
  expect(resolutionQueries[0].addEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function), { once: true });

  Object.defineProperty(window, "devicePixelRatio", {
    configurable: true,
    value: 2,
  });
  resolutionQueries[0].fireChange();

  expect(canvas.clientWidth).toBe(200);
  expect(canvas.width).toBe(400);
  resolutionQueries = queries.filter((query) => query.media.startsWith("(resolution:"));
  expect(resolutionQueries).toHaveLength(2);
  expect(resolutionQueries[0].removeEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function));
  expect(resolutionQueries[1].media).toBe("(resolution: 2dppx)");
  expect(resolutionQueries[1].addEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function), { once: true });

  rendered.rerender(createElement(FlightInstrument, { variant: "badge" }));
  resolutionQueries = queries.filter((query) => query.media.startsWith("(resolution:"));
  expect(resolutionQueries).toHaveLength(3);
  expect(resolutionQueries[1].removeEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function));
  expect(resolutionQueries[2].media).toBe("(resolution: 2dppx)");

  rendered.unmount();
  expect(resolutionQueries[2].removeEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function));
  expect(disconnect).toHaveBeenCalledTimes(2);
});
