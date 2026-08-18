import { describe, expect, test } from "vitest";
import { runFailureApplicability } from "./runFailureApplicability";

const failed = {
  installRoot: "/Applications/Starsector",
  profileFingerprint: "12".repeat(32),
};

describe("run failure applicability", () => {
  test("keeps recovery current for the same installation and profile", () => {
    expect(runFailureApplicability(
      failed,
      "/Applications/Starsector",
      "12".repeat(32),
      false,
    )).toBe("applies");
  });

  test("retires recovery after the installation changes", () => {
    expect(runFailureApplicability(
      failed,
      "/Games/Starsector",
      "12".repeat(32),
      false,
    )).toBe("stale");
  });

  test("retires recovery after the profile fingerprint changes", () => {
    expect(runFailureApplicability(
      failed,
      "/Applications/Starsector",
      "34".repeat(32),
      false,
    )).toBe("stale");
  });

  test("waits through a profile identity refresh", () => {
    expect(runFailureApplicability(
      failed,
      "/Applications/Starsector",
      null,
      true,
    )).toBe("pending");
  });

  test("keeps an unavailable profile identity pending instead of treating read failure as a setup change", () => {
    expect(runFailureApplicability(
      failed,
      "/Applications/Starsector",
      null,
      false,
    )).toBe("pending");
  });
});
