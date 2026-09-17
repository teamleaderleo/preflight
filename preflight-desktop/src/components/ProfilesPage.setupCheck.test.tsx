import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import type { SetupAnalysisResult, SetupFinding, useProfiles as UseProfilesType } from "../types";
import type { useProfiles } from "../useProfiles";
import type { useSetupCheck } from "../useSetupCheck";
import { ProfilesPage } from "./ProfilesPage";

function profilesState(overrides: Record<string, unknown> = {}): ReturnType<typeof useProfiles> {
  return {
    activationPlan: null,
    mutationPlan: null,
    profileBusy: false,
    profileName: "",
    profiles: { profiles: [], diagnostics: [] },
    profilesLoading: false,
    modReadiness: {
      format: "starsector-preflight-mod-readiness-v1",
      ready: true,
      counts: { blocking: 0, warning: 0, info: 0, unknown: 0 },
      findings: [],
      modDirectories: 83,
      metadataBytes: 1,
      elapsedMillis: 1,
    },
    modReadinessLoading: false,
    renameDraft: "",
    renameTarget: null,
    duplicateDraft: "",
    duplicateTarget: null,
    applyProfile: vi.fn(),
    applyProfileMutation: vi.fn(),
    beginRename: vi.fn(),
    cancelRename: vi.fn(),
    beginDuplicate: vi.fn(),
    cancelDuplicate: vi.fn(),
    reviewDeleteProfile: vi.fn(),
    reviewProfile: vi.fn(),
    saveCurrentProfile: vi.fn(),
    dismissActivationPlan: vi.fn(),
    dismissMutationPlan: vi.fn(),
    setProfileName: vi.fn(),
    setRenameDraft: vi.fn(),
    submitRename: vi.fn(),
    setDuplicateDraft: vi.fn(),
    submitDuplicate: vi.fn(),
    refreshModReadiness: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useProfiles>;
}

function finding(index: number, severity: SetupFinding["severity"] = "blocking"): SetupFinding {
  return {
    code: `finding-${index}`,
    provider: "fixture",
    severity,
    summary: `Finding ${index}`,
    parameters: {},
    affectedModIds: [],
    actions: index % 2 === 0 ? [`Action ${index}`] : [],
  };
}

function result(findings: SetupFinding[]): SetupAnalysisResult {
  const counts = { blocking: 0, warning: 0, info: 0, unknown: 0 };
  for (const item of findings) counts[item.severity] += 1;
  return {
    format: "starsector-preflight-setup-analysis-v1",
    installationIdentity: "install-v1:test",
    profileFingerprint: "profile:test",
    ready: counts.blocking === 0,
    counts,
    findings,
    unavailableProviders: [],
  };
}

function setupCheck(overrides: Partial<ReturnType<typeof useSetupCheck>> = {}): ReturnType<typeof useSetupCheck> {
  return {
    checking: false,
    error: null,
    result: null,
    status: "idle",
    run: vi.fn(),
    ...overrides,
  } as ReturnType<typeof useSetupCheck>;
}

test("all returned setup findings are reachable and copied from the same complete result", async () => {
  const user = userEvent.setup();
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  const findings = Array.from({ length: 25 }, (_, index) => finding(index + 1));

  render(<ProfilesPage
    message=""
    messageTone="info"
    profilesState={profilesState()}
    setupCheck={setupCheck({ status: "successful", result: result(findings) })}
    operationBlocked={false}
  />);

  expect(screen.getByText("Finding 20")).toBeVisible();
  expect(screen.queryByText("Finding 21")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Show all 25 findings" }));
  expect(screen.getByText("Finding 25")).toBeVisible();

  await user.click(screen.getByRole("button", { name: "Copy findings" }));
  expect(writeText).toHaveBeenCalledOnce();
  const copied = String(writeText.mock.calls[0][0]);
  expect(copied).toContain("Finding 1");
  expect(copied).toContain("Finding 25");
  expect(copied.match(/^\[BLOCKING\] Finding /gm)).toHaveLength(25);
});

test("a failed recheck labels the retained successful result as previous", async () => {
  const user = userEvent.setup();
  const run = vi.fn();

  render(<ProfilesPage
    message=""
    messageTone="info"
    profilesState={profilesState()}
    setupCheck={setupCheck({
      status: "failed",
      error: "Native check failed",
      result: result([]),
      run,
    })}
    operationBlocked={false}
  />);

  expect(screen.getByText("Previous check: no problems found")).toBeVisible();
  expect(screen.getByText("The latest check couldn’t finish.")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Try again" }));
  expect(run).toHaveBeenCalledOnce();
});

test("a running recheck keeps the previous result explicitly labeled", () => {
  render(<ProfilesPage
    message=""
    messageTone="info"
    profilesState={profilesState()}
    setupCheck={setupCheck({
      checking: true,
      status: "running",
      result: result([finding(1, "warning")]),
    })}
    operationBlocked
  />);

  expect(screen.getByText("Previous check: 1 item to review")).toBeVisible();
  expect(screen.getByText("Checking your current setup… Previous results remain available below.")).toBeVisible();
});

test("the lighter readiness result can expand beyond its initial eight findings", async () => {
  const user = userEvent.setup();
  const readinessFindings = Array.from({ length: 9 }, (_, index) => finding(index + 1, "warning"));

  render(<ProfilesPage
    message=""
    messageTone="info"
    profilesState={profilesState({
      modReadiness: {
        format: "starsector-preflight-mod-readiness-v1",
        ready: true,
        counts: { blocking: 0, warning: 9, info: 0, unknown: 0 },
        findings: readinessFindings,
        modDirectories: 83,
        metadataBytes: 1,
        elapsedMillis: 1,
      },
    })}
    setupCheck={setupCheck()}
    operationBlocked={false}
  />);

  await user.click(screen.getByText("9 mod warnings"));
  expect(screen.getByText("Finding 8")).toBeVisible();
  expect(screen.queryByText("Finding 9")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Show all 9 findings" }));
  expect(screen.getByText("Finding 9")).toBeVisible();
});
