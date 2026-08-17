import { expect, test } from "vitest";
import { INSTRUMENT_APPEARANCE_ATTRIBUTES } from "../flightInstrumentAppearance";

test("the canvas redraw watches both appearance axes", () => {
  expect(INSTRUMENT_APPEARANCE_ATTRIBUTES).toEqual(["data-theme", "data-palette"]);
});
