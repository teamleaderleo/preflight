import { render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import type { PreparationStoragePlan } from "../types";
import type { StorageCleanupPlan } from "../useCacheCleanup";
import type { usePreparation } from "../usePreparation";
import type { SpeedStanding } from "../useSpeedRecord";
import { PreparationPage } from "./PreparationPage";

vi.mock("./SpeedScoreboard", () => ({
  SpeedScoreboard: () => null,
}));

function plan(overrides: Partial<PreparationStoragePlan> = {}): PreparationStoragePlan {
  return {
    format: "preflight-preparation-storage-plan-v1",
    profileFingerprint: "a".repeat(64),
    textureStorage: "balanced",
    cacheDirectory: "/tmp/preflight",
    packPath: "/tmp/preflight/pack.sptp",
    candidateEntries: 10,
    hashedEntries: 10,
    uniqueContent: 8,
    supportedContent: 8,
    unsupportedContent: 0,
    failedContent: 0,
    uniqueSourceBytes: 32 * 1024 * 1024,
    uniquePixelBytes: 128 * 1024 * 1024,
    reusableLooseBytes: 0,
    predictedLooseBytes: 32 * 1024 * 1024,
    predictedPackBytes: 16 * 1024 * 1024,
    predictedMetadataBytes: 4 * 1024 * 1024,
    predictedAdditionalBytes: 52 * 1024 * 1024,
    safetyReserveBytes: 1024 * 1024 * 1024,
    requiredFreeBytes: 1128 * 1024 * 1024,
    usableBytes: 20 * 1024 * 1024 * 1024,
    packHit: false,
    complete: true,
    safeToPrepare: true,
    refusalReason: null,
    diagnostics: [],
    durationMs: 12,
    ...overrides,
  };
}

function preparation(
  preparationPlan: PreparationStoragePlan,
  overrides: Partial<ReturnType<typeof usePreparation>> = {},
): ReturnType<typeof usePreparation> {
  return {
    cache: null,
    cacheHealth: null,
    cacheLoading: false,
    cacheRepairing: false,
    preparationCancelling: false,
    preparationPercent: null,
    preparationPhaseLabel: null,
    preparationPlan,
    preparationPlanLoading: false,
    preparing: false,
    profilePrepared: false,
    resourcePreset: "balanced",
    textureStorage: "balanced",
    prepare: vi.fn(),
    repairAndPrepare: vi.fn(),
    refreshCache: vi.fn(),
    setResourcePreset: vi.fn(),
    setTextureStorage: vi.fn(),
    stopPreparation: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof usePreparation>;
}

function renderPage(
  preparationPlan: PreparationStoragePlan,
  overrides: Partial<ReturnType<typeof usePreparation>> = {},
  cleanupPlan: StorageCleanupPlan | null = null,
) {
  render(
    <PreparationPage
      message=""
      messageTone="info"
      isReady
      optimizationPreset="recommended"
      disabledOptimizationDomains={[]}
      preparation={preparation(preparationPlan, overrides)}
      cleanupPlan={cleanupPlan}
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

test("compatible prepared texture bytes are described as present without promising loose reuse", () => {
  // reusableLooseBytes can include valid LZ4 + RAW siblings even when preparation selects only one.
  renderPage(plan({
    reusableLooseBytes: 96 * 1024 * 1024,
    predictedLooseBytes: 0,
  }));

  expect(screen.getByText(/^.* of compatible prepared texture data is already on disk\.$/)).toBeInTheDocument();
  expect(screen.getByText("Compatible prepared texture data on disk")).toBeInTheDocument();
  expect(screen.getByText(/alternate encodings that remain on disk while preparation uses another/)).toBeInTheDocument();
  expect(screen.queryByText(/will be reused/i)).not.toBeInTheDocument();
  expect(screen.getByText("Finished texture data")).toBeInTheDocument();
});

test("pack hit reserves reuse wording for the exact current profile pack", () => {
  renderPage(plan({
    reusableLooseBytes: 96 * 1024 * 1024,
    predictedLooseBytes: 0,
    predictedPackBytes: 0,
    predictedMetadataBytes: 0,
    predictedAdditionalBytes: 0,
    packHit: true,
  }));

  expect(screen.getByText(/^.* of compatible prepared texture data is already on disk\. The current profile texture pack will be reused\.$/)).toBeInTheDocument();
  expect(screen.getByText("Compatible prepared texture data on disk")).toBeInTheDocument();
  expect(screen.getByText("Current profile texture pack")).toBeInTheDocument();
  expect(screen.getByText("Will be reused")).toBeInTheDocument();
  expect(screen.getByText("This profile’s texture pack is ready, so it doesn’t need to be rebuilt.")).toBeInTheDocument();
  expect(screen.getByText("Finished texture data")).toBeInTheDocument();
});

test("cold preparation stays silent about already-present texture data and pack reuse", () => {
  renderPage(plan());

  expect(screen.queryByText(/compatible prepared texture data is already on disk/)).not.toBeInTheDocument();
  expect(screen.queryByText("Compatible prepared texture data on disk")).not.toBeInTheDocument();
  expect(screen.queryByText("Current profile texture pack")).not.toBeInTheDocument();
  expect(screen.getByText("Finished texture data")).toBeInTheDocument();
});

test("default preparation stays quiet while advanced overrides state their consequences", () => {
  renderPage(plan());

  expect(screen.getByText("Ready to prepare")).toBeInTheDocument();
  expect(screen.queryByText(/Balanced storage selected/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/medium resource use/i)).not.toBeInTheDocument();
});

test("changed storage and resource choices stay visible without preset-name narration", () => {
  renderPage(plan({ textureStorage: "fastest" }), {
    textureStorage: "fastest",
    resourcePreset: "eager",
  });

  expect(screen.getByText("Keeps textures uncompressed and uses more disk · Uses more preparation resources.")).toBeInTheDocument();
  expect(screen.queryByText(/Fastest raw storage selected/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/high resource use/i)).not.toBeInTheDocument();
});

test("cleanup explains that completed comparison and save/reload evidence occupy bounded report slots", () => {
  renderPage(plan(), {}, {
    cache: {
      format: "starsector-preflight-cache-prune-v1",
      safe: true,
      applied: false,
      currentProfileFingerprint: "a".repeat(64),
      survivingProfileFingerprints: ["a".repeat(64)],
      bytes: 0,
      files: 0,
      reachableTextureBlobs: 0,
      reachablePreparedAudioBlobs: 0,
      reachableClasspathArchiveIndexes: 0,
      refusals: [],
      groups: [],
      removals: [],
      removalsTruncated: false,
    },
    evidence: {
      format: "starsector-preflight-evidence-prune-v1",
      applied: false,
      keepRuns: 10,
      keepBenchmarks: 5,
      bytes: 1024,
      files: 1,
      removedBytes: 0,
      sessions: [],
    },
    bytes: 1024,
    files: 1,
  });

  expect(screen.getByText(/10 launch reports—including the latest completed comparison and save\/reload check when available—and 5 benchmark campaigns/)).toBeInTheDocument();
});
