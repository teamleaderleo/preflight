import type { ComponentProps } from "react";
import { render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import type { useDiagnosticsReport } from "../useDiagnosticsReport";
import { HelpPage } from "./HelpPage";

vi.mock("../useCopySetup", () => ({
  useCopySetup: () => ({
    state: "idle",
    text: "",
    copySetup: vi.fn(),
    retryCopySetup: vi.fn(),
  }),
}));

function diagnostics(configured: boolean): ReturnType<typeof useDiagnosticsReport> {
  return {
    automaticRunReports: false,
    diagnosticsBusy: false,
    diagnosticsExport: {
      output: "/tmp/preflight-diagnostics.zip",
      bytes: 123,
      sha256: "a".repeat(64),
      included: [],
      skipped: [],
    },
    reportCancelling: false,
    reportDeleting: false,
    reportError: "",
    reportFinalizing: false,
    reportIntake: configured
      ? { configured: true, origin: "https://reports.example.invalid", reason: null }
      : { configured: false, origin: null, reason: "Remote reporting is disabled in this beta." },
    reportReceipt: null,
    reportReview: false,
    reportUploadedBytes: 0,
    reportUploading: false,
    clearReportReceipt: vi.fn(),
    copyRunReportReceipt: vi.fn(),
    dismissRunReportReceipt: vi.fn(),
    removeRunReport: vi.fn(),
    saveDiagnostics: vi.fn(),
    setAutomaticRunReports: vi.fn(),
    setReportReview: vi.fn(),
    stopRunReport: vi.fn(),
    submitAutomaticFailedRunReport: vi.fn(),
    submitRunReport: vi.fn(),
  } as unknown as ReturnType<typeof useDiagnosticsReport>;
}

function props(configured: boolean): ComponentProps<typeof HelpPage> {
  return {
    message: "",
    messageTone: "info",
    diagnostics: diagnostics(configured),
    operationBlocked: false,
    optimizationPreset: "recommended",
    onTurnOffOptimizations: vi.fn(),
    onChooseInstall: vi.fn(),
    onNavigate: vi.fn(),
  };
}

test("local-only Help keeps support ZIPs local and exposes no remote report action", () => {
  render(<HelpPage {...props(false)} />);

  expect(screen.getByText(/Support ZIPs stay local in this beta/)).toBeInTheDocument();
  expect(screen.getByText(/Remote reporting is disabled in this beta/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Review and send" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Delete uploaded report" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Make another one/i })).toBeEnabled();
});

test("remote-capable Help retains the explicit review action", () => {
  render(<HelpPage {...props(true)} />);

  expect(screen.getByRole("button", { name: "Review and send" })).toBeEnabled();
  expect(screen.getByText(/Support files stay local until you choose Send/)).toBeInTheDocument();
});
