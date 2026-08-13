import assert from "node:assert/strict";
import test from "node:test";
import {
  assertPackagedScenarioValidation,
  assertPackagedSkippedEvidence,
  validatePackagedDesktopSmokeProbe,
} from "./exercise-native-install.mjs";

test("packaged scenario validation requires the shipped campaign contract", () => {
  assert.doesNotThrow(() => assertPackagedScenarioValidation({
    protocol: 1,
    valid: true,
    scenario: {
      format: "starsector-preflight-smoke-v1",
      name: "campaign-roam",
      steps: [{ id: "menu" }],
    },
  }));
  assert.doesNotThrow(() => assertPackagedScenarioValidation({
    protocol: 1,
    valid: true,
    scenario: {
      format: "starsector-preflight-smoke-v1",
      name: "campaign-roam-measurement-only",
      steps: [{ id: "menu" }],
    },
  }, "campaign-roam-measurement-only"));
  assert.throws(
    () => assertPackagedScenarioValidation({ protocol: 1, valid: true, scenario: { steps: [] } }),
    /scenario validation is malformed/,
  );
});

test("packaged evidence requires a sealed skip instead of a synthetic pass", () => {
  const evidence = {
    format: "starsector-preflight-smoke-evidence-v1",
    scenario: "campaign-roam",
    status: "skipped",
    driver: { id: "package-contract" },
    steps: [],
    artifacts: [],
    diagnostics: ["No game was launched."],
  };
  assert.doesNotThrow(() => assertPackagedSkippedEvidence({ protocol: 1, evidence }, evidence));
  assert.throws(
    () => assertPackagedSkippedEvidence(
      { protocol: 1, evidence: { ...evidence, status: "passed" } },
      { ...evidence, status: "passed" },
    ),
    /evidence is malformed/,
  );
});

test("packaged startup benchmark is permission-free on the native host", () => {
  const ready = {
    protocol: 1,
    probe: {
      ready: true,
      driver: {
        id: "runtime-semantic-state",
        capabilities: ["process-control", "semantic-state"],
      },
      diagnostics: [],
    },
  };
  assert.deepEqual(validatePackagedDesktopSmokeProbe(ready, true), {
    ready: true,
    driver: "runtime-semantic-state",
    diagnostic: null,
    nativeHost: true,
  });
  assert.throws(
    () => validatePackagedDesktopSmokeProbe({
      ...ready,
      probe: {
        ...ready.probe,
        driver: { id: "macos-preflight-native-pid", capabilities: ["window-control"] },
      },
    }, true),
    /requires desktop automation/,
  );
});
