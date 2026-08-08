import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { isCurrentProfilePrepared } from "./App";
import * as bridge from "./bridge";
import type { CacheSnapshot } from "./types";

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
    currentProfileFingerprint: "current-profile",
    profiles: [{
      fingerprint: "current-profile",
      current: true,
      bytes: 1024,
      indexBytes: 128,
      manifestBytes: 256,
      lastModifiedMillis: Date.now(),
    }],
    ...overrides,
  };
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
  await user.click(action);
  expect(preparation).toHaveBeenCalledWith("/Applications/Starsector", "balanced", 4, 256);
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended"));

  cache.mockRestore();
  preparation.mockRestore();
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

  const action = await screen.findByRole("button", { name: "Prepare and launch" });
  await screen.findByText(reason);
  expect(action).toBeDisabled();
  expect(preparation).not.toHaveBeenCalled();

  cache.mockRestore();
  plan.mockRestore();
  preparation.mockRestore();
});

test("shows a useful ready-state home screen in browser preview", async () => {
  render(<App />);

  expect(await screen.findByText("Ready to launch")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getByText("Recommended")).toBeInTheDocument();
  expect(screen.getByText(/Prepared ·/)).toBeInTheDocument();
});

test("preparation exposes balanced defaults, storage, and bounded resource choices", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Storage" }));

  expect(await screen.findByRole("heading", { name: "Prepare", level: 1 })).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeChecked();
  expect(screen.getByText("4.50 GB")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Balanced4 workers/ })).toBeEnabled();
  expect(await screen.findByRole("button", { name: "Prepare current profile" })).toBeEnabled();
});

test("storage totals disclose data outside the active cache categories", async () => {
  const user = userEvent.setup();
  const cache = vi.spyOn(bridge, "getCache").mockResolvedValue(cacheSnapshot({
    total: { bytes: 1536, files: 4 },
    uncategorizedBytes: 512,
  }));

  render(<App />);
  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Storage" }));

  expect(await screen.findByText("Other Preflight data")).toBeInTheDocument();
  expect(screen.getByText("512 B")).toBeInTheDocument();
  expect(screen.getByText(/Other includes retained cache formats/)).toBeInTheDocument();
  cache.mockRestore();
});

test("cache cleanup is previewed before unused artifacts are removed", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Storage" }));
  await user.click(await screen.findByRole("button", { name: "Review cleanup" }));

  expect(await screen.findByRole("heading", { name: "Free 1.72 GB?" })).toBeInTheDocument();
  expect(screen.getByText(/Nothing removed yet/)).toBeInTheDocument();
  const apply = screen.getByRole("button", { name: "Remove 8,914 files" });
  expect(apply).toBeEnabled();
  await user.click(apply);
  expect(await screen.findByText(/Freed 1.72 GB across 8,914 unused files/)).toBeInTheDocument();
});

test("launch settings mirror vanilla display and battle controls", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Game settings" }));

  expect(await screen.findByText("Launch settings")).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: "Recommended optimizations" })).toBeChecked();
  await user.click(screen.getByRole("radio", { name: "Conservative optimizations" }));
  expect(screen.getByRole("radio", { name: "Conservative optimizations" })).toBeChecked();
  expect(screen.getByLabelText("Resolution")).toHaveValue("1440x932");
  expect(screen.getByLabelText("Fullscreen")).not.toBeChecked();
  expect(screen.getByLabelText("Sound")).toBeChecked();
  expect(screen.getByLabelText("Antialiasing")).toHaveValue("0");
  expect(screen.getByLabelText("UI scaling")).toHaveValue("1");
  expect(screen.getByLabelText("Deployment-point budget")).toHaveValue("400");
  await user.click(screen.getByRole("button", { name: "Save launch settings" }));
  expect(await screen.findByText(/Launch settings saved/)).toBeInTheDocument();
});

test("profiles are preview-first and show the exact switch before applying", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Profiles" }));

  expect(await screen.findByRole("heading", { name: "Profiles", level: 1 })).toBeInTheDocument();
  expect(screen.getByText("Heavy campaign")).toBeInTheDocument();
  expect(screen.getByText("Active")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Review switch" }));

  expect(await screen.findByRole("heading", { name: "Switch to Vanilla plus?" })).toBeInTheDocument();
  expect(screen.getByText("Enable (1)")).toBeInTheDocument();
  expect(screen.getByText("Disable (2)")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Apply switch" }));

  expect(await screen.findByText(/Switched to “Vanilla plus”/)).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Switch to Vanilla plus?" })).not.toBeInTheDocument();
});

test("diagnostics disclose their boundary and export a bounded bundle", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));

  expect(await screen.findByRole("heading", { name: "Settings", level: 1 })).toBeInTheDocument();
  await user.click(screen.getByText("What diagnostics include"));
  expect(screen.getByText("Useful metadata only")).toBeInTheDocument();
  expect(screen.getByText("Game and personal data")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Save diagnostics bundle" }));

  expect(await screen.findByText("Diagnostics are ready")).toBeInTheDocument();
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
  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));

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

  await screen.findByText("Ready to launch");
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

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(await screen.findByRole("button", { name: "Save diagnostics bundle" }));

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

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
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

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(screen.getByText("Automated game test"));
  await user.click(await screen.findByRole("button", { name: "Check readiness" }));
  await user.click(await screen.findByRole("button", { name: "Review test" }));

  expect(screen.getByText("Nothing started yet")).toBeInTheDocument();
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

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(screen.getByText("Automated game test"));
  await user.click(await screen.findByRole("button", { name: "Check readiness" }));
  await user.click(await screen.findByRole("button", { name: "Review test" }));
  await user.click(screen.getByRole("button", { name: "Start automated test" }));
  await user.click(await screen.findByRole("button", { name: "Stop test safely" }));

  expect(cancel).toHaveBeenCalledOnce();
  expect(screen.getByText("Stopping the exact game process and sealing its evidence…")).toBeInTheDocument();
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
      diagnostics: ["macOS Accessibility permission isn't enabled for the automation executable: /Applications/Preflight.app/Contents/Resources/engine/runtime/bin/java"],
    },
  });
  const settings = vi.spyOn(bridge, "openDesktopAccessibilitySettings").mockResolvedValue();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
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

  await screen.findByText("Ready to launch");
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
  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(await screen.findByRole("button", { name: "Check for updates" }));

  expect(await screen.findByRole("heading", { name: "Preflight 0.2.0 is available" })).toBeInTheDocument();
  expect(screen.getByText("A signed test release.")).toBeInTheDocument();
  expect(screen.getByText(/previous copy is kept for rollback/)).toBeInTheDocument();
  expect(screen.getByText("Update")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Install and restart" })).toBeEnabled();
  expect(install).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: "Start" }));
  expect(screen.getByRole("region", { name: "Preflight update available" })).toHaveTextContent("Preflight 0.2.0 is available");
  await user.click(screen.getByRole("button", { name: "Review update" }));
  expect(await screen.findByRole("heading", { name: "Settings", level: 1 })).toBeInTheDocument();
  expect(install).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: "Install and restart" }));
  expect(install).toHaveBeenCalledWith("0.2.0");
  install.mockRestore();
  check.mockRestore();
});

test("removal keeps launcher files and all data as separate previewed scopes", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Ready to launch");
  await user.click(screen.getByRole("button", { name: "Settings" }));
  await user.click(screen.getByText("Remove Preflight"));

  const launcher = await screen.findByRole("button", { name: "Review launcher removal" });
  const allData = screen.getByRole("button", { name: "Review all data removal" });
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
