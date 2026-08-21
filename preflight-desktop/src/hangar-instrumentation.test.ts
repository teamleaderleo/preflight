import hangarStyles from "./hangar-instrumentation.css?raw";
import { expect, test } from "vitest";

test("Hangar active values and motion stay on the current palette", () => {
  expect(hangarStyles).toContain("var(--hangar-range, 0%)");
  expect(hangarStyles).toContain("var(--accent) 0 var(--hangar-range, 0%)");
  expect(hangarStyles).toMatch(/\.hangar-motion-status__state\s*\{[^}]*color:\s*var\(--accent-strong\);/s);
  expect(hangarStyles).toMatch(/\.hangar-hull-select\s*\{[^}]*box-shadow:\s*inset 2px 0 var\(--accent\);/s);
  expect(hangarStyles).not.toMatch(/#[0-9a-f]{3,8}/i);
});

test("Hangar controls have authored hover focus pressed and selected states", () => {
  expect(hangarStyles).toContain(".hangar-hull-select:active");
  expect(hangarStyles).toContain(".hangar-motion-controls .icon-button:hover");
  expect(hangarStyles).toContain(".hangar-motion-controls .icon-button:active");
  expect(hangarStyles).toContain(".hangar-dial input:active::-webkit-slider-thumb");
  expect(hangarStyles).toContain(".hull-picker__hull--selected");
  expect(hangarStyles).toMatch(/\.hangar-hull-select:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(hangarStyles).toMatch(/\.hangar-motion-controls \.icon-button:focus-visible,[\s\S]*?\.hangar-reset-action:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(hangarStyles).toMatch(/\.hangar-dial:has\(input:focus-visible\)\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(hangarStyles).toMatch(/\.hangar-dial input:focus-visible::\-webkit-slider-thumb\s*\{[^}]*box-shadow:\s*0 0 0 3px var\(--accent\);/s);
  expect(hangarStyles).toMatch(/\.hull-picker__search input:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(hangarStyles).toMatch(/\.hull-picker__hull:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(hangarStyles).toMatch(/\.hangar-reset-action\s*\{[^}]*border-color:\s*var\(--line\);[^}]*background:\s*transparent;/s);
});

test("Hangar tuning compacts into one five-channel instrument bank at the shipped desktop widths", () => {
  expect(hangarStyles).toMatch(/\.hangar-dock--catalog \.hangar-dials\s*\{[^}]*grid-template-columns:\s*repeat\(5, minmax\(0, 1fr\)\);/s);
  expect(hangarStyles).toContain("@container (max-width: 600px)");
  expect(hangarStyles).toMatch(/@container \(max-width: 600px\)[\s\S]*grid-template-columns:\s*repeat\(3, minmax\(0, 1fr\)\);/s);
  expect(hangarStyles).not.toMatch(/\.hangar-stage(?:\s|\{|:)/);
});
