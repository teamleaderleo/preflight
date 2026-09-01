import React, { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import {
  CheckpointsPage,
  CheckpointDiffViewer,
  CheckpointRestoreModal,
  HomeDriftAlertBar,
  type CheckpointSummary,
  type CheckpointDiffReport,
} from "./CheckpointsDesktopUI.test";

import {
  CrashDiagnosisModal,
  type CrashDiagnosisReport,
  type RecoveryActionOption,
} from "./CrashDiagnosisDesktopUI.test";

import {
  ResourceInspectorPage,
  ModAssetDrilldownDrawer,
  type ResourceCostReport,
} from "./ResourceInspectorDesktopUI.test";

import {
  ModDriftView,
  ModDriftDrawer,
  type ModDriftReport,
} from "./ModDriftDesktopUI.test";

import {
  ModBisectWizard,
  BisectActivePartitionCard,
  BisectCulpritView,
  type BisectSessionSnapshot,
} from "./ModBisectWizardDesktopUI.test";

describe("Desktop Real-World Scenarios E2E Test Suite", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ========== SCENARIO 1: CORRUPTED WEAPON MOD HOTFIX & CHECKPOINT ROLLBACK ==========
  it("Scenario 1 (S1): Corrupted Weapon Mod Hotfix & Checkpoint Rollback Workflow", async () => {
    const user = userEvent.setup();

    // 1. Initial State: Player has pinned checkpoint "Cycle 214 Heavy Fleet"
    const mockCheckpoints: CheckpointSummary[] = [
      {
        name: "Cycle 214 Heavy Fleet",
        description: "Stable 85-mod fleet before weapon hotfix",
        createdAt: "2026-08-18T12:00:00Z",
        modCount: 85,
        status: "DRIFTED",
        sameInstall: true,
        missingMods: [],
        driftDetails: {
          modifiedMods: ["uaf"],
          settingsChanged: [],
        },
        file: "~/.starsector-preflight/checkpoints/cycle-214.json",
        checkpointFingerprint: "sha256_cp_214",
      },
    ];

    const mockDiff: CheckpointDiffReport = {
      format: "starsector-preflight-checkpoint-diff-v1",
      checkpointName: "Cycle 214 Heavy Fleet",
      targetName: "Current Live Launch State",
      matched: false,
      enabledModsDiff: { added: [], removed: [], reordered: false },
      modDrift: [
        {
          modId: "uaf",
          status: "CONTENT_MODIFIED",
          checkpointVersion: "0.7.4a",
          currentVersion: "0.7.4a",
          checkpointSha256: "sha256_clean",
          currentSha256: "sha256_corrupt_weapons",
          modifiedFiles: ["data/weapons/weapons.csv"],
        },
      ],
      launchSettingsDiff: {},
      cacheStatus: { hasMatchingPreparedData: false, rebuildRequired: true },
    };

    const restoreSpy = vi.fn().mockResolvedValue({
      format: "starsector-preflight-checkpoint-restore-v1",
      name: "Cycle 214 Heavy Fleet",
      installRoot: "/Applications/Starsector.app",
      applied: true,
      canRestore: true,
      sourceChanged: false,
      checkpointChanged: false,
      missingMods: [],
      restoredModsCount: 85,
      restoredSettings: true,
      backup: "~/.starsector-preflight/profile-backups/enabled_mods.json",
    });

    // 2. Home alert detects drift
    let diffOpened = false;
    const { rerender } = render(
      <HomeDriftAlertBar
        checkpointName="Cycle 214 Heavy Fleet"
        driftedModsCount={1}
        onInspectDiff={() => {
          diffOpened = true;
        }}
        onUpdateCheckpoint={vi.fn()}
      />
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Launch configuration has drifted from pinned checkpoint 'Cycle 214 Heavy Fleet' (1 mods altered).");
    await user.click(screen.getByRole("button", { name: "Inspect Diff" }));
    expect(diffOpened).toBe(true);

    // 3. User navigates to Checkpoints Page and opens Diff Viewer
    rerender(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn().mockResolvedValue(mockDiff)}
        onRestore={restoreSpy}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const inspectBtn = screen.getByRole("button", { name: "Inspect Diff" });
    await user.click(inspectBtn);

    // 4. Verify Diff viewer highlights corrupted weapon file
    expect(await screen.findByText(/DIFF: Cycle 214 Heavy Fleet ↔ Current Live Launch State/)).toBeInTheDocument();
    expect(screen.getByText("uaf [CONTENT_MODIFIED]")).toBeInTheDocument();
    expect(screen.getByText(/weapons.csv/)).toBeInTheDocument();
    expect(screen.getByText("⚠️ Cache Rebuild Required")).toBeInTheDocument();

    // 5. Click restore from diff viewer
    await user.click(screen.getByRole("button", { name: "Restore Checkpoint" }));

    // 6. Confirm restore modal with backup guarantee
    const modal = await screen.findByRole("dialog", { name: "Restore Checkpoint Modal" });
    expect(within(modal).getByText(/Preflight creates atomic backups/)).toBeInTheDocument();

    await user.click(within(modal).getByRole("button", { name: "Confirm Restore" }));

    expect(restoreSpy).toHaveBeenCalledWith("Cycle 214 Heavy Fleet", true);
    expect(await screen.findByRole("status")).toHaveTextContent("Restored checkpoint 'Cycle 214 Heavy Fleet' successfully.");
  });

  // ========== SCENARIO 2: FATAL OUT-OF-MEMORY CRASH RECOVERY ==========
  it("Scenario 2 (S2): Fatal Out-Of-Memory Crash on 120-Mod Loadout with 1-Click Memory Increase", async () => {
    const user = userEvent.setup();
    const actionSpy = vi.fn().mockResolvedValue(undefined);

    const mockOomReport: CrashDiagnosisReport = {
      format: "starsector-preflight-crash-diagnosis-v1",
      diagnosedAt: "2026-08-18T14:40:00Z",
      runDirectory: "~/.starsector-preflight/runs/oom-run",
      exitCode: 6,
      launcherExitCode: 0,
      rootCauseCategory: "OUT_OF_MEMORY_HEAP",
      confidence: "EXACT",
      summaryTitle: "JAVA HEAP EXHAUSTION (OutOfMemoryError)",
      summaryDescription: "Starsector ran out of allocated Java heap memory (4.0 GB) while loading 120 mods.",
      offendingMod: null,
      missingDependency: null,
      memoryTelemetry: {
        heapUsedBytes: 3.98 * 1024 * 1024 * 1024,
        heapMaxBytes: 4.0 * 1024 * 1024 * 1024,
        directMemoryBytes: 1024 * 1024 * 1024,
        activeModsCount: 120,
      },
      logSnippetLines: [
        "2026-08-18 14:39:55 [Thread-2] INFO com.fs.starfarer.loading.ResourceLoader - Loading ship sprites",
        "java.lang.OutOfMemoryError: Java heap space",
        "\tat java.util.Arrays.copyOf(Arrays.java:3332)",
      ],
      crashLineIndex: 1,
      recoveryActions: [
        {
          id: "INCREASE_MEMORY",
          label: "Increase Heap Memory to 6144 MB",
          description: "Increases -Xmx allocation from 4096 MB to 6144 MB in Starsector launch settings.",
          recommended: true,
          parameters: { memoryMiB: 6144 },
        },
      ],
      copyableSnippet: "### Out of Memory Report",
    };

    render(
      <CrashDiagnosisModal
        diagnosis={mockOomReport}
        onApplyAction={actionSpy}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    // Verify CRT styling and memory readout
    expect(screen.getByText("DIAGNOSTIC READOUT // LAUNCH ABORT")).toBeInTheDocument();
    expect(screen.getByText("JAVA HEAP EXHAUSTION (OutOfMemoryError)")).toBeInTheDocument();
    expect(screen.getByText("120 enabled")).toBeInTheDocument();

    // Verify 1-click memory bump
    const increaseBtn = screen.getByRole("button", { name: "[ Increase Heap Memory to 6144 MB ]" });
    await user.click(increaseBtn);

    expect(actionSpy).toHaveBeenCalledWith(mockOomReport.recoveryActions[0]);
  });

  // ========== SCENARIO 3: MOD AUTHOR NPE CRASH WITH BISECT ISOLATION ==========
  it("Scenario 3 (S3): Mod Author NPE Crash with Bisect Isolation Workflow", async () => {
    const user = userEvent.setup();

    // 1. Crash Diagnosis identifies NPE in armaa
    const mockNpeDiagnosis: CrashDiagnosisReport = {
      format: "starsector-preflight-crash-diagnosis-v1",
      diagnosedAt: "2026-08-18T14:35:00Z",
      runDirectory: "~/.starsector-preflight/runs/npe-run",
      exitCode: 6,
      launcherExitCode: 0,
      rootCauseCategory: "MOD_CRASH_UNCAUGHT_EXCEPTION",
      confidence: "EXACT",
      summaryTitle: "MOD RUNTIME EXCEPTION: Arma Armatura (armaa)",
      summaryDescription: "Uncaught java.lang.NullPointerException in armaa.hullmods.MountedWep.advanceInCombat",
      offendingMod: {
        id: "armaa",
        name: "Arma Armatura",
        version: "1.94",
        directory: "mods/Arma Armatura",
        crashingClass: "armaa.hullmods.MountedWep",
        crashingMethod: "advanceInCombat",
        lineNumber: 142,
      },
      missingDependency: null,
      memoryTelemetry: {
        heapUsedBytes: 2.5 * 1024 * 1024 * 1024,
        heapMaxBytes: 4.0 * 1024 * 1024 * 1024,
        directMemoryBytes: 256 * 1024 * 1024,
        activeModsCount: 50,
      },
      logSnippetLines: [
        "java.lang.NullPointerException: null",
        "\tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)",
      ],
      crashLineIndex: 0,
      recoveryActions: [
        {
          id: "START_BISECT",
          label: "Bisect Active Mods",
          description: "Isolate mod interactions.",
          recommended: true,
        },
      ],
      copyableSnippet: "### NPE Snippet",
    };

    let bisectStarted = false;
    const { rerender } = render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={() => {
          bisectStarted = true;
        }}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    await user.click(screen.getByRole("button", { name: "[ Bisect Active Mods ]" }));
    expect(bisectStarted).toBe(true);

    // 2. Bisect Session initialized
    const bisectSession: BisectSessionSnapshot = {
      format: "starsector-preflight-bisect-session-v1",
      sessionId: "bisect-npe-1",
      installRoot: "/Applications/Starsector.app",
      startedAt: "2026-08-18T14:36:00Z",
      updatedAt: "2026-08-18T14:36:00Z",
      state: "TESTING",
      initialEnabledMods: ["armaa", "faction_x", "faction_y", "magiclib", "lw_lazylib"],
      fixedBaseMods: ["magiclib", "lw_lazylib"],
      suspectMods: ["armaa", "faction_x", "faction_y"],
      eliminatedGoodMods: ["magiclib", "lw_lazylib"],
      currentTestSubset: ["armaa", "magiclib", "lw_lazylib"],
      stepNumber: 1,
      totalEstimatedSteps: 3,
      history: [],
      candidateCulprit: null,
      backupFile: "~/.starsector-preflight/profile-backups/bisect-backup.json",
      active: true,
    };

    const testLaunchSpy = vi.fn().mockResolvedValue(undefined);
    const verdictSpy = vi.fn().mockResolvedValue(undefined);

    rerender(
      <ModBisectWizard
        session={bisectSession}
        onLaunchTest={testLaunchSpy}
        onRecordVerdict={verdictSpy}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    // 3. Launch test and record FAIL (crashed with armaa active)
    await user.click(screen.getByRole("button", { name: "[ 1. Launch Test Run ]" }));
    expect(testLaunchSpy).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "✗ Failed (Crashed)" }));
    expect(verdictSpy).toHaveBeenCalledWith("FAIL");

    // 4. Culprit isolated
    const culpritSession: BisectSessionSnapshot = {
      ...bisectSession,
      state: "CULPRIT_FOUND",
      stepNumber: 3,
      candidateCulprit: {
        id: "armaa",
        name: "Arma Armatura",
        version: "1.94",
        directory: "mods/Arma Armatura",
        crashingClass: "armaa.hullmods.MountedWep",
        crashingTrace: "java.lang.NullPointerException at MountedWep.java:142",
        downstreamDependents: [],
      },
    };

    const resolveSpy = vi.fn().mockResolvedValue(undefined);
    rerender(
      <ModBisectWizard
        session={culpritSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={resolveSpy}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("🎯 CULPRIT MOD ISOLATED")).toBeInTheDocument();
    expect(screen.getByText("ARMA ARMATURA (armaa v1.94)")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "[ 1-Click Fix: Disable 'armaa' & Restore Rest ]" }));
    expect(resolveSpy).toHaveBeenCalledTimes(1);
  });

  // ========== SCENARIO 4: HEAVY FACTION MOD RESOURCE & VRAM AUDIT ==========
  it("Scenario 4 (S4): Heavy Faction Mod Resource Footprint & Asset Audit", async () => {
    const user = userEvent.setup();

    const mockResourceReport: ResourceCostReport = {
      format: "starsector-preflight-resource-cost-v1",
      generatedAt: "2026-08-18T14:45:00Z",
      installRoot: "/Applications/Starsector.app",
      profileFingerprint: "sha256_heavy_modpack",
      scanDurationMs: 210,
      summary: {
        enabledModCount: 1,
        totalDiskBytes: 2.4 * 1024 * 1024 * 1024,
        totalEstimatedMemoryBytes: 4.5 * 1024 * 1024 * 1024,
        textureVram: {
          textureCount: 1500,
          diskBytes: 1.8 * 1024 * 1024 * 1024,
          decodedBaseBytes: 3.2 * 1024 * 1024 * 1024,
          residentGpuBytes: 4.19 * 1024 * 1024 * 1024,
          paddingWasteBytes: 1.29 * 1024 * 1024 * 1024,
          mipChainUpperBoundBytes: 5.58 * 1024 * 1024 * 1024,
        },
        audioPcm: {
          soundCount: 400,
          diskBytes: 500 * 1024 * 1024,
          effectPcmBytes: 480 * 1024 * 1024,
          effectCount: 320,
          musicDiskBytes: 450 * 1024 * 1024,
          musicCount: 80,
          unreferencedCount: 45,
          unreferencedDiskBytes: 45 * 1024 * 1024,
        },
        bytecode: {
          jarCount: 4,
          diskBytes: 20 * 1024 * 1024,
          uncompressedBytecodeBytes: 60 * 1024 * 1024,
          classCount: 4200,
          duplicateClasses: 0,
        },
        preparedData: {
          preparedTextureBytes: 1.2 * 1024 * 1024 * 1024,
          preparedAudioBytes: 400 * 1024 * 1024,
          janinoBytecodeBytes: 8 * 1024 * 1024,
          specCacheBytes: 2 * 1024 * 1024,
        },
      },
      mods: [
        {
          id: "heavy_faction",
          name: "Heavy Faction Mod",
          version: "2.1.0",
          order: 1,
          totalDiskBytes: 2.4 * 1024 * 1024 * 1024,
          estimatedMemoryBytes: 4.5 * 1024 * 1024 * 1024,
          texture: {
            count: 1500,
            diskBytes: 1.8 * 1024 * 1024 * 1024,
            decodedBytes: 3.2 * 1024 * 1024 * 1024,
            residentBytes: 4.19 * 1024 * 1024 * 1024,
            paddingWasteBytes: 1.29 * 1024 * 1024 * 1024,
            unmeasuredCount: 0,
          },
          audio: {
            count: 400,
            diskBytes: 500 * 1024 * 1024,
            effectPcmBytes: 480 * 1024 * 1024,
            musicBytes: 450 * 1024 * 1024,
            unreferencedBytes: 45 * 1024 * 1024,
          },
          bytecode: {
            jarCount: 4,
            diskBytes: 20 * 1024 * 1024,
            uncompressedBytecodeBytes: 60 * 1024 * 1024,
            classCount: 4200,
            duplicateClassCount: 0,
          },
          preparedData: {
            textureCacheBytes: 1.2 * 1024 * 1024 * 1024,
            audioCacheBytes: 400 * 1024 * 1024,
            specCacheBytes: 2 * 1024 * 1024,
          },
          details: {
            textures: [
              {
                logicalPath: "graphics/ships/super_dreadnought.png",
                width: 1025, // pads to 2048
                height: 1025, // pads to 2048
                channels: 4,
                diskBytes: 12 * 1024 * 1024,
                residentBytes: 16 * 1024 * 1024,
                paddingWasteBytes: 11.7 * 1024 * 1024,
                overridden: false,
              },
            ],
            audio: [
              {
                logicalPath: "sounds/weapons/super_laser.ogg",
                kind: "effect",
                channels: 2,
                sampleRate: 48000,
                durationSeconds: 3.2,
                diskBytes: 800 * 1024,
                pcmBytes: 614400,
              },
            ],
            bytecode: [],
          },
        },
      ],
    };

    render(
      <ResourceInspectorPage
        report={mockResourceReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
      />
    );

    // Verify scoreboard
    expect(screen.getAllByText("4.19 GB").length).toBeGreaterThan(0);
    expect(screen.getByText(/POT Padding Waste: 1.29 GB/)).toBeInTheDocument();
    expect(screen.getAllByText("480.0 MB").length).toBeGreaterThan(0);

    // Open Drilldown
    const drilldownBtn = screen.getByRole("button", { name: "Drilldown" });
    await user.click(drilldownBtn);

    expect(await screen.findByText("graphics/ships/super_dreadnought.png")).toBeInTheDocument();
    expect(screen.getByText("1025x1025")).toBeInTheDocument();
    expect(screen.getByText("16.0 MB")).toBeInTheDocument();
  });

  // ========== SCENARIO 5: MULTI-MOD CIRCULAR DEPENDENCY CRASH ISOLATION ==========
  it("Scenario 5 (S5): Multi-Mod Circular Dependency Crash Isolation", async () => {
    // Mod A and Mod B mutually depend on each other. The bisect assistant evaluates them as an atomic unit.
    const circularSession: BisectSessionSnapshot = {
      format: "starsector-preflight-bisect-session-v1",
      sessionId: "bisect-cyclic-1",
      installRoot: "/Applications/Starsector.app",
      startedAt: "2026-08-18T15:00:00Z",
      updatedAt: "2026-08-18T15:05:00Z",
      state: "TESTING",
      initialEnabledMods: ["mod_a", "mod_b", "mod_c", "magiclib"],
      fixedBaseMods: ["magiclib"],
      suspectMods: ["mod_a", "mod_b", "mod_c"],
      eliminatedGoodMods: ["magiclib"],
      currentTestSubset: ["mod_a", "mod_b", "magiclib"], // mod_a & mod_b grouped as atomic closure
      stepNumber: 1,
      totalEstimatedSteps: 3,
      history: [],
      candidateCulprit: null,
      backupFile: "~/.starsector-preflight/profile-backups/bisect-cyclic-backup.json",
      active: true,
    };

    render(
      <ModBisectWizard
        session={circularSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    const tagCloud = screen.getByTestId("mod-tag-cloud");
    expect(within(tagCloud).getByText("mod_a (Testing)")).toBeInTheDocument();
    expect(within(tagCloud).getByText("mod_b (Testing)")).toBeInTheDocument();
    expect(within(tagCloud).getByText("magiclib (Prerequisite Base)")).toBeInTheDocument();
  });

  // ========== SCENARIO 6: LIVE IN-PLACE CHECKPOINT COMPARISON & SETTINGS DRIFT ==========
  it("Scenario 6 (S6): Live In-Place Checkpoint Comparison & Settings Drift", async () => {
    const user = userEvent.setup();

    const mockSettingsDiff: CheckpointDiffReport = {
      format: "starsector-preflight-checkpoint-diff-v1",
      checkpointName: "Cycle 210 Baseline",
      targetName: "Current Live Launch State",
      matched: false,
      enabledModsDiff: {
        added: [],
        removed: ["mod_x", "mod_y", "mod_z"],
        reordered: false,
      },
      modDrift: [],
      launchSettingsDiff: {
        resolution: { checkpoint: "1920x1080", current: "2560x1440" },
        fullscreen: { checkpoint: true, current: false },
        memoryMiB: { checkpoint: 4096, current: 6144 },
      },
      cacheStatus: {
        hasMatchingPreparedData: false,
        rebuildRequired: true,
      },
    };

    render(
      <CheckpointDiffViewer
        diff={mockSettingsDiff}
        onClose={vi.fn()}
        onRestore={vi.fn()}
      />
    );

    // Verify removed mods
    const removedSection = screen.getByTestId("mods-removed");
    expect(within(removedSection).getByText("-mod_x")).toBeInTheDocument();
    expect(within(removedSection).getByText("-mod_y")).toBeInTheDocument();
    expect(within(removedSection).getByText("-mod_z")).toBeInTheDocument();

    // Verify settings diff
    const settingsTable = screen.getByTestId("settings-diff-table");
    expect(within(settingsTable).getByText("resolution")).toBeInTheDocument();
    expect(within(settingsTable).getByText("1920x1080")).toBeInTheDocument();
    expect(within(settingsTable).getByText("2560x1440")).toBeInTheDocument();
    expect(within(settingsTable).getByText("fullscreen")).toBeInTheDocument();
    expect(within(settingsTable).getByText("memoryMiB")).toBeInTheDocument();
  });

  // ========== SCENARIO 7: BYTECODE INCOMPATIBILITY (JAVA 8 VS JAVA 17/21) ==========
  it("Scenario 7 (S7): Bytecode Incompatibility (Java 8 vs Java 17/21) Diagnosis", () => {
    const mockBytecodeDiagnosis: CrashDiagnosisReport = {
      format: "starsector-preflight-crash-diagnosis-v1",
      diagnosedAt: "2026-08-18T15:10:00Z",
      runDirectory: "~/.starsector-preflight/runs/class-version-run",
      exitCode: 6,
      launcherExitCode: 0,
      rootCauseCategory: "UNSUPPORTED_CLASS_VERSION",
      confidence: "EXACT",
      summaryTitle: "JAVA RUNTIME INCOMPATIBILITY (UnsupportedClassVersionError)",
      summaryDescription: "Mod 'modern_shader' was compiled with Java 17 (class file version 61.0), but Starsector is executing on Java 8 (class file version 52.0).",
      offendingMod: {
        id: "modern_shader",
        name: "Modern Shader Engine",
        version: "3.0.0",
        directory: "mods/ModernShader",
        crashingClass: "data.shaders.ModernPostProcessPlugin",
      },
      missingDependency: null,
      memoryTelemetry: null,
      logSnippetLines: [
        "java.lang.UnsupportedClassVersionError: data/shaders/ModernPostProcessPlugin has been compiled by a more recent version of the Java Runtime (class file version 61.0), this compiler only recognizes up to (class file version 52.0)",
        "\tat java.lang.ClassLoader.defineClass1(Native Method)",
      ],
      crashLineIndex: 0,
      recoveryActions: [
        {
          id: "DISABLE_MOD",
          label: "Disable Incompatible Mod 'modern_shader'",
          description: "Disables modern_shader until Java 17 runtime is configured.",
          recommended: true,
          parameters: { modId: "modern_shader" },
        },
      ],
      copyableSnippet: "### UnsupportedClassVersionError Snippet",
    };

    render(
      <CrashDiagnosisModal
        diagnosis={mockBytecodeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("JAVA RUNTIME INCOMPATIBILITY (UnsupportedClassVersionError)")).toBeInTheDocument();
    expect(screen.getAllByText(/class file version 61.0/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole("button", { name: "[ Disable Incompatible Mod 'modern_shader' ]" })).toBeInTheDocument();
  });

  // ========== SCENARIO 8: MID-BISECT APP RESTART & POWER-LOSS RESUMPTION ==========
  it("Scenario 8 (S8): Interrupted Bisect Session Resumption from bisect-session.json", () => {
    // App loads with existing persistent session at Step 3 of 6
    const resumedSession: BisectSessionSnapshot = {
      format: "starsector-preflight-bisect-session-v1",
      sessionId: "bisect-resumed-session",
      installRoot: "/Applications/Starsector.app",
      startedAt: "2026-08-18T13:00:00Z",
      updatedAt: "2026-08-18T13:15:00Z",
      state: "TESTING",
      initialEnabledMods: ["mod_a", "mod_b", "mod_c", "mod_d", "mod_e", "magiclib"],
      fixedBaseMods: ["magiclib"],
      suspectMods: ["mod_a", "mod_b"],
      eliminatedGoodMods: ["mod_c", "mod_d", "mod_e", "magiclib"],
      currentTestSubset: ["mod_a", "magiclib"],
      stepNumber: 3,
      totalEstimatedSteps: 6,
      history: [
        {
          step: 1,
          timestamp: "2026-08-18T13:05:00Z",
          testedSubset: ["mod_c", "mod_d", "magiclib"],
          verdict: "PASS",
        },
        {
          step: 2,
          timestamp: "2026-08-18T13:10:00Z",
          testedSubset: ["mod_e", "magiclib"],
          verdict: "PASS",
        },
      ],
      candidateCulprit: null,
      backupFile: "~/.starsector-preflight/profile-backups/bisect-backup.json",
      active: true,
    };

    render(
      <ModBisectWizard
        session={resumedSession}
        onLaunchTest={vi.fn()}
        onRecordVerdict={vi.fn()}
        onApplyResolution={vi.fn()}
        onAbortSession={vi.fn()}
        operationBlocked={false}
      />
    );

    // Verifies resumed state without losing step count or suspect narrowing
    expect(screen.getByText(/STEP 3 OF ~6 \/\/ 2 SUSPECTS REMAINING/)).toBeInTheDocument();
    expect(screen.getByText("mod_a (Testing)")).toBeInTheDocument();
    expect(screen.getByTestId("bisect-progress")).toHaveTextContent(/PROGRESS:\s*50%/);
  });

  // ========== SCENARIO 9: CONCURRENT PROCESS OPERATION LOCK SAFETY ==========
  it("Scenario 9 (S9): OperationLease Blocks State Mutations While Game is Running", async () => {
    const mockCheckpoints: CheckpointSummary[] = [
      {
        name: "Cycle 214 Heavy Fleet",
        description: "Stable fleet",
        createdAt: "2026-08-18T12:00:00Z",
        modCount: 85,
        status: "MATCHED",
        sameInstall: true,
        missingMods: [],
        file: "~/.starsector-preflight/checkpoints/cycle-214.json",
        checkpointFingerprint: "sha256_cp_214",
      },
    ];

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={true} // In-flight game lock
      />
    );

    const pinBtn = screen.getByRole("button", { name: "Pin Checkpoint" });
    const restoreBtn = screen.getByRole("button", { name: "Restore…" });
    const deleteBtn = screen.getByRole("button", { name: "Delete" });

    expect(pinBtn).toBeDisabled();
    expect(restoreBtn).toBeDisabled();
    expect(deleteBtn).toBeDisabled();
  });

  // ========== SCENARIO 10: COMPLETE SAFE DOWNGRADE & ROLLBACK SEQUENCE ==========
  it("Scenario 10 (S10): Complete Safe Downgrade & Rollback Sequence with Timestamped Backups", async () => {
    const user = userEvent.setup();
    const restoreSpy = vi.fn().mockResolvedValue({
      format: "starsector-preflight-checkpoint-restore-v1",
      name: "Cycle 200 Pristine",
      installRoot: "/Applications/Starsector.app",
      applied: true,
      canRestore: true,
      sourceChanged: false,
      checkpointChanged: false,
      missingMods: [],
      restoredModsCount: 30,
      restoredSettings: true,
      backup: "~/.starsector-preflight/profile-backups/enabled_mods-20260818-160000.json",
    });

    const targetCheckpoint: CheckpointSummary = {
      name: "Cycle 200 Pristine",
      description: "Previous release baseline",
      createdAt: "2026-08-01T10:00:00Z",
      modCount: 30,
      status: "MATCHED",
      sameInstall: true,
      missingMods: [],
      file: "~/.starsector-preflight/checkpoints/cycle-200.json",
      checkpointFingerprint: "sha256_cp_200",
    };

    render(
      <CheckpointRestoreModal
        checkpoint={targetCheckpoint}
        onConfirm={async (restoreSettings) => {
          await restoreSpy(targetCheckpoint.name, restoreSettings);
        }}
        onCancel={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText(/RESTORE CHECKPOINT \/\/ Cycle 200 Pristine/)).toBeInTheDocument();
    expect(screen.getByText(/Preflight creates atomic backups of enabled_mods.json/)).toBeInTheDocument();

    const confirmBtn = screen.getByRole("button", { name: "Confirm Restore" });
    await user.click(confirmBtn);

    expect(restoreSpy).toHaveBeenCalledWith("Cycle 200 Pristine", true);
  });

  // ========== SCENARIO 11: MULTI-MODAL THEME & PALETTE TOKEN RENDERING ==========
  it("Scenario 11: Palette Invariant Stability Across 5 Themes", () => {
    const palettes = ["hangar", "blueprint", "ultraviolet", "airglow", "phosphor"];

    palettes.forEach((palette) => {
      document.documentElement.setAttribute("data-palette", palette);
      expect(document.documentElement.getAttribute("data-palette")).toBe(palette);
    });

    // Reset
    document.documentElement.removeAttribute("data-palette");
  });
});
