import hangarStyles from "./hangar-instrumentation.css?raw";
import focusStyles from "./hangar-focus-contrast.css?raw";
import { expect, test } from "vitest";

test("Hangar active values stay on the current palette", () => {
  expect(hangarStyles).toContain("var(--hangar-range, 0%)");
  expect(hangarStyles).toContain("var(--accent) 0 var(--hangar-range, 0%)");
  expect(hangarStyles).toMatch(/\.hangar-control-group \.hangar-dial\s*\{[^}]*font-size:\s*10px;/s);
  expect(hangarStyles).toMatch(/\.hangar-control-group \.hangar-dial > output\s*\{[^}]*color:\s*var\(--accent-strong\);/s);
  expect(hangarStyles).not.toMatch(/#[0-9a-f]{3,8}/i);
});

test("the ship identity owns the typeable picker instead of a native select", () => {
  expect(hangarStyles).toMatch(/\.hangar-hull-combobox__input\s*\{[^}]*font-family:\s*var\(--font-display\);/s);
  expect(hangarStyles).toContain(".hangar-hull-combobox__list");
  expect(hangarStyles).toContain(".hangar-hull-combobox__option[aria-selected=\"true\"]");
  expect(hangarStyles).not.toContain(".hangar-hull-select");
  expect(hangarStyles).not.toContain(".hull-picker__search");
});

test("Hangar controls have authored hover focus and pressed states", () => {
  expect(hangarStyles).toContain(".hangar-hull-combobox:hover .hangar-hull-combobox__input");
  expect(hangarStyles).toContain(".hangar-hull-combobox__option:active");
  expect(hangarStyles).toContain(".hangar-stage-action:hover");
  expect(hangarStyles).toContain('.hangar-stage-action[aria-pressed="true"]');
  expect(hangarStyles).toContain(".hangar-dial input:active::-webkit-slider-thumb");
  expect(hangarStyles).toMatch(/\.hangar-reset-action\s*\{[^}]*width:\s*44px;[^}]*min-width:\s*44px;/s);
  expect(focusStyles).toMatch(/\.hangar-page \.hangar-hull-combobox__input:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
  expect(focusStyles).toMatch(/\.hangar-page \.hangar-dial:has\(input:focus-visible\)\s*\{[^}]*outline:\s*2px solid var\(--accent\);/s);
});

test("short Hangar windows bound the chooser list inside the usable workspace", () => {
  expect(hangarStyles).toMatch(
    /@media \(max-height: 600px\)[\s\S]*\.hangar-hull-combobox__list\s*\{[^}]*max-height:\s*min\(196px, 35vh\);/s,
  );
  expect(hangarStyles).toMatch(
    /\.hangar-stage:has\(\.hangar-hull-combobox\[data-open="true"\]\[data-placement="down"\]\)\s*\{[^}]*z-index:\s*2;[^}]*overflow:\s*visible;/s,
  );
});

test("Hangar tuning groups paired controls into readable rows", () => {
  expect(hangarStyles).toMatch(/\.hangar-control-groups\s*\{[^}]*grid-template-columns:\s*repeat\(3, minmax\(0, 1fr\)\);/s);
  expect(hangarStyles).toMatch(/\.hangar-control-group \.hangar-dial\s*\{[^}]*grid-template-columns:\s*58px minmax\(52px, 1fr\) 38px;/s);
  expect(hangarStyles).toContain("@container (max-width: 600px)");
  expect(hangarStyles).toMatch(/@container \(max-width: 600px\)[\s\S]*\.hangar-control-groups\s*\{[^}]*grid-template-columns:\s*repeat\(2, minmax\(0, 1fr\)\);/s);
  expect(hangarStyles).toMatch(/@media \(max-height: 600px\)[\s\S]*\.hangar-stage\s*\{[^}]*min-height:\s*195px;/s);
});
