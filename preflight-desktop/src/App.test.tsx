import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import type { DesktopSnapshot, RunStateEvent } from "./types";

const bridge = vi.hoisted(() => ({
  getSnapshot: vi.fn(),
  isDesktopHost: vi.fn(),
  startGame: vi.fn(),
}));
const events = vi.hoisted(() => ({
  listen: vi.fn(),
}));

vi.mock("./bridge", () => bridge);
vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => events);

const snapshot: DesktopSnapshot = {
  protocol: 1,
  engineVersion: "test",
  platform: "mac",
  ready: true,
  selected: {
    installRoot: "/Applications/Starsector",
    launcher: "/Applications/Starsector/Starsector.app",
    kind: "executable",
    score: 120,
    source: "test",
  },
  candidates: [],
  diagnostics: [],
  preflightHome: "/tmp/preflight",
  cachePresent: false,
  lastRun: null,
};

let publishRunState: ((event: { payload: RunStateEvent }) => void) | undefined;

beforeEach(() => {
  bridge.getSnapshot.mockReset().mockResolvedValue(snapshot);
  bridge.isDesktopHost.mockReset().mockReturnValue(true);
  bridge.startGame.mockReset().mockResolvedValue({ pid: 4242 });
  events.listen.mockReset().mockImplementation(
    (_event: string, callback: (event: { payload: RunStateEvent }) => void) => {
      publishRunState = callback;
      return Promise.resolve(vi.fn());
    },
  );
  publishRunState = undefined;
});

test("shows the useful launch controls without decorative copy", async () => {
  render(<App />);

  expect(await screen.findByText("PREFLIGHT READY")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getByText("Game files unchanged")).toBeInTheDocument();
});

test("registers one run-state listener across snapshot refreshes", async () => {
  const user = userEvent.setup();
  render(<App />);
  await screen.findByText("PREFLIGHT READY");

  await user.click(screen.getByRole("button", { name: "Refresh" }));
  await waitFor(() => expect(bridge.getSnapshot).toHaveBeenCalledTimes(2));

  expect(events.listen).toHaveBeenCalledTimes(1);
});

test("keeps launch disabled through refresh while the game is running", async () => {
  const user = userEvent.setup();
  render(<App />);
  const launch = await screen.findByRole("button", { name: "Launch Starsector" });

  await user.click(launch);
  await waitFor(() => expect(launch).toBeDisabled());
  await user.click(screen.getByRole("button", { name: "Refresh" }));
  await waitFor(() => expect(bridge.getSnapshot).toHaveBeenCalledTimes(2));

  expect(launch).toBeDisabled();

  await act(async () => {
    publishRunState?.({
      payload: {
        state: "finished",
        pid: 4242,
        success: false,
        detail: "Starsector exited with an error: missing adapter",
      },
    });
  });
  expect(await screen.findByText(/missing adapter/)).toBeInTheDocument();
  await waitFor(() => expect(launch).toBeEnabled());
});

test("does not resurrect a run that finishes before start_game returns", async () => {
  let resolveStart: ((value: { pid: number }) => void) | undefined;
  bridge.startGame.mockReturnValue(new Promise((resolve) => {
    resolveStart = resolve;
  }));
  const user = userEvent.setup();
  render(<App />);
  const launch = await screen.findByRole("button", { name: "Launch Starsector" });

  const click = user.click(launch);
  await waitFor(() => expect(bridge.startGame).toHaveBeenCalledOnce());
  await act(async () => {
    publishRunState?.({
      payload: {
        state: "finished",
        pid: 4242,
        success: false,
        detail: "Starsector exited immediately",
      },
    });
    resolveStart?.({ pid: 4242 });
    await click;
  });

  await waitFor(() => expect(launch).toBeEnabled());
  expect(screen.getByText("Starsector exited immediately")).toBeInTheDocument();
});
