import type { ComponentProps } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import { HelpPage } from "./HelpPage";

function props(overrides: Partial<ComponentProps<typeof HelpPage>> = {}): ComponentProps<typeof HelpPage> {
  return {
    message: "",
    messageTone: "info",
    diagnostics: {
      diagnosticsBusy: false,
      diagnosticsExport: null,
      reportCancelling: false,
      reportDeleting: false,
      reportError: null,
      reportFinalizing: false,
      reportIntake: null,
      reportReceipt: null,
      reportReview: false,
      reportUploadedBytes: 0,
      reportUploading: false,
      copyRunReportReceipt: vi.fn(),
      dismissRunReportReceipt: vi.fn(),
      removeRunReport: vi.fn(),
      saveDiagnostics: vi.fn(),
      setReportReview: vi.fn(),
      stopRunReport: vi.fn(),
      submitRunReport: vi.fn(),
    } as never,
    operationBlocked: false,
    optimizationPreset: "recommended",
    onLaunchWithoutOptimizations: vi.fn(),
    onChooseInstall: vi.fn(),
    onNavigate: vi.fn(),
    ...overrides,
  };
}

test("explains the ordinary save and prepared-data boundary without hiding normal game writes", () => {
  render(<HelpPage {...props()} />);

  const boundary = screen.getByText("Files and saves").closest("details")!;
  expect(boundary).not.toHaveAttribute("open");
  expect(boundary).toHaveTextContent("affect only Preflight’s cache");
  expect(boundary).toHaveTextContent("updates game preferences and makes a backup");
  expect(boundary).toHaveTextContent("Starsector and mods manage campaign saves");
});

test("offers one temporary optimizations-off launch without describing a preference change", async () => {
  const user = userEvent.setup();
  const onLaunchWithoutOptimizations = vi.fn();
  render(<HelpPage {...props({ onLaunchWithoutOptimizations })} />);

  expect(screen.queryByRole("button", { name: "Try without optimizations" })).not.toBeInTheDocument();
  const action = screen.getByRole("button", { name: "Launch once with optimizations off" });
  expect(screen.getByText(/usual optimization setting will apply to later launches/i)).toBeInTheDocument();
  await user.click(action);
  expect(onLaunchWithoutOptimizations).toHaveBeenCalledOnce();
});
