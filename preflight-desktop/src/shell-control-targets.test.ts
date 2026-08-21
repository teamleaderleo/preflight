import mainSource from "./main.tsx?raw";
import shellTargetStyles from "./shell-control-targets.css?raw";
import { expect, test } from "vitest";

test("topbar appearance choices keep 44px individual targets", () => {
  expect(mainSource).toContain('import "./shell-control-targets.css";');
  expect(shellTargetStyles).toMatch(
    /\.palette-switch__button,\s*\.theme-switch__button\s*\{[^}]*width:\s*44px;[^}]*min-height:\s*44px;/s,
  );
});
