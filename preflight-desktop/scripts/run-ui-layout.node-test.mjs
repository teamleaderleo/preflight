import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  desktopDirectory,
  environmentDirectory,
  environmentPython,
  isolatedBrowserEnvironment,
  requirementsDigest,
  requirementsPath,
  verifierArguments,
} from "./run-ui-layout.mjs";

test("the local UI runtime is isolated under ignored worktree dependencies", () => {
  assert.equal(
    environmentDirectory,
    join(desktopDirectory, "node_modules", ".preflight-ui-layout"),
  );
  assert.equal(environmentPython("win32"), join(environmentDirectory, "Scripts", "python.exe"));
  assert.equal(environmentPython("darwin"), join(environmentDirectory, "bin", "python"));
  const browserEnvironment = isolatedBrowserEnvironment({
    PLAYWRIGHT_BROWSERS_PATH: "/shared/browser-cache",
    SAMPLE: "kept",
  });
  assert.equal(browserEnvironment.PLAYWRIGHT_BROWSERS_PATH, "0");
  assert.equal(browserEnvironment.SAMPLE, "kept");
});

test("the rendered matrix owns a default output without overriding an explicit one", () => {
  assert.deepEqual(
    verifierArguments([]),
    ["scripts/verify-ui-layout.py", "--output-dir", ".ui-matrix"],
  );
  assert.deepEqual(
    verifierArguments(["--output-dir", "/tmp/review"]),
    ["scripts/verify-ui-layout.py", "--output-dir", "/tmp/review"],
  );
  assert.deepEqual(
    verifierArguments(["--output-dir=/tmp/review"]),
    ["scripts/verify-ui-layout.py", "--output-dir=/tmp/review"],
  );
});

test("local and hosted layout checks share one exact Playwright pin", () => {
  const requirements = readFileSync(requirementsPath, "utf8").trim();
  const workflow = readFileSync(
    join(desktopDirectory, "..", ".github", "workflows", "ui-layout.yml"),
    "utf8",
  );
  assert.equal(requirements, "playwright==1.55.0");
  assert.match(requirementsDigest(), /^[0-9a-f]{64}$/);
  assert.match(workflow, /ui-layout-requirements\.txt/);
  assert.doesNotMatch(workflow, /playwright==[0-9]/);
});
