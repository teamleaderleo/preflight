import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { listen } from "@tauri-apps/api/event";
import { open } from "@tauri-apps/plugin-dialog";
import App from "./App";
import { failedRunSummary } from "./uiFormat";
import { adapterHealthLine } from "./adapterHealthText";
import { HOME_OPTIONS_STORAGE_KEY } from "./desktopStorage";
import { isCurrentProfilePrepared, preparationModeMatchesStorage } from "./usePreparation";
import * as bridge from "./bridge";
import type { CacheHealth, CacheSnapshot, LaunchSettings } from "./types";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn(), save: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

beforeEach(() => {
  window.localStorage.clear();
  window.history.replaceState(null, "", "/");
});

function cacheSnapshot(overrides: Partial<CacheSnapshot> = {}): CacheSnapshot {
  return {
    format: "starsector-preflight-cache-v1",
    root: "~/.starsector-preflight",
    present: true,
    total: { bytes: 1024, files: 3 },
    groups: [],
    uncategorizedBytes: 0,
    currentProfileFingerprint: "preview-profile",
    profiles: [{
      fingerprint: "preview-profile",
      current: true,
      bytes: 1024,
      indexBytes: 128,
      manifestBytes: 256,
      lastModifiedMillis: Date.now(),
    }],
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

test("requires both the exact current index and texture manifest before calling a profile prepared", () => {
  expect(isCurrentProfilePrepared(cacheSnapshot())).toBe(true);
  expect(isCurrentProfilePrepared(cacheSnapshot({
    profiles: [{
      ...cacheSnapshot().profiles[0],
      manifestBytes: 0,
    }],
  }))).toBe(false);
  expect(isCurrentProfilePrepared(cacheSnapshot({ currentProfileFingerprint: "changed-profile" }))).toBe(false);
  expect(isCurrentProfilePrepared(null)).toBe(false);
});

test("minimal preparation needs the exact current index but intentionally has no texture manifest", () => {
  expect(isCurrentProfilePrepared(cacheSnapshot({
    profiles: [{
      ...cacheSnapshot().profiles[0],
      manifestBytes: 0,
    }],
  }), "minimal")).toBe(true);
  expect(isCurrentProfilePrepared(cacheSnapshot({
    profiles: [{
      ...cacheSnapshot().profiles[0],
      indexBytes: 0,
      manifestBytes: 0,
    }],
  }), "minimal")).toBe(false);
});

test("the selected disk mode must match the preparation that is actually ready", () => {
  const full = {
    format: "starsector-preflight-cache-health-v1" as const,
    status: "ready" as const,
    profileFingerprint: "current-profile",
    preparedTextures: true,
    issues: [],
    repairBytes: 0,
    repairFiles: 0,
  };
  const minimal = { ...full, preparedTextures: false };

  expect(preparationModeMatchesStorage(full, "balanced")).toBe(true);
  expect(preparationModeMatchesStorage(full, "minimal")).toBe(false);
  expect(preparationModeMatchesStorage(minimal, "minimal")).toBe(true);
  expect(preparationModeMatchesStorage(minimal, "fastest")).toBe(false);
});

test("a failed game process keeps the first useful native detail bounded", () => {
  expect(failedRunSummary("\njava.lang.IllegalStateException: retreat failed\n\tat example.Run.run(Run.java:42)"))
    .toBe("Starsector closed with an error: java.lang.IllegalStateException: retreat failed The support evidence has full details.");
  expect(failedRunSummary("x".repeat(500))).toContain(`${"x".repeat(357)}…`);
  expect(failedRunSummary()).toBe("Starsector closed with an error. Support evidence was saved.");
});

test("latest-run compatibility stays short and treats fallback as a safe result", () => {
  const base = {
    format: "starsector-preflight-adapter-health-v1" as const,
    accelerationsActive: true,
    originalCodeRetained: false,
    reviewRecommended: false,
    transformationsApplied: 31,
    registryTargets: 32,
    containedFailures: 0,
    evidenceKinds: [],
    suggestedActions: [],
  };

  expect(adapterHealthLine({ ...base, status: "ACTIVE" })).toBe("Last run: acceleration active");
  expect(adapterHealthLine({
    ...base,
    status: "PARTIAL",
    originalCodeRetained: true,
    reviewRecommended: true,
  })).toBe("Last run: acceleration active, with safe fallback");
  expect(adapterHealthLine({
    ...base,
    status: "SAFE_FALLBACK",
    accelerationsActive: false,
    originalCodeRetained: true,
    reviewRecommended: true,
    transformationsApplied: 0,
  })).toBe("Last run: original game code used safely");
});

test("the default cold-profile action prepares with balanced settings and then launches", async () => {
  const user = userEvent.setup();
  const cold = cacheSnapshot({ profiles: [] });
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cold);
  const preparation = vi.spyOn(bridge, "startPreparation").mockResolvedValue({ pid: 4243 });
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });

  render(<App />);

  const action = await screen.findByRole("button", { name: "Prepare and launch" });
  await waitFor(() => expect(action).toBeEnabled());
  expect(screen.getByText("First launch setup")).toBeInTheDocument();
  expect(screen.getByText(/Uses about .* free required; .* available/))
    .toBeInTheDocument();
  expect(screen.getByLabelText("186h played across 78 recorded sessions")).toBeInTheDocument();
  expect(within(screen.getByRole("main")).queryByText(/^for Starsector$/i)).not.toBeInTheDocument();
  expect(screen.getByText(/^for Starsector$/i)).toBeInTheDocument();
  await user.click(action);
  await waitFor(() => expect(preparation).toHaveBeenCalledWith("/Applications/Starsector", "balanced", 4, 256));
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", [], "minimize"));

  cache.mockRestore();
  preparation.mockRestore();
  game.mockRestore();
});

test("repairs only the reviewed profile before rebuilding and launching", async () => {
  const user = userEvent.setup();
  const damaged: CacheHealth = {
    format: "starsector-preflight-cache-health-v1",
    status: "repair-needed",
    profileFingerprint: "preview-profile",
    issues: [{
      artifact: "prepared-textures",
      summary: "The prepared texture pack needs rebuilding.",
      path: "~/.starsector-preflight/cache/packs/preview-profile.spfp",
    }],
    repairBytes: 4096,
    repairFiles: 3,
  };
  const health = vi.spyOn(bridge, "getCacheHealth").mockResolvedValue(damaged);
  const repair = vi.spyOn(bridge, "repairCache").mockResolvedValue({
    format: "starsector-preflight-cache-repair-v1",
    safe: true,
    applied: true,
    status: "cold",
    profileFingerprint: "preview-profile",
    bytes: 4096,
    files: 3,
    targets: [],
  });
  const preparation = vi.spyOn(bridge, "startPreparation").mockResolvedValue({ pid: 4243 });
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });

  render(<App />);

  expect(await screen.findByText("Prepared data needs repair")).toBeInTheDocument();
  expect(screen.getAllByRole("button", { name: "Repair and launch" })).toHaveLength(1);
  expect(screen.getByRole("button", { name: "Repair details" })).toBeEnabled();
  expect(screen.getByText(/Damaged prepared data will be rebuilt/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Repair and launch" }));
  await waitFor(() => expect(repair).toHaveBeenCalledWith("/Applications/Starsector", "preview-profile"));
  await waitFor(() => expect(preparation).toHaveBeenCalledWith("/Applications/Starsector", "balanced", 4, 256));
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", [], "minimize"));

  health.mockRestore();
  repair.mockRestore();
  preparation.mockRestore();
  game.mockRestore();
});

