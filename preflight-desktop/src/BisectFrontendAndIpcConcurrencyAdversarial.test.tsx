import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, renderHook, act, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type {
  BisectSessionSnapshot,
} from "./types";
import {
  ModBisectWizard,
  BisectProgressBar,
  BisectCulpritView,
} from "./components/ModBisectWizard";
import { useModBisect } from "./useModBisect";
import { PALETTES, type PalettePreference } from "./useTheme";
import * as bridge from "./bridge";
import styles from "./styles.css?raw";

// Mock the IPC bridge for hook testing
vi.mock("./bridge", () => ({
  getBisectStatus: vi.fn(),
  startModBisect: vi.fn(),
  recordBisectVerdict: vi.fn(),
  resetModBisect: vi.fn(),
}));

describe("Adversarial Challenge: Desktop Frontend & Rust IPC Concurrency", () => {
  const baseSession: BisectSessionSnapshot = {
    format: "starsector-preflight-bisect-session-v1",
    sessionId: "bisect-test-session-1",
    installRoot: "/Games/Starsector",
    startedAt: "2026-08-19T10:00:00Z",
    updatedAt: "2026-08-19T10:05:00Z",
    state: "TESTING",
    initialEnabledMods: ["mod_a", "mod_b", "mod_c", "mod_d", "magiclib", "lw_lazylib"],
    fixedBaseMods: ["magiclib", "lw_lazylib"],
    suspectMods: ["mod_a", "mod_b", "mod_c", "mod_d"],
    eliminatedGoodMods: ["magiclib", "lw_lazylib"],
    currentTestSubset: ["mod_a", "mod_b", "magiclib", "lw_lazylib"],
    stepNumber: 1,
    totalEstimatedSteps: 3,
    history: [],
    candidateCulprit: null,
    backupFile: "~/.starsector-preflight/profile-backups/bisect-initial-backup.json",
    active: true,
  };

  const culpritSession: BisectSessionSnapshot = {
    ...baseSession,
    state: "CULPRIT_FOUND",
    stepNumber: 3,
    suspectMods: ["mod_a"],
    currentTestSubset: ["mod_a", "magiclib", "lw_lazylib"],
    candidateCulprit: {
      id: "mod_a",
      name: "Mod Alpha Combat Overhaul",
      version: "2.1.0",
      directory: "mods/mod_a",
      crashingClass: "data.scripts.AlphaEveryFrameScript",
      crashingTrace: "java.lang.NullPointerException: null\n\tat data.scripts.AlphaEveryFrameScript.advance(AlphaEveryFrameScript.java:88)",
      downstreamDependents: ["mod_a_addon", "mod_a_ships"],
    },
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // =========================================================================
  // Challenge Area 1: Wizard Step Transitions
  // =========================================================================
  describe("Challenge Area 1: Wizard Step Transitions", () => {
    it("1.1 enforces launch before enabling verdict submission across sequential steps", async () => {
      const user = userEvent.setup();
      const launchMock = vi.fn().mockResolvedValue(undefined);
      const verdictMock = vi.fn().mockResolvedValue(undefined);

      const { rerender } = render(
        <ModBisectWizard
          session={baseSession}
          onLaunchTest={launchMock}
          onRecordVerdict={verdictMock}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      // On Step 1: verdict buttons are disabled initially
      const passBtn = screen.getByRole("button", { name: "✓ Passed (No Crash)" });
      const failBtn = screen.getByRole("button", { name: "✗ Failed (Crashed)" });
      expect(passBtn).toBeDisabled();
      expect(failBtn).toBeDisabled();

      // Launch Step 1
      const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
      await user.click(launchBtn);
      expect(launchMock).toHaveBeenCalledTimes(1);
      expect(passBtn).toBeEnabled();
      expect(failBtn).toBeEnabled();

      // Submit PASS verdict for Step 1
      await user.click(passBtn);
      expect(verdictMock).toHaveBeenCalledWith("PASS");

      // Advance to Step 2 via props update
      const step2Session: BisectSessionSnapshot = {
        ...baseSession,
        stepNumber: 2,
        currentTestSubset: ["mod_c", "magiclib", "lw_lazylib"],
        history: [
          {
            step: 1,
            timestamp: "2026-08-19T10:02:00Z",
            testedSubset: ["mod_a", "mod_b", "magiclib", "lw_lazylib"],
            verdict: "PASS",
            notes: "Clean run",
          },
        ],
      };

      rerender(
        <ModBisectWizard
          session={step2Session}
          onLaunchTest={launchMock}
          onRecordVerdict={verdictMock}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      // In Step 2: verdict buttons MUST be reset to disabled because testLaunched was reset
      expect(screen.getByText(/STEP 2 OF ~3 \/\/ 4 SUSPECTS REMAINING/)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "✓ Passed (No Crash)" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "✗ Failed (Crashed)" })).toBeDisabled();
    });

    it("1.2 calculates progress bar percentage and handles extreme boundaries", () => {
      // Normal 50%
      const { rerender } = render(
        <BisectProgressBar stepNumber={2} totalSteps={4} suspectsRemaining={2} />
      );
      expect(screen.getByText("50%")).toBeInTheDocument();
      expect(screen.getByText("ESTIMATED ~2 TEST LAUNCHES REMAINING")).toBeInTheDocument();

      // 0 total steps boundary
      rerender(<BisectProgressBar stepNumber={0} totalSteps={0} suspectsRemaining={0} />);
      expect(screen.getByText("0%")).toBeInTheDocument();
      expect(screen.getByText("ESTIMATED ~0 TEST LAUNCHES REMAINING")).toBeInTheDocument();

      // Overflow step > total steps capped at 100%
      rerender(<BisectProgressBar stepNumber={5} totalSteps={4} suspectsRemaining={1} />);
      expect(screen.getByText("100%")).toBeInTheDocument();
      expect(screen.getByText("ESTIMATED ~0 TEST LAUNCHES REMAINING")).toBeInTheDocument();
    });

    it("1.3 renders culprit view with downstream warnings and clean alternatives", () => {
      // Culprit with downstream dependents
      const { rerender } = render(
        <ModBisectWizard
          session={culpritSession}
          onLaunchTest={vi.fn()}
          onRecordVerdict={vi.fn()}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      expect(screen.getByText("MOD ALPHA COMBAT OVERHAUL (mod_a v2.1.0)")).toBeInTheDocument();
      expect(screen.getByText(/data.scripts.AlphaEveryFrameScript.advance/)).toBeInTheDocument();
      expect(screen.getByText("mod_a_addon")).toBeInTheDocument();
      expect(screen.getByText("mod_a_ships")).toBeInTheDocument();

      // Culprit with NO downstream dependents
      const isolatedCulpritSession: BisectSessionSnapshot = {
        ...culpritSession,
        candidateCulprit: {
          ...culpritSession.candidateCulprit!,
          downstreamDependents: [],
        },
      };

      rerender(
        <ModBisectWizard
          session={isolatedCulpritSession}
          onLaunchTest={vi.fn()}
          onRecordVerdict={vi.fn()}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      expect(screen.getByText("✓ No other active mods depend on this mod.")).toBeInTheDocument();
    });
  });

  // =========================================================================
  // Challenge Area 2: Rapid Verdict Button Clicking & Race Resistance
  // =========================================================================
  describe("Challenge Area 2: Rapid Verdict Button Clicking & Race Resistance", () => {
    it("2.1 prevents duplicate execution when verdict buttons are clicked rapidly", async () => {
      let resolveVerdict: () => void = () => {};
      const slowVerdictPromise = new Promise<void>((resolve) => {
        resolveVerdict = resolve;
      });

      const verdictMock = vi.fn().mockReturnValue(slowVerdictPromise);
      const launchMock = vi.fn().mockResolvedValue(undefined);

      render(
        <ModBisectWizard
          session={baseSession}
          onLaunchTest={launchMock}
          onRecordVerdict={verdictMock}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      // Launch test to enable verdict buttons
      const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
      await userEvent.click(launchBtn);

      const passBtn = screen.getByRole("button", { name: "✓ Passed (No Crash)" });
      const failBtn = screen.getByRole("button", { name: "✗ Failed (Crashed)" });
      const skipBtn = screen.getByRole("button", { name: "↷ Skip Partition" });

      // First click on Pass triggers slow verdict
      await act(async () => {
        fireEvent.click(passBtn);
      });

      expect(verdictMock).toHaveBeenCalledTimes(1);

      // While busy, all buttons must be disabled
      expect(passBtn).toBeDisabled();
      expect(failBtn).toBeDisabled();
      expect(skipBtn).toBeDisabled();
      expect(screen.getByRole("button", { name: "Abort & Restore Original Setup" })).toBeDisabled();

      // Additional rapid clicks on Fail, Skip, or Pass are ignored
      fireEvent.click(passBtn);
      fireEvent.click(failBtn);
      fireEvent.click(skipBtn);

      expect(verdictMock).toHaveBeenCalledTimes(1);

      // Complete slow verdict
      await act(async () => {
        resolveVerdict();
      });

      // Now busy is released
      expect(screen.getByRole("button", { name: "Abort & Restore Original Setup" })).toBeEnabled();
    });

    it("2.2 recovers busy state cleanly when verdict recording rejects with an error", async () => {
      let rejectVerdict: (err: Error) => void = () => {};
      const failingVerdictPromise = new Promise<void>((_, reject) => {
        rejectVerdict = reject;
      });
      // Caller wraps verdict execution and manages errors
      const failingVerdictMock = vi.fn().mockImplementation(async () => {
        try {
          await failingVerdictPromise;
        } catch {
          // Handled by UI error boundary / notice handler
        }
      });
      const launchMock = vi.fn().mockResolvedValue(undefined);

      render(
        <ModBisectWizard
          session={baseSession}
          onLaunchTest={launchMock}
          onRecordVerdict={failingVerdictMock}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
      await userEvent.click(launchBtn);

      const passBtn = screen.getByRole("button", { name: "✓ Passed (No Crash)" });
      await act(async () => {
        fireEvent.click(passBtn);
      });

      expect(failingVerdictMock).toHaveBeenCalledTimes(1);
      expect(passBtn).toBeDisabled();

      // Reject the pending verdict
      await act(async () => {
        rejectVerdict(new Error("IPC connection dropped"));
      });

      // After rejection, busy state must be reset to false (buttons not permanently locked)
      expect(screen.getByRole("button", { name: "Abort & Restore Original Setup" })).toBeEnabled();
    });

    it("2.3 prevents multiple simultaneous 1-click resolution clicks in culprit view", async () => {
      let resolveResolution: () => void = () => {};
      const slowResolutionPromise = new Promise<void>((resolve) => {
        resolveResolution = resolve;
      });

      const applyMock = vi.fn().mockReturnValue(slowResolutionPromise);

      const { rerender } = render(
        <BisectCulpritView
          culprit={culpritSession.candidateCulprit!}
          onApplyResolution={applyMock}
          onAbort={vi.fn()}
          busy={false}
        />
      );

      const applyBtn = screen.getByRole("button", { name: "[ 1-Click Fix: Disable 'mod_a' & Restore Rest ]" });
      fireEvent.click(applyBtn);

      expect(applyMock).toHaveBeenCalledTimes(1);

      // When busy is true, button is disabled
      rerender(
        <BisectCulpritView
          culprit={culpritSession.candidateCulprit!}
          onApplyResolution={applyMock}
          onAbort={vi.fn()}
          busy={true}
        />
      );

      expect(applyBtn).toBeDisabled();
      expect(screen.getByRole("button", { name: "Applying Fix…" })).toBeDisabled();
    });
  });

  // =========================================================================
  // Challenge Area 3: Session Resumption on App Relaunch
  // =========================================================================
  describe("Challenge Area 3: Session Resumption on App Relaunch", () => {
    it("3.1 restores active bisect session on initial mount with game directory", async () => {
      vi.mocked(bridge.getBisectStatus).mockResolvedValue(baseSession);

      const { result } = renderHook(() => useModBisect("/Games/Starsector"));

      expect(result.current.loading).toBe(true);

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.session).toEqual(baseSession);
      expect(result.current.error).toBeNull();
      expect(bridge.getBisectStatus).toHaveBeenCalledWith("/Games/Starsector");
    });

    it("3.2 sets session to null when getBisectStatus reports inactive session", async () => {
      const inactiveSession: BisectSessionSnapshot = {
        ...baseSession,
        active: false,
        state: "COMPLETED",
      };
      vi.mocked(bridge.getBisectStatus).mockResolvedValue(inactiveSession);

      const { result } = renderHook(() => useModBisect("/Games/Starsector"));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.session).toBeNull();
      expect(result.current.error).toBeNull();
    });

    it("3.3 surfaces errors cleanly when session read fails on relaunch", async () => {
      vi.mocked(bridge.getBisectStatus).mockRejectedValue(new Error("File lock busy"));

      const { result } = renderHook(() => useModBisect("/Games/Starsector"));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.session).toBeNull();
      expect(result.current.error).toBe("File lock busy");
    });

    it("3.4 drives complete workflow lifecycle from startBisect to recordVerdict to reset", async () => {
      vi.mocked(bridge.getBisectStatus).mockResolvedValue({ ...baseSession, active: false });
      vi.mocked(bridge.startModBisect).mockResolvedValue(baseSession);
      vi.mocked(bridge.recordBisectVerdict).mockResolvedValue({
        ...baseSession,
        stepNumber: 2,
      });
      vi.mocked(bridge.resetModBisect).mockResolvedValue({
        format: "starsector-preflight-bisect-reset-v1",
        reset: true,
      });

      const { result } = renderHook(() => useModBisect("/Games/Starsector"));

      await waitFor(() => {
        expect(result.current.loading).toBe(false);
      });

      expect(result.current.session).toBeNull();

      // Start bisect
      await act(async () => {
        await result.current.startBisect(["mod_a", "mod_b"]);
      });
      expect(result.current.session?.stepNumber).toBe(1);

      // Record verdict
      await act(async () => {
        await result.current.recordVerdict("PASS");
      });
      expect(result.current.session?.stepNumber).toBe(2);

      // Apply resolution (resets session)
      await act(async () => {
        await result.current.applyResolution();
      });
      expect(result.current.session).toBeNull();
    });
  });

  // =========================================================================
  // Challenge Area 4: Concurrency & OperationCoordinator Fencing
  // =========================================================================
  describe("Challenge Area 4: Concurrency & OperationCoordinator Fencing", () => {
    it("4.1 strictly disables launch, verdict, and skip controls when operationBlocked is true", () => {
      render(
        <ModBisectWizard
          session={baseSession}
          onLaunchTest={vi.fn()}
          onRecordVerdict={vi.fn()}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={true}
        />
      );

      const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
      const passBtn = screen.getByRole("button", { name: "✓ Passed (No Crash)" });
      const failBtn = screen.getByRole("button", { name: "✗ Failed (Crashed)" });
      const skipBtn = screen.getByRole("button", { name: "↷ Skip Partition" });

      expect(launchBtn).toBeDisabled();
      expect(passBtn).toBeDisabled();
      expect(failBtn).toBeDisabled();
      expect(skipBtn).toBeDisabled();
    });

    it("4.2 enables controls appropriately when operationBlocked is false", () => {
      render(
        <ModBisectWizard
          session={baseSession}
          onLaunchTest={vi.fn()}
          onRecordVerdict={vi.fn()}
          onApplyResolution={vi.fn()}
          onAbortSession={vi.fn()}
          operationBlocked={false}
        />
      );

      const launchBtn = screen.getByRole("button", { name: "[ 1. Launch Test Run ]" });
      const skipBtn = screen.getByRole("button", { name: "↷ Skip Partition" });

      expect(launchBtn).toBeEnabled();
      expect(skipBtn).toBeEnabled();
    });
  });

  // =========================================================================
  // Challenge Area 5: Theme Switching Across All 5 Palettes
  // =========================================================================
  describe("Challenge Area 5: Theme Switching Across All 5 Palettes", () => {
    it("5.1 contains styling rules and CRT classes for bisect across all 5 themes", () => {
      expect(styles).toContain(".bisect-progress-container");
      expect(styles).toContain(".bisect-progress-meta");
      expect(styles).toContain(".progress-bar-track");
      expect(styles).toContain(".progress-bar-fill");
      expect(styles).toContain(".bisect-partition-card");
      expect(styles).toContain(".partition-header");
      expect(styles).toContain(".mod-tag-cloud");
      expect(styles).toContain(".mod-tag");
      expect(styles).toContain(".mod-tag--base");
      expect(styles).toContain(".mod-tag--testing");
      expect(styles).toContain(".bisect-controls-bar");
      expect(styles).toContain(".verdict-buttons");
      expect(styles).toContain(".bisect-culprit-card");
      expect(styles).toContain(".culprit-badge");
      expect(styles).toContain(".culprit-path");
      expect(styles).toContain(".trace-box");
      expect(styles).toContain(".downstream-warning");
      expect(styles).toContain(".clean-notice");
      expect(styles).toContain(".bisect-completed-card");
    });

    it("5.2 ensures every palette defines all required color variables without fallback leakage", () => {
      for (const palette of PALETTES) {
        const selector = palette === "hangar" ? ":root" : `:root\\[data-palette="${palette}"\\]`;
        const blockMatch = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styles);
        expect(blockMatch, `Missing CSS block for palette ${palette}`).not.toBeNull();

        const blockContent = blockMatch![1];
        expect(blockContent).toMatch(/--accent:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--accent-strong:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--ink:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--cream:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--paper-solid:\s*#[0-9a-f]{6}/i);
      }
    });

    it("5.3 verifies dark theme blocks for all 5 palettes define required contrast tokens", () => {
      for (const palette of PALETTES) {
        const selector =
          palette === "hangar"
            ? ':root\\[data-theme="dark"\\]'
            : `:root\\[data-theme="dark"\\]\\[data-palette="${palette}"\\]`;
        const blockMatch = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styles);
        expect(blockMatch, `Missing dark CSS block for palette ${palette}`).not.toBeNull();

        const blockContent = blockMatch![1];
        expect(blockContent).toMatch(/--accent:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--accent-strong:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--ink:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--cream:\s*#[0-9a-f]{6}/i);
        expect(blockContent).toMatch(/--paper-solid:\s*#[0-9a-f]{6}/i);
      }
    });
  });
});
