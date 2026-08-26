import hangarTargetStyles from "./hangar-targets.css?raw";
import { expect, test } from "vitest";

test("Hangar tuning spends existing dock slack on full-size slider targets", () => {
  expect(hangarTargetStyles).toMatch(
    /\.hangar-control-group \.hangar-dial\s*\{[^}]*grid-template-rows:\s*44px;[^}]*min-height:\s*44px;/s,
  );
  expect(hangarTargetStyles).toMatch(
    /\.hangar-control-group \.hangar-dial > input\s*\{[^}]*min-height:\s*44px;/s,
  );
});

test("slider target sizing does not take ownership of accepted ship or dock geometry", () => {
  expect(hangarTargetStyles).not.toContain(".hangar-stage");
  expect(hangarTargetStyles).not.toMatch(/\.hangar-dock--catalog\s*\{/);
  expect(hangarTargetStyles).not.toMatch(/padding|transform|scale/);
});
