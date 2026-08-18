import { describe, expect, it } from "vitest";
import { lastRunForCurrentProfile, launchSetupApplicability } from "./lastRunApplicability";
import type { LastRun } from "./types";

const run: LastRun = {
  directory: "/evidence/run",
  modifiedAt: "2026-08-17T00:00:00Z",
  installRoot: "/Games/Starsector",
  profileFingerprint: "a".repeat(64),
  adapterHealth: null,
  startupMillis: 15_250,
};

describe("launchSetupApplicability", () => {
  it("accepts the exact installation and profile identity", () => {
    expect(launchSetupApplicability(run, "/Games/Starsector", "A".repeat(64))).toBe("applies");
  });

  it("treats an installation change as authoritative before the new profile fingerprint loads", () => {
    expect(launchSetupApplicability(run, "/Games/Other", null)).toBe("foreign");
  });

  it("waits for profile identity on the same installation", () => {
    expect(launchSetupApplicability(run, "/Games/Starsector", null)).toBe("unknown");
  });

  it("retires evidence once the authoritative profile fingerprint differs", () => {
    expect(launchSetupApplicability(run, "/Games/Starsector", "b".repeat(64))).toBe("foreign");
  });

  it("keeps legacy or preview identity unknown when a binding is missing", () => {
    expect(launchSetupApplicability({ installRoot: "/Games/Starsector" }, "/Games/Starsector", "a".repeat(64)))
      .toBe("unknown");
    expect(launchSetupApplicability({ profileFingerprint: "a".repeat(64) }, "/Games/Starsector", "a".repeat(64)))
      .toBe("unknown");
  });
});

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
