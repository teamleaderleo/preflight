import layoutStyles from "./layout-hierarchy.css?raw";
import { expect, test } from "vitest";

test("accepted Hangar dock footprint is retained while instrumentation compacts", () => {
  expect(layoutStyles).toMatch(/\.hangar-dock--catalog\s*\{[^}]*min-height:\s*224px;/s);
});
