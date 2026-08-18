import { describe, expect, it } from "vitest";
import { lastRunForCurrentProfile } from "./lastRunApplicability";
import type { LastRun } from "./types";

const run: LastRun = {
  directory: "/evidence/run",
  modifiedAt: "2026-08-17T00:00:00Z",
  installRoot: "/Games/Starsector",
  profileFingerprint: "a".repeat(64),
  adapterHealth: null,
  startupMillis: 15_250,
};

describe("lastRunForCurrentProfile", () => {
  it("returns evidence only for the exact selected installation and profile", () => {
    expect(lastRunForCurrentProfile(run, "/Games/Starsector", "A".repeat(64))).toBe(run);
    expect(lastRunForCurrentProfile(run, "/Games/Other", "a".repeat(64))).toBeNull();
    expect(lastRunForCurrentProfile(run, "/Games/Starsector", "b".repeat(64))).toBeNull();
  });

  it("declines legacy evidence without either binding", () => {
    expect(lastRunForCurrentProfile(
      { ...run, installRoot: undefined },
      "/Games/Starsector",
      "a".repeat(64),
    )).toBeNull();
    expect(lastRunForCurrentProfile(
      { ...run, profileFingerprint: undefined },
      "/Games/Starsector",
      "a".repeat(64),
    )).toBeNull();
  });
});
