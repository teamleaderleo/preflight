import type { ComponentProps } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, test, vi } from "vitest";
import { HOME_PRESENTATION_STORAGE_KEY } from "../desktopStorage";
import type { useSignedUpdates } from "../useSignedUpdates";
import { SettingsPage } from "./SettingsPage";

const updates = {
  updateChecking: false,
  updateError: null,
  updateInstalling: false,
  updateProgress: null,
  updateStatus: {
    format: "preflight-update-v1",
    configured: true,
    currentVersion: "1.0.0",
    available: false,
    version: null,
    date: null,
    notes: null,
    reason: null,
  },
  automaticUpdateChecks: true,
  setAutomaticUpdateChecks: vi.fn(),
  checkUpdates: vi.fn(),
  installSignedUpdate: vi.fn(),
} as unknown as ReturnType<typeof useSignedUpdates>;

function props(overrides: Partial<ComponentProps<typeof SettingsPage>> = {}): ComponentProps<typeof SettingsPage> {
  return {
    message: "",
    messageTone: "info",
    updates,
    reportIntake: null,
    removalPlan: null,
    removalBusy: false,
    afterLaunchBehavior: "minimize",
    recordFramePacing: false,
    framePacingPaused: false,
    installation: "/Applications/Starsector",
    onAfterLaunchBehaviorChange: vi.fn(),
    onRecordFramePacingChange: vi.fn(),
    onChooseInstall: vi.fn(),
    onReviewRemoval: vi.fn(),
    onDismissRemoval: vi.fn(),
    onRemove: vi.fn(),
    ...overrides,
  };
}

beforeEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.homeMode;
  delete document.documentElement.dataset.homePlaytime;
});

test("Settings owns ordinary installation changes", async () => {
  const user = userEvent.setup();
  const onChooseInstall = vi.fn();
  render(<SettingsPage {...props({ onChooseInstall })} />);

  expect(screen.getByText("/Applications/Starsector")).toHaveAttribute("title", "/Applications/Starsector");
  await user.click(screen.getByRole("button", { name: "Change folder" }));
  expect(onChooseInstall).toHaveBeenCalledOnce();
});

test("Settings offers first selection when no installation is active", () => {
  render(<SettingsPage {...props({ installation: null })} />);

  expect(screen.getByText("No Starsector installation selected.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Choose game folder" })).toBeEnabled();
});

test("frame pacing is an explicit local opt-in and explains the Off boundary", async () => {
  const user = userEvent.setup();
  const onRecordFramePacingChange = vi.fn();
  const { rerender } = render(<SettingsPage {...props({ onRecordFramePacingChange })} />);

  const toggle = screen.getByRole("checkbox", { name: "Record frame pacing" });
  expect(toggle).not.toBeChecked();
  expect(screen.getByText(/Never writes to saves/)).toBeInTheDocument();
  await user.click(toggle);
  expect(onRecordFramePacingChange).toHaveBeenCalledWith(true);

  rerender(<SettingsPage {...props({ recordFramePacing: true, framePacingPaused: true })} />);
  expect(screen.getByText("Paused while optimizations are Off.")).toBeInTheDocument();
});

test("installation changes follow the app-wide workflow lock", () => {
  render(<SettingsPage {...props({ installationChangeBlockedReason: "Updating the saved mod profile" })} />);

  expect(screen.getByRole("button", { name: "Change folder" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Change folder" })).toHaveAttribute("title", "Updating the saved mod profile");
});

test("support files remain manual even when report intake is configured", () => {
  render(<SettingsPage {...props({
    reportIntake: { configured: true, origin: "https://reports.example", reason: null },
  })} />);

  expect(screen.queryByRole("checkbox", { name: /failed-run reports/i })).not.toBeInTheDocument();
  expect(screen.getByText("Reports are sent only when you choose.")).toBeInTheDocument();
});

test("an update failure replaces the stale unchecked status", () => {
  render(<SettingsPage {...props({
    updates: {
      ...updates,
      updateError: "Couldn’t check for updates. The service is unavailable.",
      updateStatus: null,
    } as ReturnType<typeof useSignedUpdates>,
  })} />);

  expect(screen.getByRole("alert")).toHaveTextContent("Couldn’t check for updates");
  expect(screen.queryByText("Update status hasn’t been checked yet.")).not.toBeInTheDocument();
});

test("removal review takes focus and Cancel returns to the initiating control", async () => {
  const user = userEvent.setup();
  const onReviewRemoval = vi.fn();
  const onDismissRemoval = vi.fn();
  const initial = props({ onReviewRemoval, onDismissRemoval });
  const { rerender } = render(<SettingsPage {...initial} />);

  const summary = screen.getByText("Remove Preflight").closest("summary");
  expect(summary).not.toBeNull();
  await user.click(summary!);
  const trigger = screen.getByRole("button", { name: "Review deletion" });
  await user.click(trigger);
  expect(onReviewRemoval).toHaveBeenCalledWith("all-data");

  rerender(<SettingsPage {...props({
    onReviewRemoval,
    onDismissRemoval,
    removalPlan: {
      format: "preflight-removal-v1",
      scope: "all-data",
      safe: true,
      applied: false,
      bytes: 1024,
      files: 2,
      targets: [{
        kind: "preflight-data",
        label: "Preflight data",
        path: "/tmp/preflight-data",
        bytes: 1024,
        files: 2,
      }],
      refusals: [],
      preserves: [],
    },
  })} />);

  expect(screen.getByRole("region", { name: "Removal review" })).toHaveFocus();
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(onDismissRemoval).toHaveBeenCalledOnce();
  expect(trigger).toHaveFocus();
});

test("unsafe removal review shows the refusal beside the disabled action", () => {
  render(<SettingsPage {...props({
    removalPlan: {
      format: "preflight-removal-v1",
      scope: "all-data",
      safe: false,
      applied: false,
      bytes: 0,
      files: 0,
      targets: [{
        kind: "preflight-data",
        label: "Preflight data",
        path: "/tmp/preflight-data",
        bytes: 0,
        files: 0,
      }],
      refusals: ["Preflight home directory is a symlink or alias. All-data removal is refused."],
      preserves: [],
    },
  })} />);

  expect(screen.getByText("Preflight home directory is a symlink or alias. All-data removal is refused.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Remove all Preflight data" })).toBeDisabled();
});

test("Home presentation switches immediately between Hangar and Compact", async () => {
  const user = userEvent.setup();
  render(<SettingsPage {...props()} />);

  const select = screen.getByRole("combobox", { name: "Home presentation" });
  expect(select).toHaveValue("hangar");

  await user.selectOptions(select, "compact");
  expect(select).toHaveValue("compact");
  expect(document.documentElement.dataset.homeMode).toBe("compact");
  expect(JSON.parse(window.localStorage.getItem(HOME_PRESENTATION_STORAGE_KEY) ?? "null"))
    .toEqual({ mode: "compact", showPlaytime: true });
});

test("Home playtime visibility is an immediate display-only preference", async () => {
  const user = userEvent.setup();
  render(<SettingsPage {...props()} />);

  const select = screen.getByRole("combobox", { name: "Home playtime" });
  expect(select).toHaveValue("show");

  await user.selectOptions(select, "hide");
  expect(select).toHaveValue("hide");
  expect(document.documentElement.dataset.homePlaytime).toBe("hidden");
  expect(JSON.parse(window.localStorage.getItem(HOME_PRESENTATION_STORAGE_KEY) ?? "null"))
    .toEqual({ mode: "hangar", showPlaytime: false });

  await user.selectOptions(select, "show");
  expect(document.documentElement.dataset.homePlaytime).toBe("shown");
});
