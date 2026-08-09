import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { isCurrentProfilePrepared } from "./App";
import * as bridge from "./bridge";
import type { CacheHealth, CacheSnapshot, LaunchSettings } from "./types";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn(), save: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

beforeEach(() => {
  window.localStorage.clear();
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
  expect(screen.getByText(/must be free/)).toBeInTheDocument();
  await user.click(action);
  await waitFor(() => expect(preparation).toHaveBeenCalledWith("/Applications/Starsector", "balanced", 4, 256));
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", []));

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
  await user.click(screen.getAllByRole("button", { name: "Repair and launch" })[0]);
  await waitFor(() => expect(repair).toHaveBeenCalledWith("/Applications/Starsector", "preview-profile"));
  await waitFor(() => expect(preparation).toHaveBeenCalledWith("/Applications/Starsector", "balanced", 4, 256));
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", []));

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
  expect(screen.getByText(/Starsector will open automatically/)).toBeInTheDocument();
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

test("a cold profile cannot prepare when the conservative disk bound does not fit", async () => {
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

  const action = await screen.findByRole("button", { name: "Review storage" });
  await screen.findByText(reason);
  expect(action).toBeEnabled();
  expect(preparation).not.toHaveBeenCalled();
  await userEvent.setup().click(action);
  expect(await screen.findByRole("heading", { name: "Preflight", level: 1 })).toBeInTheDocument();

  cache.mockRestore();
  plan.mockRestore();
  preparation.mockRestore();
});

test("shows a useful ready-state home screen in browser preview", async () => {
  render(<App />);

  expect(await screen.findByText("Ready")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getAllByText("Launch Starsector")).toHaveLength(1);
  expect(screen.queryByRole("button", { name: "Choose another" })).not.toBeInTheDocument();
  expect(screen.queryByLabelText("Active profile")).not.toBeInTheDocument();
  expect(screen.getByText("Named by you · 83 mods")).toBeInTheDocument();
  expect(screen.getByText("Recommended")).toBeInTheDocument();
  expect(screen.getByText(/Prepared ·/)).toBeInTheDocument();
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

test("blocks installation and preparation mutations while the game is running", async () => {
  const user = userEvent.setup();
  const game = vi.spyOn(bridge, "startGame").mockImplementation(() => new Promise(() => undefined));
  render(<App />);

  await user.click(await screen.findByRole("button", { name: "Launch Starsector" }));
  await waitFor(() => expect(game).toHaveBeenCalled());
  expect(screen.getByRole("button", { name: "Refresh installation status" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Change Starsector installation" })).toBeDisabled();

  await user.click(screen.getByRole("button", { name: "Preflight" }));
  expect(await screen.findByRole("radio", { name: "Recommended optimizations" })).toBeDisabled();
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeDisabled();
  expect(screen.getByRole("button", { name: /Medium4 workers/ })).toBeDisabled();
  expect(screen.getByRole("button", { name: /Calculating|Prepare current profile/ })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Review cleanup" })).toBeDisabled();

  game.mockRestore();
});

test("page navigation resets the viewport that actually owns desktop scrolling", async () => {
  const user = userEvent.setup();
  const { container } = render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Run reports" }));
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

test("keyboard users can skip navigation and receive the new workspace heading", async () => {
  const user = userEvent.setup();
  const focus = vi.spyOn(HTMLElement.prototype, "focus");
  render(<App />);

  await screen.findByText("Ready");
  await user.tab();
  expect(screen.getByRole("link", { name: "Skip to workspace" })).toHaveFocus();

  await user.click(screen.getByRole("button", { name: "Run reports" }));
  const heading = await screen.findByRole("heading", { name: "Run reports", level: 1 });
  expect(heading).toHaveFocus();
  expect(focus).toHaveBeenCalledWith({ preventScroll: true });
  expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
  focus.mockRestore();
});

test("common game settings are editable beside launch", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  expect(await screen.findByLabelText("Home resolution")).toHaveValue("1440x932");
  expect(screen.getByLabelText("Home antialiasing")).toHaveValue("0");
  expect(screen.getByLabelText("Home UI size")).toHaveValue("1");
  expect(screen.getByLabelText("Home battle size")).toHaveValue(400);
  const apply = screen.getByRole("button", { name: "Settings applied" });
  expect(apply).toBeDisabled();

  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "1200");
  expect(screen.getByText(/vanilla settings slider ends at 400/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "Apply changes" }));

  expect(await screen.findByText(/Game settings saved/)).toBeInTheDocument();
});

test("the primary action saves edited game settings before launching", async () => {
  const user = userEvent.setup();
  const baseline = await bridge.getLaunchSettings("/Applications/Starsector");
  const pending = deferred<LaunchSettings>();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockImplementation(() => pending.promise);
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(update).toHaveBeenCalledWith("/Applications/Starsector", expect.objectContaining({ battleSize: 300 }));
  expect(game).not.toHaveBeenCalled();
  pending.resolve({
    ...baseline,
    preferences: { ...baseline.preferences, battleSize: 300 },
  });
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", []));
  update.mockRestore();
  game.mockRestore();
});

test("the primary action does not launch when edited game settings fail to save", async () => {
  const user = userEvent.setup();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockRejectedValue(new Error("settings write refused"));
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(await screen.findByText("Error: settings write refused")).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("settings write refused");
  expect(game).not.toHaveBeenCalled();
  update.mockRestore();
  game.mockRestore();
});

test("navigation resets the previous workflow scroll position", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  document.documentElement.scrollTop = 240;
  document.body.scrollTop = 240;
  await user.click(screen.getByRole("button", { name: "Profiles" }));

  await waitFor(() => {
    expect(document.documentElement.scrollTop).toBe(0);
    expect(document.body.scrollTop).toBe(0);
  });
});

test("preparation exposes balanced defaults, storage, and bounded resource choices", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Storage" }));

  expect(await screen.findByRole("heading", { name: "Preflight", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: "Recommended optimizations" })).toBeChecked();
  await user.click(screen.getByRole("radio", { name: "Conservative optimizations" }));
  expect(screen.getByRole("radio", { name: "Conservative optimizations" })).toBeChecked();
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: /Prepared textures/ })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: /Prepared audio/ })).toBeChecked();
  await user.click(screen.getByRole("checkbox", { name: /Prepared audio/ }));
  expect(screen.getByRole("checkbox", { name: /Prepared audio/ })).not.toBeChecked();
  expect(window.localStorage.getItem("preflight.disabledOptimizationDomains"))
    .toBe('["prepared-audio"]');
  expect(screen.getByText("4.50 GB")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Medium4 workers/ })).toBeEnabled();
  expect(await screen.findByRole("button", { name: "Prepare current profile" })).toBeEnabled();
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
  ));
  expect(window.localStorage.getItem("preflight.disabledOptimizationDomains"))
    .toBe('["prepared-textures"]');

  game.mockRestore();
});

