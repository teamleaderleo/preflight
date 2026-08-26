import { fireEvent, render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import type { usePreparation } from "../usePreparation";
import type { SpeedStanding } from "../useSpeedRecord";
import { PreparationPage } from "./PreparationPage";

vi.mock("./SpeedScoreboard", () => ({
  SpeedScoreboard: () => null,
}));

const repairAndPrepare = vi.fn();

function preparation(status: "repair-needed" | "unsafe" | "unknown"): ReturnType<typeof usePreparation> {
  return {
    cache: null,
    cacheHealth: {
      format: "starsector-preflight-cache-health-v1",
      status,
      profileFingerprint: "profile-fingerprint",
      issues: [{
        code: "test",
        summary: status === "repair-needed" ? "Prepared index is damaged." : "Prepared data location could not be verified.",
      }],
      repairBytes: 4096,
      repairFiles: 2,
    },
    cacheLoading: false,
    cacheRepairing: false,
    preparationCancelling: false,
    preparationPercent: null,
    preparationPhaseLabel: null,
    preparationPlan: null,
    preparationPlanLoading: false,
    preparing: false,
    profilePrepared: true,
    resourcePreset: "balanced",
    textureStorage: "balanced",
    prepare: vi.fn(),
    repairAndPrepare,
    refreshCache: vi.fn(),
    setResourcePreset: vi.fn(),
    setTextureStorage: vi.fn(),
    stopPreparation: vi.fn(),
  } as unknown as ReturnType<typeof usePreparation>;
}

function renderPage(status: "repair-needed" | "unsafe" | "unknown") {
  render(
    <PreparationPage
      message=""
      messageTone="info"
      isReady
      optimizationPreset="recommended"
      disabledOptimizationDomains={[]}
      preparation={preparation(status)}
      cleanupPlan={null}
      cleanupBusy={false}
      operationBlocked={false}
      speedStanding={{} as SpeedStanding}
      onOptimizationPresetChange={vi.fn()}
      onOptimizationDomainChange={vi.fn()}
      onReviewCleanup={vi.fn()}
      onCleanCache={vi.fn()}
      onDismissCleanup={vi.fn()}
      onOpenBenchmark={vi.fn()}
    />,
  );
}

test("damaged prepared data offers a rebuild without presenting game or mod files as repair targets", () => {
  repairAndPrepare.mockReset();
  renderPage("repair-needed");

  expect(screen.getByRole("heading", { name: "Prepared data needs repair" })).toBeInTheDocument();
  expect(screen.getByText(/Preflight will rebuild only its prepared data for this mod setup/)).toBeInTheDocument();
  expect(screen.getByText(/Starsector, mods, and saves stay unchanged/)).toBeInTheDocument();
  expect(screen.getByText("Repair details")).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "Rebuild prepared data" }));
  expect(repairAndPrepare).toHaveBeenCalledWith(false);
});

test("an unsafe prepared-data location stays review-only", () => {
  renderPage("unsafe");

  expect(screen.getByRole("heading", { name: "Prepared data location needs attention" })).toBeInTheDocument();
  expect(screen.getByText(/Preflight left this prepared data in place/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Rebuild prepared data" })).toBeNull();
});
