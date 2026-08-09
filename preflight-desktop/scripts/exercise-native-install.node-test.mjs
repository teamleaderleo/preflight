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

test("packaged macOS automation probe stays on the native host boundary", () => {
  const unavailable = {
    protocol: 1,
    probe: {
      ready: false,
      driver: null,
      diagnostics: [
        "macOS Accessibility permission isn't enabled for the automation executable: the Preflight application",
      ],
    },
  };
  assert.deepEqual(validatePackagedDesktopSmokeProbe(unavailable, true), {
    ready: false,
    driver: null,
    diagnostic: unavailable.probe.diagnostics[0],
    nativeHost: true,
  });
  assert.throws(
    () => validatePackagedDesktopSmokeProbe({
      ...unavailable,
      probe: {
        ...unavailable.probe,
        diagnostics: ["permission needed by /Resources/engine/runtime/bin/java"],
      },
    }, true),
    /attributes permission incorrectly/,
  );
  assert.throws(
    () => validatePackagedDesktopSmokeProbe({
      protocol: 1,
      probe: {
        ready: true,
        driver: { id: "macos-system-events-pid", capabilities: [] },
        diagnostics: [],
      },
    }, true),
    /bypassed its native bridge/,
  );
});
