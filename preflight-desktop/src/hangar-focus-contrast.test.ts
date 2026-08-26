import focusStyles from "./hangar-focus-contrast.css?raw";
import { expect, test } from "vitest";

test("custom Hangar controls keep full-accent focus indication", () => {
  expect(focusStyles).toMatch(/\.hangar-page \.hangar-hull-combobox__input:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(focusStyles).toMatch(/\.hangar-page \.hangar-dial:has\(input:focus-visible\)\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(focusStyles).toMatch(/\.hangar-page \.hangar-dial input:focus-visible::\-webkit-slider-thumb\s*\{[^}]*box-shadow:\s*0 0 0 3px var\(--accent\);/s);
  expect(focusStyles).not.toMatch(/\.hangar-stage(?:\s|\{|:)/);
});
