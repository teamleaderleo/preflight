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

  expect(await screen.findByText("Your launch pad is cozy and ready")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getByText("Recommended optimizations")).toBeInTheDocument();
  expect(screen.getByText(/Current profile prepared/)).toBeInTheDocument();
  expect(screen.getByText("Your save is sacred.")).toBeInTheDocument();
});

test("preparation exposes balanced defaults, storage, and bounded resource choices", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Prepare" }));

  expect(await screen.findByText("Prepare your profile")).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeChecked();
  expect(screen.getByText("4.50 GB")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Balanced4 workers/ })).toBeEnabled();
  expect(await screen.findByRole("button", { name: "Prepare current profile" })).toBeEnabled();
});

test("cache cleanup is previewed before unused artifacts are removed", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Prepare" }));
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

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Launch" }));

  expect(await screen.findByText("Starsector launch settings")).toBeInTheDocument();
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

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Profiles" }));

  expect(await screen.findByText("Your saved flight plans")).toBeInTheDocument();
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

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));

  expect(await screen.findByText("Support and diagnostics")).toBeInTheDocument();
  expect(screen.getByText("Useful metadata only")).toBeInTheDocument();
  expect(screen.getByText("Your actual game data")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Save diagnostics bundle" }));

  expect(await screen.findByText("Diagnostics are ready")).toBeInTheDocument();
  expect(screen.getByText(/Saved 14 disclosed files/)).toBeInTheDocument();
});

test("removal keeps launcher files and all data as separate previewed scopes", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));

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
