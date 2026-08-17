import { act, renderHook } from "@testing-library/react";
import {
  getCacheInspection,
  getLaunchSettings,
  getProfiles,
  getSnapshot,
} from "./bridge";
import { readLastInstallRoot } from "./desktopStorage";
import { useCopySetup } from "./useCopySetup";

vi.mock("./bridge", () => ({
  getCacheInspection: vi.fn(),
  getLaunchSettings: vi.fn(),
  getProfiles: vi.fn(),
  getSnapshot: vi.fn(),
}));
vi.mock("./desktopStorage", () => ({
  readLastInstallRoot: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(readLastInstallRoot).mockReturnValue("/Applications/Starsector");
  vi.mocked(getSnapshot).mockResolvedValue({
    engineVersion: "0.1.0",
    platform: "mac",
    ready: true,
    selected: { installRoot: "/Applications/Starsector" },
  } as Awaited<ReturnType<typeof getSnapshot>>);
  vi.mocked(getProfiles).mockResolvedValue({
    enabledMods: ["graphicslib", "nexerelin"],
    profiles: [{
      active: true,
      sameInstall: true,
      profileFingerprint: "profile-abc",
    }],
  } as Awaited<ReturnType<typeof getProfiles>>);
  vi.mocked(getLaunchSettings).mockRejectedValue(new Error("settings unavailable"));
  vi.mocked(getCacheInspection).mockRejectedValue(new Error("cache unavailable"));
});

test("clipboard failure retains the exact generated summary and retry does not rescan", async () => {
  const writeText = vi.fn()
    .mockRejectedValueOnce(new Error("clipboard denied"))
    .mockResolvedValueOnce(undefined);
  installClipboard(writeText);

  const { result } = renderHook(() => useCopySetup("recommended"));

  await act(async () => {
    await result.current.copySetup();
  });

  expect(result.current.state).toBe("error");
  expect(result.current.text).toContain("Preflight setup (public)\n");
  expect(result.current.text).toContain("- graphicslib\n- nexerelin\n");
  const generated = result.current.text;
  expect(writeText).toHaveBeenCalledTimes(1);
  expect(writeText).toHaveBeenNthCalledWith(1, generated);
  expect(getSnapshot).toHaveBeenCalledTimes(1);
  expect(getProfiles).toHaveBeenCalledTimes(1);
  expect(getLaunchSettings).toHaveBeenCalledTimes(1);
  expect(getCacheInspection).toHaveBeenCalledTimes(1);

  await act(async () => {
    await result.current.retryCopySetup();
  });

  expect(result.current.state).toBe("copied");
  expect(result.current.text).toBe(generated);
  expect(writeText).toHaveBeenCalledTimes(2);
  expect(writeText).toHaveBeenNthCalledWith(2, generated);
  expect(getSnapshot).toHaveBeenCalledTimes(1);
  expect(getProfiles).toHaveBeenCalledTimes(1);
  expect(getLaunchSettings).toHaveBeenCalledTimes(1);
  expect(getCacheInspection).toHaveBeenCalledTimes(1);
});

test("failed profile read reports unavailable mods even when cache fingerprint is available", async () => {
  vi.mocked(getProfiles).mockRejectedValue(new Error("profile unavailable"));
  vi.mocked(getCacheInspection).mockResolvedValue({
    cache: { currentProfileFingerprint: "cache-profile" },
    health: { status: "ready", profileFingerprint: "cache-profile" },
  } as unknown as Awaited<ReturnType<typeof getCacheInspection>>);
  const writeText = vi.fn().mockResolvedValue(undefined);
  installClipboard(writeText);

  const { result } = renderHook(() => useCopySetup("recommended"));
  await act(async () => {
    await result.current.copySetup();
  });

  expect(result.current.state).toBe("copied");
  expect(result.current.text).toContain("Profile fingerprint: cache-profile\n");
  expect(result.current.text).toContain("Enabled mods: unavailable\n");
  expect(result.current.text).not.toContain("Enabled mods: 0\n");
  expect(writeText).toHaveBeenCalledWith(result.current.text);
});

test("successful empty profile read remains an observed zero-mod setup", async () => {
  vi.mocked(getProfiles).mockResolvedValue({
    enabledMods: [],
    profiles: [],
  } as unknown as Awaited<ReturnType<typeof getProfiles>>);
  const writeText = vi.fn().mockResolvedValue(undefined);
  installClipboard(writeText);

  const { result } = renderHook(() => useCopySetup("recommended"));
  await act(async () => {
    await result.current.copySetup();
  });

  expect(result.current.state).toBe("copied");
  expect(result.current.text).toContain("Enabled mods: 0\n");
  expect(result.current.text).not.toContain("Enabled mods: unavailable\n");
});

function installClipboard(writeText: ReturnType<typeof vi.fn>) {
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText },
  });
}
