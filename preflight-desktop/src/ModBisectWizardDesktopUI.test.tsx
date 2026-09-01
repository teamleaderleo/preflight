import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type {
  BisectState,
  BisectOffendingMod,
  BisectStepHistoryEntry,
  BisectSessionSnapshot,
} from "./types";
import {
  ModBisectWizard,
  BisectProgressBar,
  BisectActivePartitionCard,
  BisectCulpritView,
} from "./components/ModBisectWizard";

export type { BisectState, BisectOffendingMod, BisectStepHistoryEntry, BisectSessionSnapshot };
export { ModBisectWizard, BisectProgressBar, BisectActivePartitionCard, BisectCulpritView };

// ------------------- TEST SUITE -------------------
describe("Feature 16: Mod Bisect Assistant Desktop Wizard Test Suite", () => {
  const mockBisectSession: BisectSessionSnapshot = {
    format: "starsector-preflight-bisect-session-v1",
    sessionId: "bisect-20260818-1",
    installRoot: "/Applications/Starsector.app",
    startedAt: "2026-08-18T15:20:00Z",
    updatedAt: "2026-08-18T15:25:00Z",
    state: "TESTING",
    initialEnabledMods: ["armaa", "nexerelin", "uaf", "ind-evolution", "magiclib", "lw_lazylib", "graphicslib"],
    fixedBaseMods: ["magiclib", "lw_lazylib", "graphicslib"],
    suspectMods: ["armaa", "nexerelin", "uaf", "ind-evolution"],
    eliminatedGoodMods: ["magiclib", "lw_lazylib", "graphicslib"],
    currentTestSubset: ["armaa", "nexerelin", "magiclib", "lw_lazylib", "graphicslib"],
    stepNumber: 2,
    totalEstimatedSteps: 4,
    history: [
      {
        step: 1,
        timestamp: "2026-08-18T15:22:00Z",
        testedSubset: ["uaf", "ind-evolution", "magiclib", "lw_lazylib", "graphicslib"],
        verdict: "PASS",
        notes: "Test passed cleanly without crash.",
      },
    ],
    candidateCulprit: null,
    backupFile: "~/.starsector-preflight/profile-backups/bisect-initial-backup.json",
    active: true,
  };

  const mockCulpritFoundSession: BisectSessionSnapshot = {
    ...mockBisectSession,
    state: "CULPRIT_FOUND",
    stepNumber: 4,
    suspectMods: ["armaa"],
    currentTestSubset: ["armaa", "magiclib", "lw_lazylib"],
    candidateCulprit: {
      id: "armaa",
      name: "Arma Armatura",
      version: "1.94",
      directory: "mods/Arma Armatura",
      crashingClass: "armaa.hullmods.MountedWep",
      crashingTrace: "java.lang.NullPointerException: null\n\tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)",
      downstreamDependents: ["armaa_submod"],
    },
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ========== TIER 1: HAPPY PATH EQUIVALENCE CLASS TESTS (>= 5 tests) ==========

  it("T1.1: initializes bisect wizard displaying suspect count and estimated steps O(log N)", () => {
    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("MOD FAILURE BISECT ASSISTANT")).toBeInTheDocument();
    expect(screen.getByText(/STEP 2 OF ~4 \/\/ 4 SUSPECTS REMAINING/)).toBeInTheDocument();
    expect(screen.getByText("[TESTING]")).toBeInTheDocument();
  });

  it("T1.2: displays active test partition showing testing suspects and fixed prerequisite base", () => {
    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    const tagCloud = screen.getByTestId("mod-tag-cloud");
    expect(within(tagCloud).getByText("armaa (Testing)")).toBeInTheDocument();
    expect(within(tagCloud).getByText("nexerelin (Testing)")).toBeInTheDocument();
    expect(within(tagCloud).getByText("magiclib (Prerequisite Base)")).toBeInTheDocument();
    expect(within(tagCloud).getByText("lw_lazylib (Prerequisite Base)")).toBeInTheDocument();
  });

  it("T1.3: launches test run and enables verdict recording buttons", async () => {
    const user = userEvent.setup();
    const launchSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={launchSpy}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
    const passBtn = screen.getByRole("button", { name: "✓ Passed (No Crash)" });
    const failBtn = screen.getByRole("button", { name: "✗ Failed (Crashed)" });

    // Verdict buttons disabled before launch
    expect(passBtn).toBeDisabled();
    expect(failBtn).toBeDisabled();

    await user.click(launchBtn);
    expect(launchSpy).toHaveBeenCalledTimes(1);

    // Enabled after launch
    expect(passBtn).toBeEnabled();
    expect(failBtn).toBeEnabled();
  });

  it("T1.4: records PASS verdict when test run succeeds", async () => {
    const user = userEvent.setup();
    const verdictSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn().mockResolvedValue(undefined)}
        onRecordVerdict={verdictSpy}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    await user.click(screen.getByRole("button", { name: "[ 1. Launch Test Run ]" }));
    await user.click(screen.getByRole("button", { name: "✓ Passed (No Crash)" }));

    expect(verdictSpy).toHaveBeenCalledWith("PASS");
  });

  it("T1.5: records FAIL verdict when test run crashes", async () => {
    const user = userEvent.setup();
    const verdictSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn().mockResolvedValue(undefined)}
        onRecordVerdict={verdictSpy}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    await user.click(screen.getByRole("button", { name: "[ 1. Launch Test Run ]" }));
    await user.click(screen.getByRole("button", { name: "✗ Failed (Crashed)" }));

    expect(verdictSpy).toHaveBeenCalledWith("FAIL");
  });

  it("T1.6: identifies and displays culprit mod summary with stack trace and downstream dependencies", () => {
    render(
      <ModBisectWizard
        session={mockCulpritFoundSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("🎯 CULPRIT MOD ISOLATED")).toBeInTheDocument();
    expect(screen.getByText("ARMA ARMATURA (armaa v1.94)")).toBeInTheDocument();
    expect(screen.getByText(/armaa.hullmods.MountedWep.advanceInCombat/)).toBeInTheDocument();
    expect(screen.getByText("armaa_submod")).toBeInTheDocument();
  });

  it("T1.7: applies 1-click culprit disabling resolution", async () => {
    const user = userEvent.setup();
    const resolveSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModBisectWizard
        session={mockCulpritFoundSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={resolveSpy}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    const applyBtn = screen.getByRole("button", { name: "[ 1-Click Fix: Disable 'armaa' & Restore Rest ]" });
    await user.click(applyBtn);

    expect(resolveSpy).toHaveBeenCalledTimes(1);
  });

  // ========== TIER 2: BOUNDARY VALUE ANALYSIS & ERROR / FAULT INJECTION (>= 5 tests) ==========

  it("T2.1: skips partition and generates alternative leaf partition", async () => {
    const user = userEvent.setup();
    const verdictSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={verdictSpy}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    const skipBtn = screen.getByRole("button", { name: "↷ Skip Partition" });
    await user.click(skipBtn);

    expect(verdictSpy).toHaveBeenCalledWith("SKIP");
  });

  it("T2.2: aborts bisect session at any point and restores original enabled_mods.json", async () => {
    const user = userEvent.setup();
    const abortSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={abortSpy}
        operationBlocked={false}
      />
    );

    const abortBtn = screen.getByRole("button", { name: "Abort & Restore Original Setup" });
    await user.click(abortBtn);

    expect(abortSpy).toHaveBeenCalledTimes(1);
  });

  it("T2.3: blocks test launches and verdicts while OperationLease is active", () => {
    render(
      <ModBisectWizard
        session={mockBisectSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={true}
      />
    );

    const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
    const skipBtn = screen.getByRole("button", { name: "↷ Skip Partition" });

    expect(launchBtn).toBeDisabled();
    expect(skipBtn).toBeDisabled();
  });

  it("T2.4: renders completed state view when bisect completes", () => {
    const completedSession: BisectSessionSnapshot = {
      ...mockBisectSession,
      state: "COMPLETED",
    };

    render(
      <ModBisectWizard
        session={completedSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByTestId("bisect-completed")).toBeInTheDocument();
    expect(screen.getByText("Bisect Session Completed Successfully")).toBeInTheDocument();
  });

  it("T2.5: handles progress bar at 100% boundary correctly", () => {
    render(
      <BisectProgressBar
        stepNumber={4}
        totalSteps={4}
        suspectsRemaining={1}
      />
    );

    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(screen.getByText("ESTIMATED ~0 TEST LAUNCHES REMAINING")).toBeInTheDocument();
  });
});