test("preparation started on Home remains visible and can be stopped safely", async () => {
  const user = userEvent.setup();
  const pending = deferred<{ pid: number }>();
  const cold = cacheSnapshot({ profiles: [] });
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cold);
  const preparation = vi.spyOn(bridge, "startPreparation").mockImplementation(() => pending.promise);
  const cancel = vi.spyOn(bridge, "cancelPreparation").mockResolvedValue(true);
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  const action = await screen.findByRole("button", { name: "Prepare and launch" });
  await waitFor(() => expect(action).toBeEnabled());
  await user.click(action);
  expect(await screen.findByRole("button", { name: "Stop safely" })).toBeEnabled();
  expect(screen.getByText(/Starsector opens automatically/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Stop safely" }));
  expect(cancel).toHaveBeenCalledOnce();
  pending.resolve({ pid: 4243 });
  await waitFor(() => expect(screen.queryByRole("button", { name: "Stop safely" })).not.toBeInTheDocument());
  expect(game).not.toHaveBeenCalled();

  cache.mockRestore();
  preparation.mockRestore();
  cancel.mockRestore();
  game.mockRestore();
});

test("a refused preparation still leaves a way to launch the game", async () => {
  const user = userEvent.setup();
  const cold = cacheSnapshot({ profiles: [] });
  const basePlan = await bridge.getPreparationPlan("/Applications/Starsector", "balanced", 4);
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cold);
  const plan = vi.spyOn(bridge, "getPreparationPlan").mockResolvedValue({
    ...basePlan,
    safeToPrepare: false,
    refusalReason: "Preparation needs more room than this disk has.",
    usableBytes: 2 * 1024 ** 3,
  });
  const preparation = vi.spyOn(bridge, "startPreparation").mockResolvedValue({ pid: 4243 });
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });

  render(<App />);

  const launch = await screen.findByRole("button", { name: "Launch at normal speed" });
  expect(screen.getByText(/Preparation needs .* free; .* is available\./)).toBeInTheDocument();
  await user.click(launch);

  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", [], "minimize"));
  expect(preparation).not.toHaveBeenCalled();

  cache.mockRestore();
  plan.mockRestore();
  preparation.mockRestore();
  game.mockRestore();
});

test("a refused preparation offers the preparation that barely uses disk", async () => {
  const user = userEvent.setup();
  const cold = cacheSnapshot({ profiles: [] });
  const basePlan = await bridge.getPreparationPlan("/Applications/Starsector", "balanced", 4);
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cold);
  const plan = vi.spyOn(bridge, "getPreparationPlan").mockResolvedValue({
    ...basePlan,
    safeToPrepare: false,
    refusalReason: "Preparation needs more room than this disk has.",
    usableBytes: 2 * 1024 ** 3,
  });
  const preparation = vi.spyOn(bridge, "startPreparation").mockResolvedValue({ pid: 4243 });
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });

  render(<App />);

  const action = await screen.findByRole("button", { name: "Prepare with less disk" });
  await user.click(action);

  await waitFor(() => expect(preparation)
    .toHaveBeenCalledWith("/Applications/Starsector", "minimal", 4, 256));

  cache.mockRestore();
  plan.mockRestore();
  preparation.mockRestore();
  game.mockRestore();
});

test("the Preflight page offers the same direct minimal-disk recovery", async () => {
  const cold = cacheSnapshot({ profiles: [] });
  const basePlan = await bridge.getPreparationPlan("/Applications/Starsector", "balanced", 4);
  const reason = "Preparation needs up to 11.0 GB plus a 1.0 GB reserve; only 2.0 GB is available.";
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cold);
  const plan = vi.spyOn(bridge, "getPreparationPlan").mockResolvedValue({
    ...basePlan,
    safeToPrepare: false,
    refusalReason: reason,
    usableBytes: 2 * 1024 ** 3,
  });
  const preparation = vi.spyOn(bridge, "startPreparation").mockResolvedValue({ pid: 4243 });

  render(<App />);

  const homeAction = await screen.findByRole("button", { name: "Prepare with less disk" });
  expect(screen.getByText(/Preparation needs .* free; .* is available\./)).toBeInTheDocument();
  expect(homeAction).toBeEnabled();
  expect(screen.queryByRole("region", { name: "Current Preflight setup" })).not.toBeInTheDocument();
  expect(preparation).not.toHaveBeenCalled();
  await userEvent.setup().click(screen.getByRole("button", { name: "Speed" }));
  expect(await screen.findByRole("heading", { name: "Speed", level: 1 })).toBeInTheDocument();
  await screen.findByText(reason);
  expect(screen.getByText("Free space needed to prepare")).toBeInTheDocument();
  const action = await screen.findByRole("button", { name: "Prepare with less disk" });
  await userEvent.setup().click(action);
  await waitFor(() => expect(preparation)
    .toHaveBeenCalledWith("/Applications/Starsector", "minimal", 4, 256));

  cache.mockRestore();
  plan.mockRestore();
  preparation.mockRestore();
});

test("shows a useful ready-state home screen in browser preview", async () => {
  const user = userEvent.setup();
  render(<App />);

  expect(await screen.findByText("Ready")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getAllByText("Launch Starsector")).toHaveLength(1);
  expect(screen.queryByRole("button", { name: "Choose another" })).not.toBeInTheDocument();
  expect(screen.queryByLabelText("Active profile")).not.toBeInTheDocument();
  expect(screen.getByLabelText("186h played across 78 recorded sessions")).toBeInTheDocument();
  expect(screen.queryByLabelText("Mod profile")).not.toBeInTheDocument();
  expect(window.localStorage.getItem(HOME_OPTIONS_STORAGE_KEY)).toBeNull();
  await user.click(screen.getByRole("button", { name: "Options" }));
  expect(window.localStorage.getItem(HOME_OPTIONS_STORAGE_KEY)).toBe("open");
  expect(screen.getByLabelText("Home resolution")).toHaveValue("1440x932");
  expect(screen.queryByLabelText("Mod profile")).not.toBeInTheDocument();
  expect(screen.queryByText(/Named by you/)).not.toBeInTheDocument();
  expect(screen.queryByText("Recommended")).not.toBeInTheDocument();
  expect(screen.queryByText(/Recommended optimizations/)).not.toBeInTheDocument();
  expect(screen.queryByText(/Prepared ·/)).not.toBeInTheDocument();
  expect(screen.queryByText("Game setup")).not.toBeInTheDocument();
});

test("starts from the remembered installation instead of rediscovering it", async () => {
  window.localStorage.setItem("preflight.lastInstallRoot", "/Games/Starsector");
  const initial = await bridge.getSnapshot();
  const bootstrap = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue(initial);

  render(<App />);

  expect(await screen.findByText("Ready")).toBeInTheDocument();
  expect(bootstrap).toHaveBeenCalledWith("/Games/Starsector");
  expect(window.localStorage.getItem("preflight.lastInstallRoot"))
    .toBe(initial.selected?.installRoot);
  bootstrap.mockRestore();
});

test("a stale remembered installation falls back to normal discovery", async () => {
  window.localStorage.setItem("preflight.lastInstallRoot", "/Moved/Starsector");
  const initial = await bridge.getSnapshot();
  const bootstrap = vi.spyOn(bridge, "getBootstrapSnapshot")
    .mockResolvedValueOnce({ ...initial, ready: false, selected: null })
    .mockResolvedValueOnce(initial);

  render(<App />);

  expect(await screen.findByText("Ready")).toBeInTheDocument();
  expect(bootstrap.mock.calls.slice(0, 2)).toEqual([
    ["/Moved/Starsector"],
    [],
  ]);
  expect(window.localStorage.getItem("preflight.lastInstallRoot"))
    .toBe(initial.selected?.installRoot);
  bootstrap.mockRestore();
});

