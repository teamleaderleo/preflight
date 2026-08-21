import chooserSource from "./components/HangarPage.tsx?raw";
import { expect, test } from "vitest";

test("Hangar active-row navigation scrolls only the chooser list", () => {
  expect(chooserSource).not.toContain("scrollIntoView");
  expect(chooserSource).toContain("function keepActiveHullVisible");
  expect(chooserSource).toMatch(/visibleBottom = visibleTop \+ list\.clientHeight/);
  expect(chooserSource).toMatch(/list\.scrollTop = optionBottom - list\.clientHeight/);
});
