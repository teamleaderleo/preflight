import { describe, expect, it, vi } from "vitest";
import {
  COPY_SETUP_MAX_MODS,
  createCopySetupSummary,
  createCopySetupText,
  writeCopySetupToClipboard,
  type CopySetupObservations,
} from "./copySetup";

function observations(overrides: Partial<CopySetupObservations> = {}): CopySetupObservations {
  return {
    preflightVersion: "0.1.0",
    platform: "mac",
    starsectorReady: true,
    profileFingerprint: "abc123",
    mods: [],
    launchSettings: {
      resolution: "2560x1440",
      fullscreen: true,
      sound: true,
      maxHeapMiB: 8192,
      initialHeapMiB: 8192,
    },
    optimizationPreset: "recommended",
    preparation: { status: "ready", profileFingerprint: "abc123" },
    latestLaunch: null,
    ...overrides,
  };
}

describe("Copy setup public summary", () => {
  it("handles a proven vanilla or empty profile", () => {
    const text = createCopySetupText(observations({ profileFingerprint: null, mods: [] }));

    expect(text).toContain("Enabled mods: 0\n");
    expect(text).toContain("Optimization preset: recommended\n");
    expect(text).toContain("Preparation: ready\n");
  });

  it("keeps unavailable mod evidence distinct from a proven empty profile", () => {
    const source = observations({
      profileFingerprint: "cache-fingerprint",
      mods: null,
      launchSettings: null,
    });
    const summary = createCopySetupSummary(source);
    const text = createCopySetupText(source);

    expect(summary.mods).toBeNull();
    expect(summary.omittedModCount).toBeNull();
    expect(text).toContain("Profile fingerprint: cache-fingerprint\n");
    expect(text).toContain("Enabled mods: unavailable\n");
    expect(text).not.toContain("Enabled mods: 0\n");
  });

  it("bounds and deterministically orders a very large mod profile", () => {
    const mods = Array.from({ length: 500 }, (_, index) => ({ id: `mod-${String(499 - index).padStart(3, "0")}` }));
    const summary = createCopySetupSummary(observations({ mods }));
    const text = createCopySetupText(observations({ mods }));

    expect(summary.mods).toHaveLength(COPY_SETUP_MAX_MODS);
    expect(summary.omittedModCount).toBe(500 - COPY_SETUP_MAX_MODS);
    expect(summary.mods?.[0]?.id).toBe("mod-000");
    expect(summary.mods?.at(-1)?.id).toBe(`mod-${String(COPY_SETUP_MAX_MODS - 1).padStart(3, "0")}`);
    expect(text).toContain(`${500 - COPY_SETUP_MAX_MODS} more mod IDs omitted`);
  });

  it("drops malformed metadata while preserving bounded Unicode names", () => {
    const text = createCopySetupText(observations({
      mods: [
        { id: "unicode.mod", displayName: "星海の航路 — Étoile", declaredVersion: "版本 2.0" },
        { id: "" },
        { id: null, displayName: "bad" } as unknown as { id: string },
      ],
    }));

    expect(text).toContain("unicode.mod — 星海の航路 — Étoile [版本 2.0]");
    expect(text).not.toContain("bad");
    expect(text).toContain("Enabled mods: 1");
  });

  it("keeps latest success and failure observations as observations", () => {
    const success = createCopySetupText(observations({
      latestLaunch: { outcome: "SUCCESS", startupMillis: 12_345, exitCode: 0 },
    }));
    const failure = createCopySetupText(observations({
      latestLaunch: { outcome: "FAILED", startupMillis: 4_321, exitCode: 1 },
    }));

    expect(success).toContain("Latest launch outcome: SUCCESS\nLatest launch startup: 12345 ms\nLatest launch exit code: 0");
    expect(failure).toContain("Latest launch outcome: FAILED\nLatest launch startup: 4321 ms\nLatest launch exit code: 1");
  });

  it("produces byte-stable text for identical observations", () => {
    const source = observations({
      mods: [{ id: "zeta" }, { id: "alpha" }, { id: "beta" }],
      latestLaunch: { outcome: "SUCCESS", startupMillis: 9_876, exitCode: 0 },
    });

    const first = new TextEncoder().encode(createCopySetupText(source));
    const second = new TextEncoder().encode(createCopySetupText(source));
    expect(second).toEqual(first);
  });

  it("keeps partial unavailable observations byte-stable", () => {
    const source = observations({
      profileFingerprint: "cache-fingerprint",
      mods: null,
      launchSettings: null,
      preparation: { status: "repair-needed", profileFingerprint: "cache-fingerprint" },
    });

    const first = new TextEncoder().encode(createCopySetupText(source));
    const second = new TextEncoder().encode(createCopySetupText(source));
    expect(second).toEqual(first);
  });

  it("propagates clipboard failure without changing the generated text", async () => {
    const source = observations({ mods: [{ id: "graphicslib" }] });
    const writeText = vi.fn<(text: string) => Promise<void>>().mockRejectedValue(new Error("clipboard denied"));

    await expect(writeCopySetupToClipboard(source, writeText)).rejects.toThrow("clipboard denied");
    expect(writeText).toHaveBeenCalledOnce();
    expect(writeText).toHaveBeenCalledWith(createCopySetupText(source));
  });

  it("uses an explicit public-field allowlist against an aggressive privacy fixture", () => {
    const privateMarkers = [
      "/Users/alice/Library/Application Support/Starsector",
      "C:\\Users\\alice\\Documents\\Starsector",
      "alice",
      "HOME=/Users/alice SECRET_TOKEN=top-secret",
      "java -Xmx8g -Dtoken=top-secret -jar /Users/alice/preflight.jar",
      "java.lang.IllegalStateException: secret exception prose",
      "campaign-save-name",
      "https://support.example.invalid/delete?token=top-secret",
      "delete-authorization-top-secret",
      "raw mod description with private prose",
    ];
    const source = {
      ...observations({
        mods: [{
          id: "safe.mod",
          displayName: "Safe Mod",
          declaredVersion: "1.2.3",
          path: privateMarkers[0],
          description: privateMarkers[9],
          rawContent: privateMarkers[5],
        } as unknown as { id: string; displayName: string; declaredVersion: string }],
      }),
      installRoot: privateMarkers[0],
      homeDirectory: privateMarkers[1],
      username: privateMarkers[2],
      environment: privateMarkers[3],
      commandLine: privateMarkers[4],
      stackTrace: privateMarkers[5],
      saveName: privateMarkers[6],
      shareUrl: privateMarkers[7],
      deletion: { token: privateMarkers[8], url: privateMarkers[7] },
      arbitraryDescription: privateMarkers[9],
    } as CopySetupObservations & Record<string, unknown>;

    const text = createCopySetupText(source);

    expect(text).toContain("safe.mod — Safe Mod [1.2.3]");
    for (const marker of privateMarkers) expect(text).not.toContain(marker);
    expect(text).not.toContain("SECRET_TOKEN");
    expect(text).not.toContain("delete?token=");
  });

  it("omits mod display and version labels that contain paths, URIs, secrets, or control characters", () => {
    const text = createCopySetupText(observations({
      mods: [
        {
          id: "safe-mod",
          displayName: "Safe Mod — Ironclad",
          declaredVersion: "2.1.0-RC1",
        },
        {
          id: "unix-path-mod",
          displayName: "Broken mod at /Users/alice/private-pack",
          declaredVersion: "1.0.0",
        },
        {
          id: "windows-path-mod",
          displayName: "Mod at C:\\Games\\Starsector\\mods",
          declaredVersion: "v1.2",
        },
        {
          id: "unc-path-mod",
          displayName: "Mod from \\\\server\\share\\mod",
          declaredVersion: "0.9.1",
        },
        {
          id: "uri-mod",
          displayName: "Official Mod",
          declaredVersion: "see https://example.invalid/?token=secret",
        },
        {
          id: "credential-mod",
          displayName: "Secret API token=supersecret",
          declaredVersion: "Bearer secret-token-12345",
        },
        {
          id: "control-injection-mod",
          displayName: "Injected\n- fake-mod — Injected Name [9.9.9]",
          declaredVersion: "1.0\r\nInjected Line",
        },
        {
          id: "path/mod/id",
          displayName: "Illegal ID",
          declaredVersion: "1.0.0",
        },
      ],
    }));

    // Safe mod retains all labels
    expect(text).toContain("- safe-mod — Safe Mod — Ironclad [2.1.0-RC1]");

    // Mod with unsafe display name keeps safe version
    expect(text).toContain("- unix-path-mod [1.0.0]");
    expect(text).not.toContain("/Users/alice");

    // Mod with Windows path in display name keeps safe version
    expect(text).toContain("- windows-path-mod [v1.2]");
    expect(text).not.toContain("C:\\Games");

    // Mod with UNC path in display name keeps safe version
    expect(text).toContain("- unc-path-mod [0.9.1]");
    expect(text).not.toContain("\\\\server\\share");

    // Mod with URL in version keeps safe display name
    expect(text).toContain("- uri-mod — Official Mod");
    expect(text).not.toContain("https://");
    expect(text).not.toContain("token=secret");

    // Mod with credentials in both display and version drops both labels
    expect(text).toContain("- credential-mod\n");
    expect(text).not.toContain("supersecret");
    expect(text).not.toContain("Bearer");

    // Mod with control/newline injection drops both labels without injecting lines
    expect(text).toContain("- control-injection-mod\n");
    expect(text).not.toContain("fake-mod");
    expect(text).not.toContain("Injected Line");

    // Mod with invalid ID containing path slashes is dropped completely
    expect(text).not.toContain("path/mod/id");
    expect(text).not.toContain("Illegal ID");
  });
});