test("home surfaces the latest compatibility verdict without exposing the raw report", async () => {
  const base = await bridge.getSnapshot();
  const snapshot = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue({
    ...base,
    lastRun: {
      directory: "~/.starsector-preflight/runs/latest",
      modifiedAt: "2026-08-17T00:00:00Z",
      installRoot: base.selected?.installRoot,
      profileFingerprint: "preview-profile",
      adapterHealth: {
        format: "starsector-preflight-adapter-health-v1",
        status: "PARTIAL",
        accelerationsActive: true,
        originalCodeRetained: true,
        reviewRecommended: true,
        transformationsApplied: 31,
        registryTargets: 32,
        containedFailures: 0,
        evidenceKinds: ["VERSION_OR_TARGET_MISMATCH"],
        suggestedActions: ["Keep playing if the game is otherwise healthy."],
      },
    },
  });

  render(<App />);

  expect(await screen.findByText("Last run: acceleration active, with safe fallback"))
    .toHaveAttribute("title", "Keep playing if the game is otherwise healthy.");
  expect(screen.queryByText("VERSION_OR_TARGET_MISMATCH")).not.toBeInTheDocument();
  snapshot.mockRestore();
});

test("home and speed omit launch evidence from another profile", async () => {
  const user = userEvent.setup();
  const base = await bridge.getSnapshot();
  const snapshot = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue({
    ...base,
    lastRun: {
      directory: "~/.starsector-preflight/runs/latest",
      modifiedAt: "2026-08-17T00:00:00Z",
      installRoot: base.selected?.installRoot,
      profileFingerprint: "different-profile",
      adapterHealth: {
        format: "starsector-preflight-adapter-health-v1",
        status: "ACTIVE",
        accelerationsActive: true,
        originalCodeRetained: true,
        reviewRecommended: false,
        transformationsApplied: 32,
        registryTargets: 32,
        containedFailures: 0,
        evidenceKinds: [],
        suggestedActions: [],
      },
      startupMillis: 15_250,
    },
  });

  render(<App />);

  await screen.findByText("Ready");
  expect(screen.queryByText("Last run: acceleration active")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Speed" }));
  expect(screen.queryByText("Last fast launch: 15.3s to the menu.")).not.toBeInTheDocument();
  snapshot.mockRestore();
});

test("setup keeps a single installation action and hides unavailable ready-state panels", async () => {
  const user = userEvent.setup();
  const snapshot = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue({
    ...(await bridge.getSnapshot()),
    ready: false,
    selected: null,
    diagnostics: ["No supported Starsector launcher was found."],
  });
  render(<App />);

  expect(await screen.findByRole("heading", { name: "Setup", level: 1 })).toBeInTheDocument();
  expect(screen.getAllByRole("button", { name: "Choose game folder" })).toHaveLength(1);
  expect(screen.getByText("Preflight creates reusable startup data for your current mod setup, then opens Starsector. Your game, mods, and saves stay unchanged.")).toBeVisible();
  expect(screen.queryByRole("region", { name: "Current Preflight setup" })).not.toBeInTheDocument();
  expect(screen.queryByText("Unavailable")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Speed" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Mods" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Help" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "Help" }));
  expect(await screen.findByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Make a support file" })).toBeEnabled();
  expect(screen.getByText(/Never attach a support ZIP there/)).toBeVisible();
  expect(screen.getByText(/quote only the case number/)).toBeVisible();

  snapshot.mockRestore();
});

test("a failed launch is an alert and retries the launch operation", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame")
    .mockRejectedValueOnce(new Error("launcher refused"))
    .mockResolvedValueOnce({ pid: 4242 });
  render(<App />);

  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  expect(await screen.findByRole("heading", { name: "Needs attention", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("launcher refused");
  await user.click(screen.getByRole("button", { name: "Try launch again" }));

  await waitFor(() => expect(game).toHaveBeenCalledTimes(2));
  expect(await screen.findByRole("heading", { name: "Running", level: 1 })).toBeInTheDocument();
  game.mockRestore();
});

test("a failed launch offers help, and help is one click away from making the file", async () => {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/?scenario=run-failure");
  render(<App />);

  expect(await screen.findByRole("alert")).toHaveTextContent("Starsector stopped before reaching the main menu.");
  await user.click(screen.getByRole("button", { name: "Get help" }));

  expect(await screen.findByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Make a support file" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: "Benchmark", level: 1 })).not.toBeInTheDocument();
});

test("blocks preparation mutations while the game is running", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockImplementation(() => new Promise(() => undefined));
  render(<App />);

  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalled());

  await user.click(screen.getByRole("button", { name: "Speed" }));
  expect(await screen.findByText("Opening Starsector. Other changes wait until it finishes.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "View progress" })).toBeEnabled();
  await user.click(await screen.findByText("Advanced controls"));
  expect(await screen.findByRole("radio", { name: "Recommended optimizations" })).toBeDisabled();
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeDisabled();
  expect(screen.getByRole("button", { name: /Medium4 workers/ })).toBeDisabled();
  expect(screen.queryByRole("button", { name: /Calculating|Prepare current profile/ })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Review cleanup" })).toBeDisabled();

  game.mockRestore();
});

test("does not rediscover a stable installation when the window regains focus", async () => {
  const snapshot = vi.spyOn(bridge, "getSnapshot");
  render(<App />);
  await screen.findByRole("heading", { name: "Ready", level: 1 });
  const afterSetup = snapshot.mock.calls.length;

  window.dispatchEvent(new Event("focus"));
  document.dispatchEvent(new Event("visibilitychange"));
  await Promise.resolve();

  expect(snapshot.mock.calls.length).toBe(afterSetup);
  expect(screen.getByRole("heading", { name: "Ready", level: 1 })).toBeInTheDocument();
  snapshot.mockRestore();
});

test("keeps the ship display off the installation picker", async () => {
  const snapshot = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue({
    ...(await bridge.getSnapshot()),
    ready: false,
    selected: null,
  });

  const { container } = render(<App />);
  await screen.findByRole("button", { name: "Choose game folder" });

  expect(container.querySelector(".home-flight-instrument")).not.toBeInTheDocument();
  snapshot.mockRestore();
});

test("window focus never changes the quick game controls", async () => {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Options" }));
  const sound = await screen.findByRole("checkbox", { name: "Home sound" });
  expect(sound).toBeEnabled();
  expect(sound).toBeChecked();

  window.dispatchEvent(new Event("blur"));
  document.dispatchEvent(new Event("visibilitychange"));
  window.dispatchEvent(new Event("focus"));
  await Promise.resolve();

  expect(sound).toBeEnabled();
  expect(sound).toBeChecked();
});

test("returning to the window does not re-read the installation while the game runs", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockImplementation(() => new Promise(() => undefined));
  const snapshot = vi.spyOn(bridge, "getSnapshot");
  render(<App />);

  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalled());
  const whileRunning = snapshot.mock.calls.length;

  window.dispatchEvent(new Event("focus"));
  await Promise.resolve();

  expect(snapshot.mock.calls.length).toBe(whileRunning);
  snapshot.mockRestore();
  game.mockRestore();
});

test("page navigation resets the viewport that actually owns desktop scrolling", async () => {
  const user = userEvent.setup();
  const { container } = render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Measure speed" }));
  const viewport = container.querySelector<HTMLElement>(".page-viewport");
  expect(viewport).not.toBeNull();
  if (!viewport) return;
  viewport.scrollTop = 500;

  await user.click(screen.getByRole("button", { name: "Home" }));
  await waitFor(() => {
    const homeViewport = container.querySelector<HTMLElement>(".page-viewport");
    expect(homeViewport).not.toBe(viewport);
    expect(homeViewport?.scrollTop).toBe(0);
  });
});

test("pointer drafting feedback performs at most one style write per display frame", () => {
  let frame: FrameRequestCallback | null = null;
  const requestFrame = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
    frame = callback;
    return 17;
  });
  try {
    const { container, unmount } = render(<App />);
    const shell = container.querySelector<HTMLElement>(".app-shell");
    expect(shell).not.toBeNull();
    if (!shell) return;

    fireEvent.pointerMove(shell, { clientX: 12, clientY: 24 });
    fireEvent.pointerMove(shell, { clientX: 120, clientY: 240 });
    expect(requestFrame).toHaveBeenCalledTimes(1);
    expect(shell.style.getPropertyValue("--grid-x")).toBe("");
    expect(frame).not.toBeNull();
    if (frame) (frame as FrameRequestCallback)(0);
    expect(shell.style.getPropertyValue("--grid-x")).toBe("120px");
    expect(shell.style.getPropertyValue("--grid-y")).toBe("240px");
    unmount();
  } finally {
    requestFrame.mockRestore();
  }
});

test("keyboard users can skip navigation and receive the new workspace heading", async () => {
  const user = userEvent.setup();
  const focus = vi.spyOn(HTMLElement.prototype, "focus");
  render(<App />);

  await screen.findByText("Ready");
  await user.tab();
  expect(screen.getByRole("link", { name: "Skip to workspace" })).toHaveFocus();

  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Measure speed" }));
  const heading = await screen.findByRole("heading", { name: "Benchmark", level: 1 });
  expect(heading).toHaveFocus();
  expect(focus).toHaveBeenCalledWith({ preventScroll: true });
  expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
  focus.mockRestore();
});

test("common game settings are editable beside launch", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  expect(await screen.findByLabelText("Home resolution")).toHaveValue("1440x932");
  expect(screen.getByLabelText("Home antialiasing")).toHaveValue("0");
  expect(screen.getByLabelText("Home UI size")).toHaveValue("1");
  expect(screen.getByLabelText("Home battle size")).toHaveValue(400);
  expect(screen.queryByRole("button", { name: "Apply changes" })).not.toBeInTheDocument();

  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "1200");
  expect(screen.getByText(/vanilla settings slider ends at 400/)).toBeInTheDocument();
  const apply = screen.getByRole("button", { name: "Apply changes" });
  expect(apply).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/ }));
  expect(apply).toBeEnabled();
  await user.click(apply);

  expect(await screen.findByText(/Global game settings applied and verified/)).toBeInTheDocument();
});

