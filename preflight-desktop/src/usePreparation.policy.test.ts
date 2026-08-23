import { describe, expect, test } from "vitest";
import type { CacheHealth } from "./types";
import { canGraduateToCompact, preparationModeMatchesStorage } from "./usePreparation";

const full: CacheHealth = {
  format: "starsector-preflight-cache-health-v1",
  status: "ready",
  profileFingerprint: "a".repeat(64),
  preparedTextures: true,
  textureStorage: "balanced",
  textureScope: "full",
  compactAvailable: true,
  issues: [],
  repairBytes: 0,
  repairFiles: 0,
};

describe("automatic Compact graduation", () => {
  test("graduates only a learned-ready Balanced bootstrap", () => {
    expect(canGraduateToCompact(full)).toBe(true);
    expect(canGraduateToCompact({ ...full, compactAvailable: false })).toBe(false);
    expect(canGraduateToCompact({ ...full, textureScope: "learned" })).toBe(false);
    expect(canGraduateToCompact({ ...full, textureStorage: "fastest" })).toBe(false);
    expect(canGraduateToCompact({ ...full, preparedTextures: false })).toBe(false);
    expect(canGraduateToCompact({ ...full, status: "repair-needed" })).toBe(false);
    expect(canGraduateToCompact({
      ...full,
      textureStorage: undefined,
      textureScope: undefined,
    })).toBe(true);
  });

  test("matches each prepared mode instead of treating every texture pack as equivalent", () => {
    expect(preparationModeMatchesStorage(full, "balanced")).toBe(true);
    expect(preparationModeMatchesStorage(full, "compact")).toBe(false);
    expect(preparationModeMatchesStorage({ ...full, textureScope: "learned" }, "compact")).toBe(true);
    expect(preparationModeMatchesStorage({ ...full, textureStorage: "fastest" }, "fastest")).toBe(true);
    expect(preparationModeMatchesStorage({ ...full, textureStorage: "fastest" }, "balanced")).toBe(false);
    expect(preparationModeMatchesStorage({ ...full, preparedTextures: false }, "minimal")).toBe(true);
    expect(preparationModeMatchesStorage({
      ...full,
      textureStorage: undefined,
      textureScope: undefined,
    }, "balanced")).toBe(true);
  });
});
