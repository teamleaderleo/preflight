import type { ComponentProps } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
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
    automaticRunReports: false,
    installation: "/Applications/Starsector",
    onAutomaticRunReportsChange: vi.fn(),
    onAfterLaunchBehaviorChange: vi.fn(),
    onChooseInstall: vi.fn(),
    onReviewRemoval: vi.fn(),
    onDismissRemoval: vi.fn(),
    onRemove: vi.fn(),
    ...overrides,
  };
}

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

test("installation changes lock with release-critical operations", () => {
  render(<SettingsPage {...props({ removalBlockedReason: "Close Starsector before removing Preflight data." })} />);

  expect(screen.getByRole("button", { name: "Change folder" })).toBeDisabled();
});
