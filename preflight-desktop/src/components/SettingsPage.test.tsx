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

test("installation changes follow the app-wide workflow lock", () => {
  render(<SettingsPage {...props({ installationChangeBlockedReason: "Updating the saved mod profile" })} />);

  expect(screen.getByRole("button", { name: "Change folder" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Change folder" })).toHaveAttribute("title", "Updating the saved mod profile");
});

test("Home presentation switches immediately between Hangar and Compact", async () => {
  const user = userEvent.setup();
  render(<SettingsPage {...props()} />);

  const select = screen.getByRole("combobox", { name: "Home presentation" });
  expect(select).toHaveValue("hangar");
  expect(screen.getByText("Hull-led Home with the full settled display.")).toBeInTheDocument();

  await user.selectOptions(select, "compact");
  expect(select).toHaveValue("compact");
  expect(document.documentElement.dataset.homeMode).toBe("compact");
  expect(screen.getByText("Launch-first Home without the decorative hull and history readouts.")).toBeInTheDocument();
  expect(JSON.parse(window.localStorage.getItem(HOME_PRESENTATION_STORAGE_KEY) ?? "null"))
    .toEqual({ mode: "compact", showPlaytime: true });
});

test("Home playtime visibility is an immediate display-only preference", async () => {
  const user = userEvent.setup();
  render(<SettingsPage {...props()} />);

  const select = screen.getByRole("combobox", { name: "Home playtime" });
  expect(select).toHaveValue("show");
  expect(screen.getByText("Display only. Launch history and playtime recording continue either way.")).toBeInTheDocument();

  await user.selectOptions(select, "hide");
  expect(select).toHaveValue("hide");
  expect(document.documentElement.dataset.homePlaytime).toBe("hidden");
  expect(JSON.parse(window.localStorage.getItem(HOME_PRESENTATION_STORAGE_KEY) ?? "null"))
    .toEqual({ mode: "hangar", showPlaytime: false });

  await user.selectOptions(select, "show");
  expect(document.documentElement.dataset.homePlaytime).toBe("shown");
});
