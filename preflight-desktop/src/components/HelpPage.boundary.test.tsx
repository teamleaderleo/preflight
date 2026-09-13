import type { ComponentProps } from "react";
import { render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import { HelpPage } from "./HelpPage";

function props(): ComponentProps<typeof HelpPage> {
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
    onTurnOffOptimizations: vi.fn(),
    onChooseInstall: vi.fn(),
    onNavigate: vi.fn(),
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
