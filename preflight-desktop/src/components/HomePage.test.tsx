import type { ComponentProps } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, test, vi } from "vitest";
import type { usePreparation } from "../usePreparation";
import type { useInstrumentHull } from "../useInstrumentHull";
import type { DesktopSnapshot, WireframeHull } from "../types";
import { HomePage } from "./HomePage";

vi.mock("./FlightInstrument", () => ({
  FlightInstrument: ({ hull }: { hull: WireframeHull }) => <div data-testid="instrument">{hull.name}</div>,
}));

const instrumentHull: WireframeHull = {
  id: "odyssey",
  name: "Odyssey",
  hullSize: "CAPITAL_SHIP",
  style: "HIGH_TECH",
  featured: true,
  bounds: [{ x: 1, y: 0 }, { x: -1, y: 1 }, { x: -1, y: -1 }],
  engines: [],
  mounts: [],
};

const instrumentHullState = {
  catalog: null,
  catalogLoaded: false,
  catalogHulls: [instrumentHull],
  hulls: [instrumentHull],
  selected: instrumentHull,
  selectedId: instrumentHull.id,
  tuning: { outerSmooth: 0, outerDetail: 0, innerSmooth: 0, innerDetail: 0, height: 1 },
  customized: false,
  choose: vi.fn(),
  remove: vi.fn(),
  customize: vi.fn(),
  resetCustomization: vi.fn(),
} as ReturnType<typeof useInstrumentHull>;

beforeEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.homeMode;
  delete document.documentElement.dataset.homePlaytime;
});

const snapshot: DesktopSnapshot = {
  protocol: 1,
  engineVersion: "test",
  platform: "mac",
  ready: true,
  selected: {
    installRoot: "/Applications/Starsector",
    launcher: "/Applications/Starsector/Starsector.app",
    kind: "app",
    score: 100,
    source: "test",
  },
  candidates: [],
  diagnostics: [],
  preflightHome: "/tmp/preflight",
  cachePresent: true,
  lastRun: null,
  playtime: {
    readable: true,
    totalMillis: 3_600_000,
    longestSessionMillis: 3_600_000,
    averageMillis: 3_600_000,
    launches: 1,
    sessionsWithoutDuration: 0,
    first: null,
    last: null,
  },
};

const preparation = {
  cache: {
    format: "starsector-preflight-cache-v1",
    root: "/tmp/preflight/cache",
    present: true,
    total: { bytes: 1, files: 1 },
    groups: [],
    uncategorizedBytes: 0,
    currentProfileFingerprint: "profile-fingerprint",
    profiles: [{
      fingerprint: "profile-fingerprint",
      current: true,
      bytes: 1,
      indexBytes: 1,
      manifestBytes: 1,
      lastModifiedMillis: 1,
    }],
  },
  cacheHealth: {
    format: "starsector-preflight-cache-health-v1",
    status: "ready",
    profileFingerprint: "profile-fingerprint",
    issues: [],
    repairBytes: 0,
    repairFiles: 0,
  },
  cacheLoading: false,
  cacheRepairing: false,
  preparationCancelling: false,
  preparationPercent: 0,
  preparationPhaseLabel: undefined,
  preparationPlan: null,
  preparationPlanLoading: false,
  preparing: false,
  profilePrepared: true,
  resourcePreset: "balanced",
  textureStorage: "balanced",
  clearCache: vi.fn(),
  invalidatePreparationPlan: vi.fn(),
  prepare: vi.fn(),
  repairAndPrepare: vi.fn(),
  refreshCache: vi.fn(),
  setResourcePreset: vi.fn(),
  setTextureStorage: vi.fn(),
  stopPreparation: vi.fn(),
} as unknown as ReturnType<typeof usePreparation>;

function props(overrides: Partial<ComponentProps<typeof HomePage>> = {}): ComponentProps<typeof HomePage> {
  return {
    snapshot,
    status: "ready",
    message: "",
    messageTone: "info",
    isReady: true,
    needsPreparation: false,
    optimizationPreset: "recommended",
    preparation,
    updateStatus: null,
    launcherSettings: null,
    launcherDraft: null,
    launcherSettingsLoading: false,
    launcherSettingsSaving: false,
    launchSettingsDirty: false,
    operationBlocked: false,
    launchSettingsEditingBlocked: false,
    launchSettingsSaveBlocked: false,
    theme: "light",
    onLauncherChange: vi.fn(),
    onChooseInstall: vi.fn(),
    onPrimaryLaunch: vi.fn(),
    onLaunchWithoutPreparing: vi.fn(),
    stoppingGame: false,
    forceStopAvailable: false,
    onStopGame: vi.fn(),
    onSaveLauncherSettings: vi.fn(),
    retryLabel: "Try again",
    onRetry: vi.fn(),
    runFailure: null,
    onDismissRunFailure: vi.fn(),
    onNavigate: vi.fn(),
    instrumentHull: instrumentHullState,
    launchProfileName: "Exploration",
    modReadiness: null,
    ...overrides,
  };
}

