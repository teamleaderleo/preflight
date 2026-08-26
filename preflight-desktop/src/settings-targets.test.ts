import settingsTargetStyles from "./settings-targets.css?raw";
import { expect, test } from "vitest";

test("Settings native controls keep full desktop interaction targets", () => {
  expect(settingsTargetStyles).toMatch(
    /\.preferences-card \.preference-field select\s*\{[^}]*min-height:\s*44px;/s,
  );
  expect(settingsTargetStyles).toMatch(
    /\.settings-overview \.settings-toggle\s*\{[^}]*min-height:\s*44px;/s,
  );
});

test("Settings target sizing does not inflate typography or card spacing", () => {
  expect(settingsTargetStyles).not.toMatch(/font-size|padding|margin|transform|scale/);
});
