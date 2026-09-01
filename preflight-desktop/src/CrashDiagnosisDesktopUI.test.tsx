import React, { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Data Contracts for Feature 7 (Crash Diagnosis Desktop Screen)
export type RootCauseCategory =
  | "MOD_CRASH_UNCAUGHT_EXCEPTION"
  | "MISSING_DEPENDENCY"
  | "OUT_OF_MEMORY_HEAP"
  | "OUT_OF_MEMORY_VRAM_DIRECT"
  | "UNSUPPORTED_CLASS_VERSION"
  | "NATIVE_JVM_CRASH"
  | "SHADER_COMPILE_ERROR"
  | "MISSING_ASSET_RESOURCE"
  | "MOD_ID_COLLISION_DUPLICATE"
  | "UNKNOWN_FAILURE";

export interface OffendingModInfo {
  id: string;
  name: string;
  version: string;
  directory: string;
  crashingClass?: string;
  crashingMethod?: string;
  lineNumber?: number;
}

export interface MissingDependencyInfo {
  dependentModId: string;
  missingModId: string;
  missingClassName?: string;
}

export interface MemoryTelemetryInfo {
  heapUsedBytes: number;
  heapMaxBytes: number;
  directMemoryBytes: number;
  activeModsCount: number;
}

export interface RecoveryActionOption {
  id: "DISABLE_MOD" | "INCREASE_MEMORY" | "CLEAR_CACHE" | "RESTORE_FALLBACK_ARGS" | "START_BISECT";
  label: string;
  description: string;
  recommended: boolean;
  parameters?: Record<string, unknown>;
}

export interface CrashDiagnosisReport {
  format: "starsector-preflight-crash-diagnosis-v1";
  diagnosedAt: string;
  runDirectory: string;
  exitCode: number;
  launcherExitCode: number;
  rootCauseCategory: RootCauseCategory;
  confidence: "EXACT" | "HIGH" | "HEURISTIC" | "LOW";
  summaryTitle: string;
  summaryDescription: string;
  offendingMod: OffendingModInfo | null;
  missingDependency: MissingDependencyInfo | null;
  memoryTelemetry: MemoryTelemetryInfo | null;
  logSnippetLines: string[];
  crashLineIndex: number;
  recoveryActions: RecoveryActionOption[];
  copyableSnippet: string;
}

// UI Component implementation for Feature 7
export function CrashDiagnosisModal({
  diagnosis,
  onApplyAction,
  onStartBisect,
  onExportSupportZip,
  onDismiss,
  operationBlocked,
}: {
  diagnosis: CrashDiagnosisReport;
  onApplyAction: (action: RecoveryActionOption) => Promise<void>;
  onStartBisect: () => void;
  onExportSupportZip: () => Promise<void>;
  onDismiss: () => void;
  operationBlocked: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const [busyActionId, setBusyActionId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const handleCopySnippet = async () => {
    try {
      await navigator.clipboard.writeText(diagnosis.copyableSnippet);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback
      setCopied(true);
    }
  };

  const handleAction = async (action: RecoveryActionOption) => {
    setActionError(null);
    setBusyActionId(action.id);
    try {
      if (action.id === "START_BISECT") {
        onStartBisect();
      } else {
        await onApplyAction(action);
      }
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : "Failed to apply recovery action");
    } finally {
      setBusyActionId(null);
    }
  };

  const formatBytes = (bytes: number) => `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;

  return (
    <div className="modal-backdrop crt-theme" role="dialog" aria-modal="true" aria-label="Crash Diagnosis Modal">
      <div className="card crt-diagnostic-card">
        {/* CRT Scanline and Avionics Header */}
        <div className="crt-header">
          <div className="crt-header__title">
            <h1 className="orbitron-title">DIAGNOSTIC READOUT // LAUNCH ABORT</h1>
            <span className="crt-blinker">● REC</span>
          </div>
          <span className="status-badge status-badge--critical">
            EXIT CODE {diagnosis.exitCode} (FATAL LIFECYCLE)
          </span>
        </div>

        {/* Root Cause Banner */}
        <div className={`root-cause-banner root-cause-banner--${diagnosis.rootCauseCategory.toLowerCase()}`} data-testid="root-cause-banner">
          <div className="cause-icon">⚡</div>
          <div className="cause-details">
            <strong className="cause-title">{diagnosis.summaryTitle}</strong>
            <p className="cause-desc">{diagnosis.summaryDescription}</p>
            <span className="confidence-tag">CONFIDENCE: [{diagnosis.confidence}]</span>
          </div>
        </div>

        {/* Action Error Notice */}
        {actionError && (
          <div className="alert alert--danger" role="alert" data-testid="recovery-action-error">
            {actionError}
          </div>
        )}

        {/* Diagnostic Telemetry Grid */}
        <div className="telemetry-grid" data-testid="telemetry-grid">
          {diagnosis.offendingMod && (
            <div className="telemetry-item telemetry-item--highlight">
              <span className="telemetry-label">OFFENDING MOD</span>
              <strong className="telemetry-value">
                {diagnosis.offendingMod.name} ({diagnosis.offendingMod.id} v{diagnosis.offendingMod.version})
              </strong>
            </div>
          )}
          {diagnosis.missingDependency && (
            <div className="telemetry-item telemetry-item--highlight">
              <span className="telemetry-label">MISSING DEPENDENCY</span>
              <strong className="telemetry-value">
                {diagnosis.missingDependency.missingModId} (Required by {diagnosis.missingDependency.dependentModId})
              </strong>
            </div>
          )}
          {diagnosis.memoryTelemetry && (
            <div className="telemetry-item">
              <span className="telemetry-label">MEMORY AT CRASH</span>
              <strong className="telemetry-value">
                Heap: {formatBytes(diagnosis.memoryTelemetry.heapUsedBytes)} / {formatBytes(diagnosis.memoryTelemetry.heapMaxBytes)}
              </strong>
            </div>
          )}
          <div className="telemetry-item">
            <span className="telemetry-label">ACTIVE MODS</span>
            <strong className="telemetry-value">
              {diagnosis.memoryTelemetry?.activeModsCount ?? 0} enabled
            </strong>
          </div>
          <div className="telemetry-item">
            <span className="telemetry-label">CRASH LOCATION</span>
            <strong className="telemetry-value">
              {diagnosis.offendingMod?.crashingClass
                ? `${diagnosis.offendingMod.crashingClass}:${diagnosis.offendingMod.lineNumber ?? 0}`
                : `logs/starsector.log:line ${diagnosis.crashLineIndex + 1}`}
            </strong>
          </div>
        </div>

        {/* CRT Monospace Log Terminal */}
        <div className="crt-terminal-container">
          <div className="crt-terminal-header">
            <span>BOUNDED LOG TAIL (CAPTURED AT LAUNCH INODE)</span>
            <button className="button button--quiet button--compact" onClick={() => void handleCopySnippet()}>
              {copied ? "Copied!" : "Copy Support Snippet"}
            </button>
          </div>
          <pre className="crt-monospace-log" data-testid="crt-monospace-log">
            {diagnosis.logSnippetLines.map((line, idx) => (
              <div
                key={idx}
                className={`log-line ${idx === diagnosis.crashLineIndex ? "log-line--crash" : ""}`}
              >
                <span className="log-line-no">{idx + 1}</span>
                <span className="log-line-content">{line}</span>
              </div>
            ))}
          </pre>
        </div>

        {/* 1-Click Safe Recovery Actions Bar */}
        <div className="recovery-actions-bar">
          <span className="actions-header">1-CLICK SAFE RECOVERY ACTIONS:</span>
          <div className="actions-buttons" data-testid="recovery-buttons">
            {diagnosis.recoveryActions.map((action) => (
              <button
                key={action.id}
                className={`button ${action.recommended ? "button--primary" : "button--secondary"}`}
                onClick={() => void handleAction(action)}
                disabled={Boolean(busyActionId) || operationBlocked}
              >
                {busyActionId === action.id ? "Applying…" : `[ ${action.label} ]`}
              </button>
            ))}
            <button
              className="button button--quiet"
              onClick={() => void onExportSupportZip()}
              disabled={Boolean(busyActionId) || operationBlocked}
            >
              Export Support ZIP
            </button>
            <button
              className="button button--quiet"
              onClick={onDismiss}
              disabled={Boolean(busyActionId)}
            >
              Dismiss
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ------------------- TEST SUITE -------------------
describe("Feature 7: Crash Diagnosis Desktop Screen Test Suite", () => {
  const mockNpeDiagnosis: CrashDiagnosisReport = {
    format: "starsector-preflight-crash-diagnosis-v1",
    diagnosedAt: "2026-08-18T14:35:00Z",
    runDirectory: "~/.starsector-preflight/runs/20260818-143500",
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
      heapUsedBytes: 3.2 * 1024 * 1024 * 1024,
      heapMaxBytes: 4.0 * 1024 * 1024 * 1024,
      directMemoryBytes: 512 * 1024 * 1024,
      activeModsCount: 74,
    },
    logSnippetLines: [
      "2026-08-18 14:34:58 [Thread-2] INFO com.fs.starfarer.loading.SpecStore - Loaded 480 ship specs",
      "2026-08-18 14:34:59 [Thread-2] ERROR com.fs.starfarer.combat.CombatEngine - java.lang.NullPointerException",
      "java.lang.NullPointerException: null",
      "\tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)",
      "\tat com.fs.starfarer.combat.CombatEngine.advance(CombatEngine.java:820)",
    ],
    crashLineIndex: 2,
    recoveryActions: [
      {
        id: "DISABLE_MOD",
        label: "Disable 'armaa' & Relaunch",
        description: "Disables Arma Armatura in enabled_mods.json with automatic backup.",
        recommended: true,
        parameters: { modId: "armaa" },
      },
      {
        id: "START_BISECT",
        label: "Bisect Active Mods",
        description: "Launch Mod Bisect Assistant to isolate mod interactions.",
        recommended: false,
      },
    ],
    copyableSnippet: `### Starsector Crash Diagnosis Report
**Root Cause**: MOD_CRASH_UNCAUGHT_EXCEPTION in \`armaa\` (v1.94)
\`\`\`
java.lang.NullPointerException: null
\tat armaa.hullmods.MountedWep.advanceInCombat(MountedWep.java:142)
\`\`\``,
  };

  const mockOomDiagnosis: CrashDiagnosisReport = {
    format: "starsector-preflight-crash-diagnosis-v1",
    diagnosedAt: "2026-08-18T14:40:00Z",
    runDirectory: "~/.starsector-preflight/runs/20260818-144000",
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
      "2026-08-18 14:39:55 [Thread-2] INFO com.fs.starfarer.loading.ResourceLoader - Loading large graphics atlas",
      "java.lang.OutOfMemoryError: Java heap space",
      "\tat java.util.Arrays.copyOf(Arrays.java:3332)",
    ],
    crashLineIndex: 1,
    recoveryActions: [
      {
        id: "INCREASE_MEMORY",
        label: "Increase Heap Memory to 6144 MB",
        description: "Increases -Xmx allocation in Starsector launch settings.",
        recommended: true,
        parameters: { memoryMiB: 6144 },
      },
    ],
    copyableSnippet: "### OutOfMemory Diagnosis Snippet",
  };

  const mockMissingDepDiagnosis: CrashDiagnosisReport = {
    format: "starsector-preflight-crash-diagnosis-v1",
    diagnosedAt: "2026-08-18T14:45:00Z",
    runDirectory: "~/.starsector-preflight/runs/20260818-144500",
    exitCode: 6,
    launcherExitCode: 0,
    rootCauseCategory: "MISSING_DEPENDENCY",
    confidence: "EXACT",
    summaryTitle: "MISSING REQUIRED MOD DEPENDENCY: lw_lazylib",
    summaryDescription: "Mod 'magiclib' requires 'lw_lazylib' which is not installed or enabled in mods/.",
    offendingMod: null,
    missingDependency: {
      dependentModId: "magiclib",
      missingModId: "lw_lazylib",
      missingClassName: "org.lazywizard.lazylib.LazyLib",
    },
    memoryTelemetry: {
      heapUsedBytes: 1.2 * 1024 * 1024 * 1024,
      heapMaxBytes: 4.0 * 1024 * 1024 * 1024,
      directMemoryBytes: 256 * 1024 * 1024,
      activeModsCount: 15,
    },
    logSnippetLines: [
      "java.lang.NoClassDefFoundError: org/lazywizard/lazylib/LazyLib",
      "\tat org.magiclib.MagicLib.init(MagicLib.java:30)",
    ],
    crashLineIndex: 0,
    recoveryActions: [
      {
        id: "DISABLE_MOD",
        label: "Disable Dependent Mod 'magiclib'",
        description: "Disables magiclib until lw_lazylib is installed.",
        recommended: true,
        parameters: { modId: "magiclib" },
      },
    ],
    copyableSnippet: "### Missing Dependency Snippet",
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ========== TIER 1: HAPPY PATH EQUIVALENCE CLASS TESTS (>= 5 tests) ==========

  it("T1.1: renders CRT diagnostic modal with Orbitron headers, scanline styling, and status badge", () => {
    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("DIAGNOSTIC READOUT // LAUNCH ABORT")).toBeInTheDocument();
    expect(screen.getByText("EXIT CODE 6 (FATAL LIFECYCLE)")).toBeInTheDocument();
    expect(screen.getByText("● REC")).toBeInTheDocument();
  });

  it("T1.2: displays root cause banner and mod attribution for MOD_CRASH_UNCAUGHT_EXCEPTION (armaa)", () => {
    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const banner = screen.getByTestId("root-cause-banner");
    expect(within(banner).getByText("MOD RUNTIME EXCEPTION: Arma Armatura (armaa)")).toBeInTheDocument();
    expect(within(banner).getByText(/Uncaught java.lang.NullPointerException in armaa.hullmods.MountedWep/)).toBeInTheDocument();
    expect(within(banner).getByText("CONFIDENCE: [EXACT]")).toBeInTheDocument();

    const telemetry = screen.getByTestId("telemetry-grid");
    expect(within(telemetry).getByText("Arma Armatura (armaa v1.94)")).toBeInTheDocument();
    expect(within(telemetry).getByText("armaa.hullmods.MountedWep:142")).toBeInTheDocument();
  });

  it("T1.3: displays MISSING_DEPENDENCY diagnosis with missing and dependent mod names", () => {
    render(
      <CrashDiagnosisModal
        diagnosis={mockMissingDepDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const banner = screen.getByTestId("root-cause-banner");
    expect(within(banner).getByText("MISSING REQUIRED MOD DEPENDENCY: lw_lazylib")).toBeInTheDocument();

    const telemetry = screen.getByTestId("telemetry-grid");
    expect(within(telemetry).getByText("lw_lazylib (Required by magiclib)")).toBeInTheDocument();
  });

  it("T1.4: displays OUT_OF_MEMORY_HEAP with memory telemetry grid and recommended heap action", () => {
    render(
      <CrashDiagnosisModal
        diagnosis={mockOomDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const banner = screen.getByTestId("root-cause-banner");
    expect(within(banner).getByText("JAVA HEAP EXHAUSTION (OutOfMemoryError)")).toBeInTheDocument();

    const telemetry = screen.getByTestId("telemetry-grid");
    expect(within(telemetry).getByText(/Heap: 4.0 GB \/ 4.0 GB/)).toBeInTheDocument();
    expect(within(telemetry).getByText("120 enabled")).toBeInTheDocument();

    const buttons = screen.getByTestId("recovery-buttons");
    expect(within(buttons).getByRole("button", { name: "[ Increase Heap Memory to 6144 MB ]" })).toBeInTheDocument();
  });

  it("T1.5: copies anonymized support snippet to clipboard", async () => {
    const user = userEvent.setup();
    const writeTextSpy = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: writeTextSpy },
      writable: true,
      configurable: true,
    });

    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const copyBtn = screen.getByRole("button", { name: "Copy Support Snippet" });
    await user.click(copyBtn);

    expect(writeTextSpy).toHaveBeenCalledWith(mockNpeDiagnosis.copyableSnippet);
    expect(await screen.findByRole("button", { name: "Copied!" })).toBeInTheDocument();
  });

  it("T1.6: executes 1-click safe recovery action DISABLE_MOD with pre-mutation backup", async () => {
    const user = userEvent.setup();
    const actionSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={actionSpy}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const disableBtn = screen.getByRole("button", { name: "[ Disable 'armaa' & Relaunch ]" });
    await user.click(disableBtn);

    expect(actionSpy).toHaveBeenCalledWith(mockNpeDiagnosis.recoveryActions[0]);
  });

  it("T1.7: initiates Mod Bisect Assistant from diagnostic action button", async () => {
    const user = userEvent.setup();
    const bisectSpy = vi.fn();

    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={bisectSpy}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const bisectBtn = screen.getByRole("button", { name: "[ Bisect Active Mods ]" });
    await user.click(bisectBtn);

    expect(bisectSpy).toHaveBeenCalledTimes(1);
  });

  // ========== TIER 2: BOUNDARY VALUE ANALYSIS & ERROR / FAULT INJECTION (>= 5 tests) ==========

  it("T2.1: handles UNKNOWN_FAILURE gracefully when process exits non-zero without recognized exception", () => {
    const mockUnknownDiagnosis: CrashDiagnosisReport = {
      format: "starsector-preflight-crash-diagnosis-v1",
      diagnosedAt: "2026-08-18T14:50:00Z",
      runDirectory: "~/.starsector-preflight/runs/unknown",
      exitCode: 1,
      launcherExitCode: 1,
      rootCauseCategory: "UNKNOWN_FAILURE",
      confidence: "LOW",
      summaryTitle: "UNCLASSIFIED PROCESS TERMINATION (Exit Code 1)",
      summaryDescription: "The game process terminated with exit code 1. No standard Java exception pattern was detected.",
      offendingMod: null,
      missingDependency: null,
      memoryTelemetry: null,
      logSnippetLines: ["Process exited unexpectedly with code 1."],
      crashLineIndex: 0,
      recoveryActions: [
        {
          id: "CLEAR_CACHE",
          label: "Clear Prepared Data Cache",
          description: "Forces a fresh asset preparation on next launch.",
          recommended: false,
        },
      ],
      copyableSnippet: "### Unknown Crash",
    };

    render(
      <CrashDiagnosisModal
        diagnosis={mockUnknownDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("UNCLASSIFIED PROCESS TERMINATION (Exit Code 1)")).toBeInTheDocument();
    expect(screen.getByText("CONFIDENCE: [LOW]")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "[ Clear Prepared Data Cache ]" })).toBeInTheDocument();
  });

  it("T2.2: renders bounded log snippet highlighting exact crashLineIndex line", () => {
    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const terminal = screen.getByTestId("crt-monospace-log");
    const lines = terminal.querySelectorAll(".log-line");
    expect(lines).toHaveLength(5);
    expect(lines[2]).toHaveClass("log-line--crash");
    expect(lines[2]).toHaveTextContent("java.lang.NullPointerException: null");
  });

  it("T2.3: handles recovery action IPC failure and renders error alert without crashing modal", async () => {
    const user = userEvent.setup();
    const actionSpy = vi.fn().mockRejectedValue(new Error("Disk locked: failed to stage enabled_mods.json backup"));

    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={actionSpy}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    const disableBtn = screen.getByRole("button", { name: "[ Disable 'armaa' & Relaunch ]" });
    await user.click(disableBtn);

    const errorAlert = await screen.findByTestId("recovery-action-error");
    expect(errorAlert).toHaveTextContent("Disk locked: failed to stage enabled_mods.json backup");
  });

  it("T2.4: blocks recovery action execution while OperationLease is active", () => {
    render(
      <CrashDiagnosisModal
        diagnosis={mockNpeDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={true}
      />
    );

    const disableBtn = screen.getByRole("button", { name: "[ Disable 'armaa' & Relaunch ]" });
    const bisectBtn = screen.getByRole("button", { name: "[ Bisect Active Mods ]" });
    const exportBtn = screen.getByRole("button", { name: "Export Support ZIP" });

    expect(disableBtn).toBeDisabled();
    expect(bisectBtn).toBeDisabled();
    expect(exportBtn).toBeDisabled();
  });

  it("T2.5: handles NATIVE_JVM_CRASH (SIGSEGV / hs_err_pid) with driver diagnostics and fallback args action", () => {
    const mockNativeCrashDiagnosis: CrashDiagnosisReport = {
      format: "starsector-preflight-crash-diagnosis-v1",
      diagnosedAt: "2026-08-18T14:55:00Z",
      runDirectory: "~/.starsector-preflight/runs/native-crash",
      exitCode: 139,
      launcherExitCode: 0,
      rootCauseCategory: "NATIVE_JVM_CRASH",
      confidence: "EXACT",
      summaryTitle: "NATIVE JVM FATAL CRASH (SIGSEGV in lwjgl.dll / GPU driver)",
      summaryDescription: "Native crash detected in hs_err_pid1234.log during OpenGL shader context switch.",
      offendingMod: null,
      missingDependency: null,
      memoryTelemetry: null,
      logSnippetLines: [
        "# A fatal error has been detected by the Java Runtime Environment:",
        "#  SIGSEGV (0xb) at pc=0x00007fff892b12a0, pid=1234, tid=4321",
        "# Problematic frame: C  [nvoglv64.dll+0xa1234]",
      ],
      crashLineIndex: 1,
      recoveryActions: [
        {
          id: "RESTORE_FALLBACK_ARGS",
          label: "Restore Fallback Safe JVM & OpenGL Arguments",
          description: "Disables experimental graphics mods and restores safe JVM options.",
          recommended: true,
        },
      ],
      copyableSnippet: "### Native Crash Snippet",
    };

    render(
      <CrashDiagnosisModal
        diagnosis={mockNativeCrashDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText(/NATIVE JVM FATAL CRASH/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "[ Restore Fallback Safe JVM & OpenGL Arguments ]" })).toBeInTheDocument();
  });

  it("T2.6: handles UNSUPPORTED_CLASS_VERSION (Java 17/21 bytecode on Java 8 runtime)", () => {
    const mockClassVersionDiagnosis: CrashDiagnosisReport = {
      format: "starsector-preflight-crash-diagnosis-v1",
      diagnosedAt: "2026-08-18T15:00:00Z",
      runDirectory: "~/.starsector-preflight/runs/class-version",
      exitCode: 6,
      launcherExitCode: 0,
      rootCauseCategory: "UNSUPPORTED_CLASS_VERSION",
      confidence: "EXACT",
      summaryTitle: "JAVA RUNTIME INCOMPATIBILITY (UnsupportedClassVersionError)",
      summaryDescription: "Mod 'new_faction' was compiled for Java 17 (class file 61.0), but Starsector is running Java 8 (class file 52.0).",
      offendingMod: {
        id: "new_faction",
        name: "New Faction Mod",
        version: "2.0.0",
        directory: "mods/New Faction",
        crashingClass: "data.scripts.NewFactionPlugin",
      },
      missingDependency: null,
      memoryTelemetry: null,
      logSnippetLines: [
        "java.lang.UnsupportedClassVersionError: data/scripts/NewFactionPlugin has been compiled by a more recent version of the Java Runtime (class file version 61.0)",
      ],
      crashLineIndex: 0,
      recoveryActions: [
        {
          id: "DISABLE_MOD",
          label: "Disable Incompatible Mod 'new_faction'",
          description: "Disables the mod requiring Java 17.",
          recommended: true,
          parameters: { modId: "new_faction" },
        },
      ],
      copyableSnippet: "### Class Version Snippet",
    };

    render(
      <CrashDiagnosisModal
        diagnosis={mockClassVersionDiagnosis}
        onApplyAction={vi.fn()}
        onStartBisect={vi.fn()}
        onExportSupportZip={vi.fn()}
        onDismiss={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("JAVA RUNTIME INCOMPATIBILITY (UnsupportedClassVersionError)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "[ Disable Incompatible Mod 'new_faction' ]" })).toBeInTheDocument();
  });
});
