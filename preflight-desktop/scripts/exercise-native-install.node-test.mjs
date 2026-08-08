import assert from "node:assert/strict";
import test from "node:test";
import {
  assertPackagedScenarioValidation,
  assertPackagedSkippedEvidence,
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