test("the primary action requires an explicit Apply before launching edited global settings", async () => {
  const user = userEvent.setup();
  const baseline = await bridge.getLaunchSettings("/Applications/Starsector");
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockResolvedValue({
    ...baseline,
    preferences: { ...baseline.preferences, battleSize: 300 },
  });
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(update).not.toHaveBeenCalled();
  expect(game).not.toHaveBeenCalled();
  expect(await screen.findByRole("heading", { name: "Game settings", level: 1 })).toBeInTheDocument();
  expect(screen.getByText(/Apply your changed global game settings before launching/)).toBeInTheDocument();

  const apply = screen.getByRole("button", { name: "Apply changes" });
  expect(apply).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/ }));
  await user.click(apply);

  await waitFor(() => expect(update).toHaveBeenCalledWith(
    "/Applications/Starsector",
    expect.objectContaining({ battleSize: 300 }),
    true,
  ));
  expect(await screen.findByText(/Global game settings applied and verified/)).toBeInTheDocument();
  expect(game).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: "Home" }));
  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalledWith(
    "/Applications/Starsector",
    "recommended",
    [],
    "minimize",
  ));
  update.mockRestore();
  game.mockRestore();
});

test("a refused explicit Apply keeps launch blocked", async () => {
  const user = userEvent.setup();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockRejectedValue(new Error("settings write refused"));
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(update).not.toHaveBeenCalled();
  expect(game).not.toHaveBeenCalled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/ }));
  await user.click(screen.getByRole("button", { name: "Apply changes" }));

  expect(await screen.findByText("settings write refused")).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("settings write refused");
  expect(update).toHaveBeenCalledWith(
    "/Applications/Starsector",
    expect.objectContaining({ battleSize: 300 }),
    true,
  );
  expect(game).not.toHaveBeenCalled();
  update.mockRestore();
  game.mockRestore();
});

test("an unrelated update check does not erase a game-settings failure", async () => {
  const user = userEvent.setup();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockRejectedValue(new Error("settings write refused"));
  const check = vi.spyOn(bridge, "checkForUpdate").mockResolvedValue({
    format: "preflight-update-v1",
    configured: true,
    currentVersion: "0.1.0",
    available: false,
    version: null,
    date: null,
    notes: null,
    reason: null,
  });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/ }));
  await user.click(screen.getByRole("button", { name: "Apply changes" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("settings write refused");

  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(await screen.findByRole("button", { name: "Check for updates" }));
  expect(await screen.findByText("Version 0.1.0 is current.")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Home" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("settings write refused");

  update.mockRestore();
  check.mockRestore();
});

test("navigation resets the previous workflow scroll position", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  document.documentElement.scrollTop = 240;
  document.body.scrollTop = 240;
  await user.click(screen.getByRole("button", { name: "Mods" }));

  await waitFor(() => {
    expect(document.documentElement.scrollTop).toBe(0);
    expect(document.body.scrollTop).toBe(0);
  });
});

test("preparation exposes balanced defaults, storage, and bounded resource choices", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));

  expect(await screen.findByRole("heading", { name: "Speed", level: 1 })).toBeInTheDocument();
  await user.click(screen.getByText("Advanced controls"));
  expect(screen.getByRole("radio", { name: "Recommended optimizations" })).toBeChecked();
  await user.click(screen.getByRole("radio", { name: "Conservative optimizations" }));
  expect(screen.getByRole("radio", { name: "Conservative optimizations" })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "Use Preflight optimizations" }).closest("label"))
    .toHaveTextContent("Compatibility");
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: /Prepared textures/ })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: /Prepared audio/ })).toBeChecked();
  await user.click(screen.getByRole("checkbox", { name: /Prepared audio/ }));
  expect(screen.getByRole("checkbox", { name: /Prepared audio/ })).not.toBeChecked();
  expect(window.localStorage.getItem("preflight.disabledOptimizationDomains"))
    .toBe('["prepared-audio"]');
  expect(screen.getAllByText("3.50 GB").length).toBeGreaterThan(0);
  expect(screen.queryByText("Free space needed to prepare")).not.toBeInTheDocument();
  expect(screen.queryByText("Finished data uses much less.")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Medium4 workers/ })).toBeEnabled();
  expect(screen.queryByRole("button", { name: "Prepare current profile" })).not.toBeInTheDocument();
  const storageInfo = screen.getByRole("button", { name: "About Preflight storage" });
  expect(document.getElementById(storageInfo.getAttribute("aria-describedby") ?? "")).toHaveAttribute("role", "tooltip");
});

test("advanced domain selections are validated on restore and reach the typed launch bridge", async () => {
  window.localStorage.setItem(
    "preflight.disabledOptimizationDomains",
    '["prepared-textures","unknown","prepared-textures"]',
  );
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });

  render(<App />);

  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalledWith(
    "/Applications/Starsector",
    "recommended",
    ["prepared-textures"],
    "minimize",
  ));
  expect(window.localStorage.getItem("preflight.disabledOptimizationDomains"))
    .toBe('["prepared-textures"]');

  game.mockRestore();
});

test("after-launch behavior defaults to minimize and remains an explicit setting", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });

  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Settings" }));
  const behavior = await screen.findByRole("combobox", { name: "Preflight window" });
  expect(behavior).toHaveValue("minimize");
  await user.selectOptions(behavior, "keep");
  await user.click(screen.getByRole("button", { name: "Home" }));
  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalledWith(
    "/Applications/Starsector",
    "recommended",
    [],
    "keep",
  ));
  expect(window.localStorage.getItem("preflight.afterLaunchBehavior")).toBe("keep");
  game.mockRestore();
});

