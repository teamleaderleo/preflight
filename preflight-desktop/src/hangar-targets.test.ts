import hangarTargetStyles from "./hangar-targets.css?raw";
import { expect, test } from "vitest";

test("Hangar tuning spends existing dock slack on full-size slider targets", () => {
  expect(hangarTargetStyles).toMatch(
    /\.hangar-dock--catalog \.hangar-dial\s*\{[^}]*grid-template-rows:\s*12px 44px;/s,
  );
  expect(hangarTargetStyles).toMatch(
    /\.hangar-dock--catalog \.hangar-dial > input\s*\{[^}]*min-height:\s*44px;/s,
  );
});

test("slider target sizing does not take ownership of accepted ship or dock geometry", () => {
  expect(hangarTargetStyles).not.toContain(".hangar-stage");
  expect(hangarTargetStyles).not.toMatch(/\.hangar-dock--catalog\s*\{/);
  expect(hangarTargetStyles).not.toMatch(/padding|transform|scale/);
});