test("storage totals disclose data outside the active cache categories", async () => {
  const user = userEvent.setup();
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cacheSnapshot({
    total: { bytes: 1536, files: 4 },
    uncategorizedBytes: 512,
  }));

  render(<App />);
  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Storage" }));

  expect(await screen.findByText("Other Preflight data")).toBeInTheDocument();
  expect(screen.getByText("512 B")).toBeInTheDocument();
  expect(screen.getByText(/Other includes retained cache formats/)).toBeInTheDocument();
  cache.mockRestore();
});

test("cache cleanup is previewed before unused artifacts are removed", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Storage" }));
  await user.click(await screen.findByRole("button", { name: "Review cleanup" }));

  expect(await screen.findByRole("heading", { name: "Free 1.72 GB?" })).toBeInTheDocument();
  expect(screen.getByText("Cleanup review")).toBeInTheDocument();
  const apply = screen.getByRole("button", { name: "Remove 8,914 files" });
  expect(apply).toBeEnabled();
  await user.click(apply);
  expect(await screen.findByText(/Freed 1.72 GB across 8,914 unused files/)).toBeInTheDocument();
});

test("launch settings mirror vanilla display and battle controls", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "All settings" }));

  expect(await screen.findByRole("heading", { name: "Game settings", level: 1 })).toBeInTheDocument();
  expect(screen.getByLabelText("Resolution")).toHaveValue("1440x932");
  expect(screen.getByLabelText("Fullscreen")).not.toBeChecked();
  expect(screen.getByLabelText("Sound")).toBeChecked();
  expect(screen.getByLabelText("Antialiasing")).toHaveValue("0");
  expect(screen.getByLabelText("UI size")).toHaveValue("1");
  expect(screen.getByLabelText("Deployment-point budget")).toHaveValue("400");
  expect(screen.getByLabelText("Deployment-point budget")).toHaveAttribute("max", "2000");
  expect(screen.getByLabelText("Game memory")).toHaveValue("6144");
  await user.selectOptions(screen.getByLabelText("Game memory"), "8192");
  await user.click(screen.getByRole("button", { name: "Save changes" }));
  expect(await screen.findByText(/Game settings saved/)).toBeInTheDocument();
});