test("the Hangar keeps the ship central and its compact customization local to that hull", async () => {
  const user = userEvent.setup();
  render(<App />);

  await user.click(await screen.findByRole("button", { name: "Hangar" }));
  expect(screen.queryByLabelText("186h played across 78 recorded sessions")).not.toBeInTheDocument();
  expect(screen.queryByText("Longest")).not.toBeInTheDocument();
  expect(screen.queryByText("Featured hulls")).not.toBeInTheDocument();
  const ship = screen.getByRole("combobox", { name: "Display ship" });
  expect(ship).toHaveValue("Odyssey");

  await user.click(ship);
  const ships = screen.getByRole("listbox", { name: "Display ships" });
  for (const name of ["Odyssey", "Onslaught", "Conquest", "Paragon", "Astral", "Hammerhead"]) {
    expect(within(ships).getByRole("option", { name })).toBeInTheDocument();
  }
  expect(within(ships).queryByRole("option", { name: "Preflight courier" })).not.toBeInTheDocument();

  await user.clear(ship);
  await user.type(ship, "para");
  expect(within(screen.getByRole("listbox", { name: "Display ships" }))
    .getByRole("option", { name: "Paragon" })).toBeInTheDocument();
  await user.keyboard("{Escape}");
  expect(ship).toHaveValue("Odyssey");

  const height = screen.getByRole("slider", { name: "Depth" });
  fireEvent.change(height, { target: { value: "1.35" } });
  expect(screen.getByRole("button", { name: "Reset appearance" })).toBeEnabled();
  await waitFor(() => expect(JSON.parse(window.localStorage.getItem("preflight.instrumentHullTuning.v1") ?? "{}")["/Applications/Starsector::odyssey"].height).toBe(1.35));

  const detail = screen.getByRole("slider", { name: "Detail" });
  fireEvent.change(detail, { target: { value: "0.04" } });
  await waitFor(() => {
    const saved = JSON.parse(window.localStorage.getItem("preflight.instrumentHullTuning.v1") ?? "{}");
    expect(saved["/Applications/Starsector::odyssey"].outerDetail).toBe(0.04);
    expect(saved["/Applications/Starsector::odyssey"].innerDetail).toBe(0.04);
  });

  await user.clear(ship);
  await user.type(ship, "Onslaught");
  await user.keyboard("{Enter}");
  await waitFor(() => expect(ship).toHaveValue("Onslaught"));
  expect(screen.getByRole("button", { name: "Reset appearance" })).toBeDisabled();

  await user.clear(ship);
  await user.type(ship, "Odyssey");
  await user.keyboard("{Enter}");
  await waitFor(() => expect(ship).toHaveValue("Odyssey"));
  expect(screen.getByRole("slider", { name: "Depth" })).toHaveValue("1.35");
  expect(screen.getByRole("slider", { name: "Detail" })).toHaveValue("0.04");
});

test("the desktop sidebar collapses to icon navigation without losing its destinations", async () => {
  const user = userEvent.setup();
  render(<App />);

  const collapse = await screen.findByRole("button", { name: "Collapse sidebar" });
  await user.click(collapse);
  expect(screen.getByRole("button", { name: "Expand sidebar" })).toHaveAttribute("aria-expanded", "false");
  expect(window.localStorage.getItem("preflight.sidebar")).toBe("collapsed");
  expect(screen.getByRole("button", { name: "Home" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Hangar" })).toBeEnabled();
});

test("a restored running window offers graceful stop before force stop", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  const stop = vi.spyOn(bridge, "stopGame")
    .mockResolvedValueOnce({ inspected: 1, stopped: 0, stillRunning: 1, skipped: 0, forced: false })
    .mockResolvedValueOnce({ inspected: 1, stopped: 1, stillRunning: 0, skipped: 0, forced: true });

  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await user.click(await screen.findByRole("button", { name: "Stop Starsector" }));
  expect(await screen.findByRole("button", { name: "Force stop Starsector" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "Force stop Starsector" }));
  await waitFor(() => expect(stop).toHaveBeenNthCalledWith(1, false));
  await waitFor(() => expect(stop).toHaveBeenNthCalledWith(2, true));
  game.mockRestore();
  stop.mockRestore();
});

test("the running preview keeps next-launch drafts editable while blocking the write", async () => {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/?scenario=running");

  render(<App />);

  expect(await screen.findByRole("button", { name: "Stop Starsector" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "Options" }));
  expect(await screen.findByRole("combobox", { name: "Home resolution" })).toBeEnabled();
  expect(screen.getByRole("spinbutton", { name: "Home battle size" })).toBeEnabled();
  expect(screen.getByRole("combobox", { name: "Home game memory" })).toBeEnabled();
  expect(screen.getByRole("combobox", { name: "Home antialiasing" })).toBeEnabled();
  expect(screen.getByRole("combobox", { name: "Home UI size" })).toBeEnabled();
  expect(screen.getByRole("checkbox", { name: "Home fullscreen" })).toBeEnabled();
  const sound = screen.getByRole("checkbox", { name: "Home sound" });
  expect(sound).toBeEnabled();
  expect(screen.getByRole("button", { name: /All settings/ })).toBeEnabled();

  await user.click(sound);
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeDisabled();
  expect(screen.getByText("Changes can be applied after Starsector closes.")).toBeInTheDocument();
});

test("storage totals disclose data outside the active cache categories", async () => {
  const user = userEvent.setup();
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cacheSnapshot({
    total: { bytes: 1536, files: 4 },
    uncategorizedBytes: 512,
  }));

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(screen.getByText("Storage details"));

  expect(await screen.findByText("Other Preflight data")).toBeInTheDocument();
  expect(screen.getByText("512 B")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "About Preflight storage" })).toBeInTheDocument();
  cache.mockRestore();
});

test("cache cleanup is previewed before unused artifacts are removed", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Review cleanup" }));

  expect(await screen.findByRole("heading", { name: "Free 4.72 GB?" })).toBeInTheDocument();
  expect(screen.getByText("Cleanup review")).toBeInTheDocument();
  const apply = screen.getByRole("button", { name: "Free 4.72 GB" });
  expect(apply).toBeEnabled();
  await user.click(apply);
  expect(await screen.findByText(/Freed 4.72 GB across 11,760 old files/)).toBeInTheDocument();
});

test("a benchmark cannot be queued behind an active cleanup", async () => {
  const user = userEvent.setup();
  const pending = deferred<Awaited<ReturnType<typeof bridge.applyCacheCleanup>>>();
  const cleanup = vi.spyOn(bridge, "applyCacheCleanup").mockReturnValue(pending.promise);
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Review cleanup" }));
  await user.click(await screen.findByRole("button", { name: "Free 4.72 GB" }));
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Measure speed" }));

  expect(await screen.findByRole("button", { name: "Run benchmark" })).toBeDisabled();
  cleanup.mockRestore();
});

test("launch settings mirror vanilla display and battle controls", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.click(screen.getByRole("button", { name: "All settings" }));

  expect(await screen.findByRole("heading", { name: "Game settings", level: 1 })).toBeInTheDocument();
  expect(screen.getByLabelText("Resolution")).toHaveValue("1440x932");
  expect(screen.getByLabelText("Fullscreen")).not.toBeChecked();
  expect(screen.getByLabelText("Sound")).toBeChecked();
  expect(screen.getByLabelText("Antialiasing")).toHaveValue("0");
  expect(screen.getByLabelText("UI size")).toHaveValue("1");
  expect(screen.getByLabelText("Battle size")).toHaveValue("400");
  expect(screen.getByLabelText("Battle size")).toHaveAttribute("max", "2000");
  expect(screen.getByLabelText("Game memory")).toHaveValue("6144");
  await user.selectOptions(screen.getByLabelText("Game memory"), "8192");
  const apply = screen.getByRole("button", { name: "Apply changes" });
  expect(apply).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/ }));
  await user.click(apply);
  expect(await screen.findByText(/Global game settings applied and verified/)).toBeInTheDocument();
});