function failedSnapshot(
  installRoot = "/Applications/Starsector",
  profileFingerprint = "profile-fingerprint",
): DesktopSnapshot {
  return {
    ...snapshot,
    lastRun: {
      directory: "/tmp/preflight/runs/failed",
      modifiedAt: "2026-08-18T10:00:00Z",
      installRoot,
      profileFingerprint,
      adapterHealth: null,
    },
  };
}

function failedRunFailure(
  installRoot = "/Applications/Starsector",
  profileFingerprint = "profile-fingerprint",
) {
  return {
    summary: "Starsector stopped before reaching the main menu.",
    installRoot,
    profileFingerprint,
  };
}

function preparationForProfile(profileFingerprint: string, cacheLoading = false): ReturnType<typeof usePreparation> {
  return {
    ...preparation,
    cacheLoading,
    cache: cacheLoading ? null : {
      ...preparation.cache!,
      currentProfileFingerprint: profileFingerprint,
      profiles: [{
        ...preparation.cache!.profiles[0],
        fingerprint: profileFingerprint,
      }],
    },
  } as ReturnType<typeof usePreparation>;
}

test("settled Home shows the installation and active named profile beside the launch action", () => {
  render(<HomePage {...props()} />);

  expect(screen.getByText("Exploration", { selector: ".home-launch-identity strong" })).toBeInTheDocument();
  const installation = screen.getByLabelText("Installation /Applications/Starsector");
  expect(installation).toHaveAttribute("title", "/Applications/Starsector");
  expect(installation).toHaveAttribute("tabindex", "0");
  expect(screen.getByRole("button", { name: "Launch Starsector" })).toBeEnabled();
});

test("settled Home omits an invented label for an unsaved active mod set", () => {
  render(<HomePage {...props({ launchProfileName: null })} />);

  expect(screen.queryByText("Current mod setup")).not.toBeInTheDocument();
  expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
});

test("Home surfaces a broken mod setup without blocking launch", async () => {
  const user = userEvent.setup();
  const onNavigate = vi.fn();
  render(<HomePage {...props({
    onNavigate,
    modReadiness: {
      format: "starsector-preflight-mod-readiness-v1",
      ready: false,
      counts: { blocking: 2, warning: 0, info: 0, unknown: 0 },
      findings: [],
      modDirectories: 83,
      metadataBytes: 131_072,
      elapsedMillis: 6,
    },
  })} />);

  expect(screen.getByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "2 mod problems" }));
  expect(onNavigate).toHaveBeenCalledWith("mods");
});

test("Home exposes direct display controls without conflicting compact and playtime states", async () => {
  const user = userEvent.setup();
  const choose = vi.fn();
  const navigate = vi.fn();
  const hammerhead = { ...instrumentHull, id: "hammerhead", name: "Hammerhead" };
  render(<HomePage {...props({
    instrumentHull: {
      ...instrumentHullState,
      hulls: [instrumentHull, hammerhead],
      choose,
    },
    onNavigate: navigate,
  })} />);

  await user.click(screen.getByRole("button", { name: "Next display ship" }));
  expect(choose).toHaveBeenCalledWith("hammerhead");
  await user.click(screen.getByRole("button", { name: "Odyssey" }));
  expect(navigate).toHaveBeenCalledWith("hangar");

  await user.click(screen.getByRole("button", { name: "Reverse ship rotation" }));
  expect(screen.getByRole("button", { name: "Reverse ship rotation" })).toBeEnabled();

  await user.click(screen.getByRole("button", { name: "Hide time" }));
  expect(screen.getByRole("button", { name: "Show time" })).toHaveAttribute("aria-pressed", "false");
  await user.click(screen.getByRole("button", { name: "Hide ship" }));
  expect(screen.getByRole("button", { name: "Show ship" })).toHaveAttribute("aria-pressed", "true");
  await user.click(screen.getByRole("button", { name: "Show time" }));
  expect(screen.getByRole("button", { name: "Hide time" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "Hide ship" })).toHaveAttribute("aria-pressed", "false");
});

