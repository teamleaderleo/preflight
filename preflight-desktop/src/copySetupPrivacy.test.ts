import { describe, expect, it } from "vitest";
import {
  createCopySetupSummary,
  createCopySetupText,
  type CopySetupObservations,
} from "./copySetup";

function observations(mods: CopySetupObservations["mods"]): CopySetupObservations {
  return {
    preflightVersion: "0.1.0",
    platform: "mac",
    starsectorReady: true,
    profileFingerprint: null,
    mods,
    launchSettings: null,
    optimizationPreset: "recommended",
    preparation: null,
    latestLaunch: null,
  };
}

describe("Copy setup public mod scalars", () => {
  it("keeps safe human display names and declared versions", () => {
    const summary = createCopySetupSummary(observations([
      { id: "safe.mod", displayName: "Safe Mod — Étoile", declaredVersion: "版本 2.0 RC1" },
    ]));

    expect(summary.mods).toEqual([
      { id: "safe.mod", displayName: "Safe Mod — Étoile", declaredVersion: "版本 2.0 RC1" },
    ]);
  });

  it.each([
    ["Unix path", "Broken mod at /Users/alice/private-pack"],
    ["Windows drive path", "Broken mod at C:\\Users\\alice\\private-pack"],
    ["UNC path", "Broken mod at \\\\server\\private\\pack"],
    ["HTTP URL", "see https://example.invalid/mod"],
    ["generic URI", "source=file:/Users/alice/private-pack"],
    ["credential field", "token=top-secret"],
    ["environment locator", "HOME=/Users/alice"],
    ["newline control", "private\nsecond line"],
  ])("omits %s material from display and version fields", (_kind, privateValue) => {
    const summary = createCopySetupSummary(observations([
      { id: "safe.mod", displayName: privateValue, declaredVersion: privateValue },
    ]));
    const text = createCopySetupText(observations([
      { id: "safe.mod", displayName: privateValue, declaredVersion: privateValue },
    ]));

    expect(summary.mods).toEqual([{ id: "safe.mod", displayName: null, declaredVersion: null }]);
    expect(text).toContain("- safe.mod\n");
    expect(text).not.toContain(privateValue);
    expect(text).not.toContain("second line");
  });

  it("keeps unsafe labels distinct from empty or unavailable mod evidence", () => {
    const unsafe = createCopySetupSummary(observations([
      { id: "safe.mod", displayName: "/Users/alice/private", declaredVersion: "https://example.invalid/?token=secret" },
    ]));
    const empty = createCopySetupSummary(observations([]));
    const unavailable = createCopySetupSummary(observations(null));

    expect(unsafe.mods).toEqual([{ id: "safe.mod", displayName: null, declaredVersion: null }]);
    expect(empty.mods).toEqual([]);
    expect(unavailable.mods).toBeNull();
    expect(unavailable.omittedModCount).toBeNull();
  });

  it("drops non-canonical mod IDs at the public boundary", () => {
    const summary = createCopySetupSummary(observations([
      { id: "safe.mod" },
      { id: "/Users/alice/private-mod" },
      { id: "unsafe mod id" },
    ]));

    expect(summary.mods).toEqual([{ id: "safe.mod", displayName: null, declaredVersion: null }]);
  });

  it("preserves the byte contract for today's ID-only adapter", () => {
    const text = createCopySetupText(observations([{ id: "zeta" }, { id: "alpha" }]));

    expect(text).toBe(
      "Preflight setup (public)\n"
      + "Summary version: 1\n"
      + "Preflight: 0.1.0\n"
      + "Platform: mac\n"
      + "Starsector detected: yes\n"
      + "Enabled mods: 2\n"
      + "- alpha\n"
      + "- zeta\n"
      + "Optimization preset: recommended\n",
    );
  });
});