test("profiles are preview-first and show the exact switch before applying", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Mods" }));

  expect(await screen.findByRole("heading", { name: "Mods", level: 1 })).toBeInTheDocument();
  expect(screen.getByText("Main campaign")).toBeInTheDocument();
  expect(screen.getByText("Active")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Current" })).not.toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Switch…" }));
  expect(await screen.findByRole("heading", { name: "Switch to Utilities only?" })).toBeInTheDocument();
  expect(screen.getByText("Enable (1)")).toBeInTheDocument();
  expect(screen.getByText("Disable (2)")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Apply switch" }));

  expect(await screen.findByText(/Switched to “Utilities only”/)).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Switch to Utilities only?" })).not.toBeInTheDocument();
});

test("profile work does not dim unrelated launcher settings", async () => {
  const user = userEvent.setup();
  const pending = deferred<Awaited<ReturnType<typeof bridge.activateProfile>>>();
  const activateNormally = bridge.activateProfile;
  const activate = vi.spyOn(bridge, "activateProfile").mockImplementation((game, name, confirmed) => (
    confirmed ? pending.promise : activateNormally(game, name, false)
  ));

  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Mods" }));
  await user.click(await screen.findByRole("button", { name: "Switch…" }));
  await user.click(await screen.findByRole("button", { name: "Apply switch" }));
  await user.click(screen.getByRole("button", { name: "Home" }));
  await user.click(await screen.findByRole("button", { name: "Options" }));

  expect(await screen.findByRole("checkbox", { name: "Home sound" })).toBeEnabled();
  expect(screen.getByRole("combobox", { name: "Home resolution" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Launch Starsector" })).toBeDisabled();

  pending.resolve(await activateNormally("/Applications/Starsector", "Utilities only", true));
  await waitFor(() => expect(activate).toHaveBeenLastCalledWith(
    "/Applications/Starsector",
    "Utilities only",
    true,
  ));
  activate.mockRestore();
});

test("a profile with missing mods explains the problem without showing a dead switch action", async () => {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/?scenario=profile-mismatch");
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Mods" }));
  expect(await screen.findByText("Missing: graphicslib")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Switch…" })).not.toBeInTheDocument();
});

test("named profiles can be renamed or deleted only after an exact review", async () => {
  const user = userEvent.setup();
  const rename = vi.spyOn(bridge, "renameProfile");
  const remove = vi.spyOn(bridge, "deleteProfile");
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Mods" }));
  const mainCampaign = (await screen.findByText("Main campaign")).closest("article");
  expect(mainCampaign).not.toBeNull();
  if (!mainCampaign) return;

  await user.click(within(mainCampaign).getByText("Manage"));
  await user.click(within(mainCampaign).getByRole("button", { name: "Rename" }));
  const renameInput = screen.getByRole("textbox", { name: "Rename Main campaign" });
  await user.clear(renameInput);
  await user.type(renameInput, "Long campaign");
  await user.click(screen.getByRole("button", { name: "Review rename" }));

  expect(await screen.findByRole("heading", { name: "Rename Main campaign to Long campaign?" })).toBeInTheDocument();
  expect(screen.getByText(/mod list and prepared data stay unchanged/)).toBeInTheDocument();
  expect(rename).toHaveBeenCalledWith(
    "/Applications/Starsector",
    "Main campaign",
    "Long campaign",
    null,
    false,
  );
  await user.click(screen.getByRole("button", { name: "Rename profile" }));
  await waitFor(() => expect(rename).toHaveBeenLastCalledWith(
    "/Applications/Starsector",
    "Main campaign",
    "Long campaign",
    "preview-profile",
    true,
  ));
  expect(await screen.findByText("Renamed “Main campaign” to “Long campaign”.")).toBeInTheDocument();

  await user.click(within(mainCampaign).getByText("Manage"));
  await user.click(within(mainCampaign).getByRole("button", { name: "Delete" }));
  expect(await screen.findByRole("heading", { name: "Delete Main campaign?" })).toBeInTheDocument();
  expect(screen.getByText(/will not disable any mods/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Delete profile" }));
  await waitFor(() => expect(remove).toHaveBeenLastCalledWith(
    "/Applications/Starsector",
    "Main campaign",
    "preview-profile",
    true,
  ));
  expect(await screen.findByText("Deleted “Main campaign”. Its prepared data was kept.")).toBeInTheDocument();

  rename.mockRestore();
  remove.mockRestore();
});

test("diagnostics disclose their boundary and export a bounded bundle", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));

  expect(await screen.findByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  await user.click(screen.getByText("What’s inside?"));
  expect(screen.getByText("Run details")).toBeInTheDocument();
  expect(screen.getByText("Your game and data")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Make a support file" }));

  expect(await screen.findByText("Support file ready")).toBeInTheDocument();
  expect(screen.getByText(/Saved 14 disclosed files/)).toBeInTheDocument();
  await user.click(await screen.findByRole("button", { name: "Review and send" }));

  expect(await screen.findByRole("heading", { name: "Send this exact file?" })).toBeInTheDocument();
  expect(screen.getByText("4bd6db450a131978b8f8b79d5f08d6e75670ba7e75288bb50f9a742a6d996d8d")).toBeInTheDocument();
  expect(screen.getByText("Included entries (3)")).toBeInTheDocument();
  expect(screen.getByText("runs/1/run.json")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Send this exact file" }));

  expect(await screen.findByRole("heading", { name: /Case ed6ca0c8/ })).toBeInTheDocument();
  expect(screen.getByText(/was accepted/)).toBeInTheDocument();
  await waitFor(() => expect(window.localStorage.getItem("preflight.reportReceipt")).not.toBeNull());
  await user.click(screen.getByRole("button", { name: "Delete uploaded report" }));
  expect(await screen.findByText(/was deleted/)).toBeInTheDocument();
  await waitFor(() => expect(window.localStorage.getItem("preflight.reportReceipt")).toBeNull());
});

test("restores an unexpired report deletion receipt after restart", async () => {
  const user = userEvent.setup();
  const caseId = "ed6ca0c8-0417-45e5-864f-557680b00590";
  window.localStorage.setItem("preflight.reportReceipt", JSON.stringify({
    protocolVersion: 1,
    caseId,
    objectKey: `accepted/${caseId}.zip`,
    bytes: 197_368,
    sha256: "558766c179e293418d406b525613af435129673f519d9c26a093fa71f5d12260",
    productVersion: "0.1.0",
    receivedAt: new Date(Date.now() - 60_000).toISOString(),
    retentionDeadline: new Date(Date.now() + 86_400_000).toISOString(),
    deletion: {
      method: "DELETE",
      url: `https://reports.preview.invalid/v1/cases/${caseId}`,
      token: "preview.deletion",
    },
    signature: "preview-signature",
  }));

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));

  expect(await screen.findByRole("heading", { name: `Case ${caseId}` })).toBeInTheDocument();
  expect(screen.getByText(/The deletion receipt stays here/)).toBeInTheDocument();
  expect(screen.getByText(/Use this case number in an issue/)).toBeInTheDocument();
});

test("discards an expired local report deletion receipt", async () => {
  const caseId = "ed6ca0c8-0417-45e5-864f-557680b00590";
  window.localStorage.setItem("preflight.reportReceipt", JSON.stringify({
    protocolVersion: 1,
    caseId,
    objectKey: `accepted/${caseId}.zip`,
    bytes: 197_368,
    sha256: "558766c179e293418d406b525613af435129673f519d9c26a093fa71f5d12260",
    productVersion: "0.1.0",
    receivedAt: new Date(Date.now() - 120_000).toISOString(),
    retentionDeadline: new Date(Date.now() - 60_000).toISOString(),
    deletion: {
      method: "DELETE",
      url: `https://reports.preview.invalid/v1/cases/${caseId}`,
      token: "preview.deletion",
    },
    signature: "preview-signature",
  }));

  render(<App />);

  await screen.findByText("Ready");
  expect(window.localStorage.getItem("preflight.reportReceipt")).toBeNull();
  expect(screen.queryByRole("heading", { name: `Case ${caseId}` })).not.toBeInTheDocument();
});

test("an unconfigured build keeps local export available and refuses report sending", async () => {
  const user = userEvent.setup();
  const intake = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Run-report sending isn't configured in this build.",
  });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Make a support file" }));

  expect(await screen.findByText(/Run-report sending isn't configured/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Review and send" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Make another one" })).toBeEnabled();
  intake.mockRestore();
});

test("a failed report send keeps one recovery alert and the local ZIP", async () => {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/?scenario=report-error");
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Make a support file" }));
  await user.click(await screen.findByRole("button", { name: "Review and send" }));
  await user.click(screen.getByRole("button", { name: "Send this exact file" }));

  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("It wasn’t sent");
  expect(alert).toHaveTextContent("preview report service");
  expect(alert).toHaveTextContent("still on this computer");
  expect(screen.getAllByText(/preview report service/)).toHaveLength(1);
  expect(screen.getByRole("button", { name: "Try sending again" })).toBeEnabled();
});

test("the benchmark checks its packaged startup contract and launches without a review step", async () => {
  const user = userEvent.setup();
  const probe = vi.spyOn(bridge, "getDesktopSmokeProbe");
  const smoke = vi.spyOn(bridge, "startDesktopSmoke").mockResolvedValue({ pid: 4244 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Measure speed" }));
  await user.click(await screen.findByRole("button", { name: "Run benchmark" }));

  expect(probe).toHaveBeenCalledOnce();
  await waitFor(() => expect(smoke).toHaveBeenCalledWith("/Applications/Starsector"));
  expect(await screen.findByText("Startup benchmark finished in browser preview.")).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Latest benchmark result" })).toBeInTheDocument();
  expect(screen.getByText("15.88s")).toBeInTheDocument();
  expect(screen.getByText("total prepared data")).toBeInTheDocument();
  probe.mockRestore();
  smoke.mockRestore();
});

test("a running benchmark exposes cooperative cancellation", async () => {
  const user = userEvent.setup();
  const smoke = vi.spyOn(bridge, "startDesktopSmoke").mockReturnValue(new Promise(() => {}));
  const cancel = vi.spyOn(bridge, "cancelDesktopSmoke").mockResolvedValue(true);
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Measure speed" }));
  await user.click(await screen.findByRole("button", { name: "Run benchmark" }));
  await user.click(screen.getByRole("button", { name: "Help" }));
  expect(await screen.findByRole("button", { name: "Make a support file" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Open Benchmark" }));
  await user.click(await screen.findByRole("button", { name: "Stop benchmark" }));

  expect(cancel).toHaveBeenCalledOnce();
  expect(screen.getByText("Stopping the exact game process and sealing its evidence…")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Stopping…" })).toBeDisabled();
  smoke.mockRestore();
  cancel.mockRestore();
});

test("an unavailable startup benchmark reports the packaged-contract failure without permissions", async () => {
  const user = userEvent.setup();
  const smoke = vi.spyOn(bridge, "startDesktopSmoke");
  const probe = vi.spyOn(bridge, "getDesktopSmokeProbe").mockResolvedValue({
    protocol: 1,
    probe: {
      ready: false,
      driver: null,
      diagnostics: ["A packaged startup benchmark scenario is missing."],
    },
  });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  await user.click(await screen.findByRole("button", { name: "Measure speed" }));
  await user.click(await screen.findByRole("button", { name: "Run benchmark" }));

  expect(await screen.findByText("This build can’t run the startup benchmark. Reinstall Preflight, or open Help to make a support file.")).toBeInTheDocument();
  expect(screen.queryByText("A packaged startup benchmark scenario is missing.")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Check again" })).toBeEnabled();
  expect(screen.queryByRole("button", { name: /Accessibility/i })).not.toBeInTheDocument();
  expect(smoke).not.toHaveBeenCalled();
  probe.mockRestore();
  smoke.mockRestore();
});

/*
 * The measurement costs several minutes of the machine to itself, and it used to be discarded the
 * moment the app closed. Keeping it is the whole point of having taken it.
 */
test("a measured benchmark becomes the scoreboard and survives reopening Preflight", async () => {
  const user = userEvent.setup();
  const first = render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));
  expect(await screen.findByText("Your startup")).toBeInTheDocument();
  expect(screen.getByLabelText("186h recorded playtime across 78 sessions")).toBeInTheDocument();

  await user.click(await screen.findByRole("button", { name: "Measure speed" }));
  await user.click(await screen.findByRole("button", { name: "Run benchmark" }));
  await screen.findByText("Startup benchmark finished in browser preview.");
  await user.click(screen.getByRole("button", { name: "Speed" }));

  expect(await screen.findByText("Personal best")).toBeInTheDocument();
  expect(screen.getByText("82%")).toBeInTheDocument();
  expect(screen.getByText("Latest benchmark: Faster")).toBeInTheDocument();
  expect(screen.getByText(/82\.0% less startup time/)).toBeInTheDocument();
  expect(screen.getByLabelText("186h recorded playtime across 78 sessions")).toBeInTheDocument();
  first.unmount();

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));

  expect(await screen.findByText("Personal best")).toBeInTheDocument();
  expect(screen.getByText("Latest benchmark: Faster")).toBeInTheDocument();
  expect(screen.queryByText(/saved per launch/)).not.toBeInTheDocument();
});

test("an unmeasured scoreboard labels the exact last process-to-menu time", async () => {
  const user = userEvent.setup();
  const base = await bridge.getSnapshot();
  const snapshot = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue({
    ...base,
    lastRun: {
      directory: "~/.starsector-preflight/runs/latest",
      modifiedAt: "2026-08-17T00:00:00Z",
      installRoot: base.selected?.installRoot,
      profileFingerprint: "preview-profile",
      adapterHealth: null,
      started: "2026-08-17T00:00:00Z",
      ended: "2026-08-17T02:00:00Z",
      startupMillis: 15_250,
      outcome: "COMPLETED",
      exitCode: 0,
    },
  });

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Speed" }));

  expect(await screen.findByText("Last Preflight launch: 15.3s to the menu.")).toBeInTheDocument();
  expect(screen.queryByText(/2h/)).not.toBeInTheDocument();
  snapshot.mockRestore();
});

test("verified updates are explicit and explain when a build has no update channel", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(await screen.findByRole("button", { name: "Check for updates" }));

  expect(await screen.findAllByText("Browser preview has no verified update channel.")).toHaveLength(2);
  expect(screen.queryByRole("button", { name: "Install and restart" })).not.toBeInTheDocument();
});

test("help remains a permanent primary route", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));

  expect(await screen.findByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Make a support file" })).toBeVisible();
});

/*
 * A support file is what the maintainer wants; it is not what somebody whose game will not start
 * came here for. Each fix on this page has to land on the control that performs it, or the page
 * is a list of advice with no way to take it.
 */
test("help performs its fixes instead of only pointing at other pages", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await screen.findByRole("heading", { name: "Try this first", level: 2 });

  await user.click(screen.getByRole("button", { name: "Turn off" }));
  expect(screen.getByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  expect(screen.getByText("Optimizations are off. Your prepared data is still there.")).toBeVisible();
  expect(screen.getByRole("button", { name: "Go to launch" })).toBeEnabled();
  await waitFor(() => expect(window.localStorage.getItem("preflight.optimizationPreset")).toBe("off"));

  await user.click(screen.getByRole("button", { name: "Go to launch" }));
  expect(await screen.findByRole("heading", { name: "Ready", level: 1 })).toBeInTheDocument();
  expect(screen.getByText("Optimizations off")).toBeVisible();

  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Run benchmark" }));
  expect(await screen.findByRole("heading", { name: "Benchmark", level: 1 })).toBeInTheDocument();
});

test("cancelling an installation change stays in Help and a valid change returns Home", async () => {
  const user = userEvent.setup();
  const initialSnapshot = await bridge.getBootstrapSnapshot();
  const desktopHost = vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  const nativeListen = vi.mocked(listen).mockResolvedValue(() => undefined);
  const cancelledPicker = deferred<string | null>();
  const folderPicker = vi.mocked(open)
    .mockReturnValueOnce(cancelledPicker.promise)
    .mockResolvedValueOnce("/Applications/Starsector");
  const snapshot = vi.spyOn(bridge, "getBootstrapSnapshot").mockResolvedValue(initialSnapshot);
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Choose folder" }));
  await waitFor(() => expect(folderPicker).toHaveBeenCalledTimes(1));
  expect(screen.getByRole("button", { name: "Choose folder" })).toBeDisabled();
  cancelledPicker.resolve(null);
  await waitFor(() => expect(screen.getByRole("button", { name: "Choose folder" })).toBeEnabled());
  expect(screen.getByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Choose folder" }));
  expect(await screen.findByRole("heading", { name: "Ready", level: 1 })).toBeInTheDocument();
  await waitFor(() => expect(snapshot).toHaveBeenCalledWith("/Applications/Starsector"));

  desktopHost.mockRestore();
  nativeListen.mockReset();
  folderPicker.mockReset();
  snapshot.mockRestore();
});

test("a folder-picker failure stays in context and explains the failure", async () => {
  const user = userEvent.setup();
  const desktopHost = vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  const nativeListen = vi.mocked(listen).mockResolvedValue(() => undefined);
  const folderPicker = vi.mocked(open).mockRejectedValue(new Error("picker unavailable"));
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Choose folder" }));

  expect(await screen.findByText(/Couldn’t open the folder picker.*picker unavailable/)).toBeVisible();
  expect(screen.getByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Choose folder" })).toBeEnabled();

  desktopHost.mockRestore();
  nativeListen.mockReset();
  folderPicker.mockReset();
});

test("primary navigation marks only the current destination", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  const home = screen.getByRole("button", { name: "Home" });
  const help = screen.getByRole("button", { name: "Help" });
  expect(home).toHaveAttribute("aria-current", "page");
  expect(help).not.toHaveAttribute("aria-current");

  await user.click(help);
  expect(await screen.findByRole("heading", { name: "Help", level: 1 })).toBeInTheDocument();
  expect(help).toHaveAttribute("aria-current", "page");
  expect(home).not.toHaveAttribute("aria-current");
});

test("a verified available update still waits for install confirmation", async () => {
  const user = userEvent.setup();
  const install = vi.spyOn(bridge, "installUpdate").mockResolvedValue();
  const check = vi.spyOn(bridge, "checkForUpdate").mockResolvedValue({
    format: "preflight-update-v1",
    configured: true,
    currentVersion: "0.1.0",
    available: true,
    version: "0.2.0",
    date: "2026-08-07T00:00:00Z",
    notes: "A signed test release.",
    reason: null,
  });

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(await screen.findByRole("button", { name: "Check for updates" }));

  expect(await screen.findByRole("heading", { name: "Preflight 0.2.0" })).toBeInTheDocument();
  expect(screen.getByText("A signed test release.")).toBeInTheDocument();
  expect(screen.getByText(/previous copy is kept for rollback/)).toBeInTheDocument();
  expect(screen.getByText("Update")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Install and restart" })).toBeEnabled();
  expect(install).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: "Home" }));
  expect(screen.getByRole("region", { name: "Preflight update available" })).toHaveTextContent("Preflight 0.2.0 is available");
  await user.click(screen.getByRole("button", { name: "Review update" }));
  expect(await screen.findByRole("heading", { name: "Settings", level: 1 })).toBeInTheDocument();
  expect(install).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: "Install and restart" }));
  expect(install).toHaveBeenCalledWith("0.2.0");
  install.mockRestore();
  check.mockRestore();
});

