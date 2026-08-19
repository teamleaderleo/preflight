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

test("installation changes follow the app-wide workflow lock", () => {
  render(<SettingsPage {...props({ installationChangeBlockedReason: "Updating the saved mod profile" })} />);

  expect(screen.getByRole("button", { name: "Change folder" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Change folder" })).toHaveAttribute("title", "Updating the saved mod profile");
});

test("local-only builds expose no automatic report control", () => {
  render(<SettingsPage {...props({
    reportIntake: {
      configured: false,
      origin: null,
      reason: "Remote reporting is disabled in this beta.",
    },
  })} />);

  expect(screen.queryByRole("checkbox", { name: /Send failed-run reports automatically/i })).not.toBeInTheDocument();
  expect(screen.getByText("Update checks fetch version metadata. Support ZIPs stay here until you share one.")).toBeInTheDocument();
});

test("remote-capable builds retain the automatic report control", async () => {
  const user = userEvent.setup();
  const onAutomaticRunReportsChange = vi.fn();
  render(<SettingsPage {...props({
    reportIntake: {
      configured: true,
      origin: "https://reports.example.invalid",
      reason: null,
    },
    onAutomaticRunReportsChange,
  })} />);

  const checkbox = screen.getByRole("checkbox", { name: /Send failed-run reports automatically/i });
  expect(checkbox).toBeEnabled();
  await user.click(checkbox);
  expect(onAutomaticRunReportsChange).toHaveBeenCalledWith(true);
});
