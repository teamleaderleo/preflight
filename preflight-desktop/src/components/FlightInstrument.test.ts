import { render } from "@testing-library/react";
import { createElement } from "react";
import { expect, test } from "vitest";
import { INSTRUMENT_APPEARANCE_ATTRIBUTES } from "../flightInstrumentAppearance";
import { FlightInstrument } from "./FlightInstrument";

test("the canvas redraw watches both appearance axes", () => {
  expect(INSTRUMENT_APPEARANCE_ATTRIBUTES).toEqual(["data-theme", "data-palette"]);
});

test("the Hangar stage keeps the ship and drops the small targeting reticle", () => {
  const { container } = render(createElement(FlightInstrument, { variant: "stage" }));
  expect(container.querySelector(".flight-instrument--stage canvas")).not.toBeNull();
  expect(container.querySelector(".flight-instrument--stage svg")).toBeNull();
});
