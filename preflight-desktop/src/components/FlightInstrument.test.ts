import { render } from "@testing-library/react";
import { createElement } from "react";
import { beforeEach, expect, test } from "vitest";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "../desktopStorage";
import { INSTRUMENT_APPEARANCE_ATTRIBUTES } from "../flightInstrumentAppearance";
import { FlightInstrument } from "./FlightInstrument";

beforeEach(() => {
  window.localStorage.clear();
});

test("the canvas redraw watches both appearance axes", () => {
  expect(INSTRUMENT_APPEARANCE_ATTRIBUTES).toEqual(["data-theme", "data-palette"]);
});

test("the Hangar stage keeps the ship and drops the small targeting reticle", () => {
  const { container } = render(createElement(FlightInstrument, { variant: "stage" }));
  expect(container.querySelector(".flight-instrument--stage canvas")).not.toBeNull();
  expect(container.querySelector(".flight-instrument--stage svg")).toBeNull();
});

test("a saved Still preference reaches the shared renderer", () => {
  window.localStorage.setItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY, "still");
  const { container } = render(createElement(FlightInstrument, { variant: "stage" }));

  expect(container.querySelector(".flight-instrument--stage")).toHaveAttribute("data-motion", "still");
});
