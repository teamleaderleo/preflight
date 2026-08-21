import { expect, test } from "vitest";
import homePageSource from "./HomePage.tsx?raw";

test("captured run recovery removes the settled launch identity", () => {
  expect(homePageSource).toContain(
    'isReady && !visibleRunFailure && (status === "ready" || status === "error") && snapshot?.selected',
  );
  expect(homePageSource).not.toContain(
    'isReady && (status === "ready" || status === "error") && snapshot?.selected',
  );
});
