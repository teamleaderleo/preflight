import hangarStyles from "./hangar-instrumentation.css?raw";
import { expect, test } from "vitest";

test("Hangar dock follows its controls instead of reserving a stale fixed footprint", () => {
  expect(hangarStyles).toMatch(/\.hangar-dock--catalog\s*\{[^}]*min-height:\s*0;/s);
  expect(hangarStyles).not.toMatch(/\.hangar-dock--catalog\s*\{[^}]*min-height:\s*224px;/s);
});
