import homePresentationStyles from "../homePresentation.css?raw";
import { render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import type { usePreparation } from "../usePreparation";
import type { DesktopSnapshot, WireframeHull } from "../types";
import { HomePage } from "./HomePage";

vi.mock("./FlightInstrument", () => ({
  FlightInstrument: ({ hull }: { hull: WireframeHull }) => <div>{hull.name}</div>,
}));

const hull: WireframeHull = {
  id: "odyssey",
  name: "Odyssey",
  hullSize: "CAPITAL_SHIP",
  style: "HIGH_TECH",
  featured: true,
  bounds: [{ x: 1, y: 0 }, { x: -1, y: 1 }, { x: -1, y: -1 }],
  engines: [],
  mounts: [],
};

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
    totalMillis: 0,
    longestSessionMillis: 0,
    averageMillis: 0,
    launches: 0,
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
    profiles: [],
  },
  cacheHealth: {
    format: "starsector-preflight-cache-health-v1",
    status: "cold",
    profileFingerprint: "profile-fingerprint",
    issues: [],
    repairBytes: 0,
    repairFiles: 0,
  },
  cacheLoading: false,
  cacheRepairing: false,
  preparationCancelling: false,
  preparationPercent: null,
  preparationPhaseLabel: "Textures",
  preparationPlan: null,
  preparationPlanLoading: false,
  preparing: true,
  profilePrepared: false,
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

function renderHome(currentPreparation: ReturnType<typeof usePreparation>, operationBlocked = false) {
  return render(<HomePage
    snapshot={snapshot}
    status="ready"
    message=""
    messageTone="info"
    isReady
    needsPreparation
    optimizationPreset="recommended"
    preparation={currentPreparation}
    updateStatus={null}
    launcherSettings={null}
    launcherDraft={null}
    launcherSettingsLoading={false}
    launcherSettingsSaving={false}
    launchSettingsDirty={false}
    operationBlocked={operationBlocked}
    launchSettingsEditingBlocked={false}
    launchSettingsSaveBlocked={false}
    theme="light"
    onLauncherChange={vi.fn()}
    onChooseInstall={vi.fn()}
    onPrimaryLaunch={vi.fn()}
    onLaunchWithoutPreparing={vi.fn()}
    stoppingGame={false}
    forceStopAvailable={false}
    onStopGame={vi.fn()}
    onSaveLauncherSettings={vi.fn()}
    retryLabel="Try again"
    onRetry={vi.fn()}
    runFailure={null}
    onDismissRunFailure={vi.fn()}
    onNavigate={vi.fn()}
    instrumentHull={hull}
    launchProfileName="Exploration"
  />);
}

test("Home explains a preparation recovered after restart without inventing a percentage", () => {
  renderHome(preparation, true);

  expect(screen.getByText("Preparation in progress")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Preparation in progress…" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Stop safely" })).toBeEnabled();
  expect(screen.getByText(/Reconnected after restart\. Starsector stays closed/)).toBeInTheDocument();
  expect(document.body).not.toHaveTextContent("0%");
});

test("Home keeps storage-mode taxonomy out of the default low-disk decision", () => {
  const lowDiskPreparation = {
    ...preparation,
    preparing: false,
    preparationPlan: {
      safeToPrepare: false,
      predictedAdditionalBytes: 4_000_000_000,
      requiredFreeBytes: 5_000_000_000,
      usableBytes: 3_000_000_000,
    },
  } as unknown as ReturnType<typeof usePreparation>;

  renderHome(lowDiskPreparation);

  const noteText = screen.getByText(/Preparation needs .* free; .* is available\./);
  const lessDisk = screen.getByRole("button", { name: "Prepare with less disk" });
  expect(screen.getByRole("button", { name: "Launch at normal speed" })).toBeEnabled();
  expect(document.body).not.toHaveTextContent(/Full preparation|Balanced|Fastest/);

  const note = noteText.closest(".launch-console__note");
  const actions = lessDisk.closest(".launch-console__actions");
  expect(note).not.toBeNull();
  expect(actions).not.toBeNull();
  expect(note!.nextElementSibling).toBe(actions);
});

test("compact preparation keeps its note immediately above the real action row", () => {
  const styles = homePresentationStyles.replace(/\/\*[\s\S]*?\*\//g, "");
  const mediaIndex = styles.search(/@media\s*\(\s*max-width\s*:\s*720px\s*\)/);
  const rule = styles.match(
    /\.launch-console--layout-preparation\.launch-console--ready\s+\.launch-console__note\s*\{([^}]*)\}/,
  );

  expect(mediaIndex).toBeGreaterThanOrEqual(0);
  expect(rule).not.toBeNull();
  expect(rule?.index ?? -1).toBeGreaterThan(mediaIndex);
  expect(rule?.[1]).toMatch(/bottom\s*:\s*82px\s*;?/);
  expect(styles).not.toContain(":has(");
});