test("profiles are preview-first and show the exact switch before applying", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  expect(await screen.findByText("Main campaign")).toBeInTheDocument();
  expect(screen.getByText("Named by you · 83 mods")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Manage profiles" }));

  expect(await screen.findByRole("heading", { name: "Profiles", level: 1 })).toBeInTheDocument();
  expect(screen.getByText("Main campaign")).toBeInTheDocument();
  expect(screen.getByText("Active")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Review switch" }));

  expect(await screen.findByRole("heading", { name: "Switch to Utilities only?" })).toBeInTheDocument();
  expect(screen.getByText("Enable (1)")).toBeInTheDocument();
  expect(screen.getByText("Disable (2)")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Apply switch" }));

  expect(await screen.findByText(/Switched to “Utilities only”/)).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Switch to Utilities only?" })).not.toBeInTheDocument();
});

test("diagnostics disclose their boundary and export a bounded bundle", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Run reports" }));

  expect(await screen.findByRole("heading", { name: "Run reports", level: 1 })).toBeInTheDocument();
  await user.click(screen.getByText("Diagnostic contents"));
  expect(screen.getByText("Useful metadata only")).toBeInTheDocument();
  expect(screen.getByText("Game and personal data")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Save diagnostics" }));

  expect(await screen.findByText("Diagnostics ready")).toBeInTheDocument();
  expect(screen.getByText(/Saved 14 disclosed files/)).toBeInTheDocument();
  await user.click(await screen.findByRole("button", { name: "Review send" }));

  expect(await screen.findByRole("heading", { name: "Send this exact ZIP?" })).toBeInTheDocument();
  expect(screen.getByText("4bd6db450a131978b8f8b79d5f08d6e75670ba7e75288bb50f9a742a6d996d8d")).toBeInTheDocument();
  expect(screen.getByText("Included entries (3)")).toBeInTheDocument();
  expect(screen.getByText("runs/1/run.json")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Send this exact ZIP" }));

  expect(await screen.findByRole("heading", { name: /Run report ed6ca0c8/ })).toBeInTheDocument();
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
  await user.click(screen.getByRole("button", { name: "Run reports" }));

  expect(await screen.findByRole("heading", { name: `Run report ${caseId}` })).toBeInTheDocument();
  expect(screen.getByText(/keeps this deletion receipt on this computer/)).toBeInTheDocument();
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
  expect(screen.queryByRole("heading", { name: `Run report ${caseId}` })).not.toBeInTheDocument();
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
  await user.click(screen.getByRole("button", { name: "Run reports" }));
  await user.click(await screen.findByRole("button", { name: "Save diagnostics" }));

  expect(await screen.findByText(/Run-report sending isn't configured/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Review send" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Save another ZIP" })).toBeEnabled();
  intake.mockRestore();
});

test("the automated game test checks readiness without launching", async () => {
  const user = userEvent.setup();
  const probe = vi.spyOn(bridge, "getDesktopSmokeProbe");
  const game = vi.spyOn(bridge, "startGame");
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Run reports" }));
  await user.click(screen.getByText("Automated game test"));
  await user.click(await screen.findByRole("button", { name: "Check readiness" }));

  expect(await screen.findByText(/Ready through browser-preview/)).toBeInTheDocument();
  expect(screen.getByText("launch · observe · screenshot · input · shutdown")).toBeInTheDocument();
  expect(probe).toHaveBeenCalledOnce();
  expect(game).not.toHaveBeenCalled();
  probe.mockRestore();
  game.mockRestore();
});

test("the automated game test requires a review before it starts", async () => {
  const user = userEvent.setup();
  const smoke = vi.spyOn(bridge, "startDesktopSmoke").mockResolvedValue({ pid: 4244 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Run reports" }));
  await user.click(screen.getByText("Automated game test"));
  await user.click(await screen.findByRole("button", { name: "Check readiness" }));
  await user.click(await screen.findByRole("button", { name: "Review test" }));

  expect(screen.getByText("Test review")).toBeInTheDocument();
  expect(smoke).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Start automated test" }));

  await waitFor(() => expect(smoke).toHaveBeenCalledWith("/Applications/Starsector"));
  expect(await screen.findByText("Automated game test passed in browser preview.")).toBeInTheDocument();
  smoke.mockRestore();
});

test("a running automated game test exposes cooperative cancellation", async () => {
  const user = userEvent.setup();
  const smoke = vi.spyOn(bridge, "startDesktopSmoke").mockReturnValue(new Promise(() => {}));
  const cancel = vi.spyOn(bridge, "cancelDesktopSmoke").mockResolvedValue(true);
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Run reports" }));
  await user.click(screen.getByText("Automated game test"));
  await user.click(await screen.findByRole("button", { name: "Check readiness" }));
  await user.click(await screen.findByRole("button", { name: "Review test" }));
  await user.click(screen.getByRole("button", { name: "Start automated test" }));
  await user.click(await screen.findByRole("button", { name: "Stop test safely" }));

  expect(cancel).toHaveBeenCalledOnce();
  expect(screen.getByText("Stopping the exact game process and sealing its evidence…")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Stopping test…" })).toBeDisabled();
  smoke.mockRestore();
  cancel.mockRestore();
});

test("a blocked macOS automation probe links to the manual permission pane", async () => {
  const user = userEvent.setup();
  const probe = vi.spyOn(bridge, "getDesktopSmokeProbe").mockResolvedValue({
    protocol: 1,
    probe: {
      ready: false,
      driver: null,
      diagnostics: ["macOS Accessibility permission isn't enabled for the automation executable: the Preflight application"],
    },
  });
  const settings = vi.spyOn(bridge, "openDesktopAccessibilitySettings").mockResolvedValue();
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Run reports" }));
  await user.click(screen.getByText("Automated game test"));
  await user.click(await screen.findByRole("button", { name: "Check readiness" }));
  await user.click(await screen.findByRole("button", { name: "Open Accessibility settings" }));

  expect(settings).toHaveBeenCalledOnce();
  expect(screen.queryByRole("button", { name: "Review test" })).not.toBeInTheDocument();
  probe.mockRestore();
  settings.mockRestore();
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
  expect(await screen.findByRole("button", { name: "Remove all Preflight data" })).toBeDisabled();
  game.mockRestore();
});