test("a failed update keeps the verified offer available for retry", async () => {
  const user = userEvent.setup();
  const install = vi.spyOn(bridge, "installUpdate").mockRejectedValue(new Error("signature service unavailable"));
  const check = vi.spyOn(bridge, "checkForUpdate").mockResolvedValue({
    format: "preflight-update-v1",
    configured: true,
    currentVersion: "0.1.0",
    available: true,
    version: "0.2.0",
    date: "2026-08-07T00:00:00Z",
    notes: "A signed test release.",
    reason: null,
  });

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(await screen.findByRole("button", { name: "Check for updates" }));
  await user.click(await screen.findByRole("button", { name: "Install and restart" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("Update wasn’t installed. Preflight 0.1.0 is unchanged.");
  expect(screen.getByRole("heading", { name: "Preflight 0.2.0" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Install and restart" })).toBeEnabled();

  install.mockRestore();
  check.mockRestore();
});

test("removal keeps launcher files and all data as separate previewed scopes", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(screen.getByText("Remove Preflight"));

  const launcher = await screen.findByRole("button", { name: "Review" });
  const allData = screen.getByRole("button", { name: "Review deletion" });
  expect(launcher).toBeEnabled();
  expect(allData).toBeEnabled();

  await user.click(launcher);
  expect(await screen.findByRole("heading", { name: "Remove launch integration?" })).toBeInTheDocument();
  expect(screen.getByText(/Starsector, mods, saves, and game settings aren’t removal targets/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Cancel" }));

  await user.click(allData);
  expect(await screen.findByRole("heading", { name: "Remove all Preflight data?" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Remove all Preflight data" })).toBeEnabled();
});

test("a reviewed removal cannot be applied while the game is running", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockImplementation(() => new Promise(() => undefined));
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(screen.getByText("Remove Preflight"));
  await user.click(await screen.findByRole("button", { name: "Review deletion" }));
  expect(await screen.findByRole("button", { name: "Remove all Preflight data" })).toBeEnabled();

  await user.click(screen.getByRole("button", { name: "Home" }));
  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalled());
  await user.click(screen.getByRole("button", { name: "Settings" }));
  const removalReview = await screen.findByRole("region", { name: "Removal review" });
  expect(within(removalReview).getByRole("button", { name: "Remove all Preflight data" })).toBeDisabled();
  expect(within(removalReview).getByText("Close Starsector before removing Preflight data.")).toBeVisible();
  game.mockRestore();
});

/**
 * Settings tells a wary player what this build sends. A build with no intake origin compiled in
 * cannot send a report at all, and describing the send flow there anyway would advertise a feature
 * it doesn't have while understating a privacy position that is stronger, not weaker.
 */
test("the privacy panel describes the build's actual sending ability", async () => {
  const user = userEvent.setup();
  const unconfigured = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Run-report sending isn't configured in this build.",
  });

  const { unmount } = render(<App />);
  await user.click(await screen.findByRole("button", { name: "Settings" }));
  expect(await screen.findByText(/Support ZIPs stay here until you share one/))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "What Preflight can access" })).toBeEnabled();
  expect(screen.queryByText(/sent only when you press Send/)).not.toBeInTheDocument();
  unmount();

  unconfigured.mockResolvedValue({ configured: true, origin: "https://reports.invalid", reason: null });
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Settings" }));
  expect(await screen.findByText(/sent only when you press Send/)).toBeInTheDocument();

  unconfigured.mockRestore();
});
