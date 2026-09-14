import { expect, test } from "vitest";
import { adapterHealthNeedsAttention } from "./adapterHealthText";
import type { AdapterHealthSummary } from "./types";

const partial: AdapterHealthSummary = {
  format: "starsector-preflight-adapter-health-v1", status: "PARTIAL",
  accelerationsActive: true, originalCodeRetained: true, reviewRecommended: true,
  transformationsApplied: 51, registryTargets: 113, containedFailures: 0,
  evidenceKinds: ["VERSION_OR_TARGET_MISMATCH", "TRANSFORMATION_DECLINED", "SHADOWED_TARGET", "CACHE_MISS"],
  suggestedActions: [],
};

test("old compatibility reports stay quiet regardless of their review flag", () => {
  expect(adapterHealthNeedsAttention(partial)).toBe(false);
});

test.each(["CACHE_REJECTION", "WRAPPER_FAILURE", "RUNTIME_INTEGRITY_FAILURE"])(
  "%s remains actionable", kind => {
    expect(adapterHealthNeedsAttention({ ...partial, evidenceKinds: [kind] })).toBe(true);
  },
);

test("failed setup, full fallback and contained failures remain actionable", () => {
  expect(adapterHealthNeedsAttention({ ...partial, status: "ERROR" })).toBe(true);
  expect(adapterHealthNeedsAttention({ ...partial, status: "SAFE_FALLBACK" })).toBe(true);
  expect(adapterHealthNeedsAttention({ ...partial, containedFailures: 1 })).toBe(true);
});
