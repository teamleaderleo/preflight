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

  const boundary = screen.getByRole("region", { name: "What Preflight changes" });
  expect(boundary).toHaveTextContent("Optimized launches do not rewrite Starsector or mod files");
  expect(boundary).toHaveTextContent("Preflight does not put prepared data in campaign saves");
  expect(boundary).toHaveTextContent("the game and mods can still make their normal save writes");
  expect(boundary).toHaveTextContent("explicit, backed-up changes to game-owned preferences");
});
