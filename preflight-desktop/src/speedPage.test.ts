import speedPageStyles from "./speedPage.css?raw";
import { expect, test } from "vitest";

test("settled Speed trims only narrow-layout inter-card spacing", () => {
  expect(speedPageStyles).toMatch(/@media \(max-width: 1000px\)[\s\S]*?\.page-viewport--speed \.prepare-page--settled\s*\{[^}]*gap:\s*12px;/);
  expect(speedPageStyles).not.toMatch(/font-size|padding|transform|scale/);
});