test("Home keeps the new profile and installation visible when that setup needs preparation", () => {
  render(<HomePage {...props({ needsPreparation: true })} />);

  expect(screen.getByText("Exploration", { selector: ".home-launch-identity strong" })).toBeInTheDocument();
  expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Prepare and launch" })).toBeDisabled();
  expect(screen.getByText("Storage must be calculated before preparation.")).toBeInTheDocument();
});

test("closed Options keeps pending launch-setting changes visible", () => {
  window.localStorage.clear();
  render(<HomePage {...props({ launchSettingsDirty: true })} />);

  expect(screen.getByRole("button", { name: "Options · changed" })).toHaveAttribute("aria-expanded", "false");
});

test("a launch error keeps the retry target visible beside its recovery action", () => {
  render(<HomePage {...props({ status: "error", message: "Launch failed", messageTone: "error", retryLabel: "Try launch again" })} />);

  expect(screen.getByRole("alert")).toHaveTextContent("Launch failed");
  expect(screen.getByRole("button", { name: "Try launch again" })).toBeEnabled();
  expect(screen.getByText("Exploration", { selector: ".home-launch-identity strong" })).toBeInTheDocument();
  expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
});

test("run recovery keeps Relaunch when the captured failed target still matches the current setup", () => {
  const dismiss = vi.fn();
  render(<HomePage {...props({
    snapshot: failedSnapshot(),
    runFailure: failedRunFailure(),
    onDismissRunFailure: dismiss,
  })} />);

  expect(screen.getByText("Run needs attention")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Relaunch" })).toBeEnabled();
  expect(dismiss).not.toHaveBeenCalled();
});

test("cosmetic Home changes keep recovery bound to the same failed setup", () => {
  const dismiss = vi.fn();
  const view = render(<HomePage {...props({
    snapshot: failedSnapshot(),
    runFailure: failedRunFailure(),
    onDismissRunFailure: dismiss,
  })} />);

  view.rerender(<HomePage {...props({
    snapshot: failedSnapshot(),
    runFailure: failedRunFailure(),
    onDismissRunFailure: dismiss,
    theme: "dark",
    launchSettingsDirty: true,
  })} />);

  expect(screen.getByText("Run needs attention")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Relaunch" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Options · changed" })).toBeInTheDocument();
  expect(dismiss).not.toHaveBeenCalled();
});

test("switching to another profile retires the old Home recovery card", async () => {
  const dismiss = vi.fn();
  render(<HomePage {...props({
    snapshot: failedSnapshot(),
    preparation: preparationForProfile("different-profile"),
    runFailure: failedRunFailure(),
    onDismissRunFailure: dismiss,
    launchProfileName: "Utilities only",
  })} />);

  expect(screen.queryByText("Run needs attention")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Relaunch" })).not.toBeInTheDocument();
  await waitFor(() => expect(dismiss).toHaveBeenCalledOnce());
  expect(screen.getByText("Utilities only", { selector: ".home-launch-identity strong" })).toBeInTheDocument();
  expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
});

test("changing installations retires the old Home recovery card before the new profile identity settles", async () => {
  const dismiss = vi.fn();
  render(<HomePage {...props({
    snapshot: {
      ...snapshot,
      selected: {
        ...snapshot.selected!,
        installRoot: "/Games/Starsector",
        launcher: "/Games/Starsector/starsector.exe",
      },
    },
    preparation: preparationForProfile("profile-fingerprint", true),
    runFailure: failedRunFailure(),
    onDismissRunFailure: dismiss,
  })} />);

  expect(screen.queryByText("Run needs attention")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Relaunch" })).not.toBeInTheDocument();
  await waitFor(() => expect(dismiss).toHaveBeenCalledOnce());
});

test("an in-flight profile identity refresh keeps recovery stable until the new setup is known", () => {
  const dismiss = vi.fn();
  render(<HomePage {...props({
    snapshot: failedSnapshot(),
    preparation: preparationForProfile("different-profile", true),
    runFailure: failedRunFailure(),
    onDismissRunFailure: dismiss,
  })} />);

  expect(screen.getByText("Run needs attention")).toBeInTheDocument();
  expect(dismiss).not.toHaveBeenCalled();
});

test("preview recovery without captured native identity remains available", () => {
  render(<HomePage {...props({
    snapshot: failedSnapshot(),
    preparation: preparationForProfile("different-profile"),
    runFailure: { summary: "Preview run failed." },
  })} />);

  expect(screen.getByText("Run needs attention")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Relaunch" })).toBeEnabled();
});
