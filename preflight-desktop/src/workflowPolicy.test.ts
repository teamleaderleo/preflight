import { describe, expect, test } from "vitest";
import { blockingWorkflow, type WorkflowState } from "./workflowPolicy";

const idle: WorkflowState = {
  preparing: false,
  preparationPercent: 0,
  cacheRepairing: false,
  choosingInstall: false,
  restoringOperation: false,
  desktopSmokeRunning: false,
  desktopSmokeCancelling: false,
  status: "ready",
  cleanupBusy: false,
  launcherSaving: false,
  profileBusy: false,
  diagnosticsBusy: false,
  reportUploading: false,
  reportFinalizing: false,
  reportCancelling: false,
  removalBusy: false,
  updateInstalling: false,
};

describe("blockingWorkflow", () => {
  test("idle has no app-wide owner", () => {
    expect(blockingWorkflow(idle)).toBeNull();
  });

  test.each([
    ["preparation", { preparing: true, preparationPercent: 42 }, "home", "Preparing this mod setup · 42% complete"],
    ["cache repair", { cacheRepairing: true }, "prepare", "Repairing prepared data for this mod setup"],
    ["installation picker", { choosingInstall: true }, "home", "Choosing the Starsector installation"],
    ["startup recovery", { restoringOperation: true }, "home", "Checking for a previous Starsector launch"],
    ["benchmark", { desktopSmokeRunning: true }, "benchmark", "Running the startup benchmark"],
    ["launch", { status: "launching" }, "home", "Opening Starsector"],
    ["game", { status: "running" }, "home", "Starsector is running"],
    ["cleanup", { cleanupBusy: true }, "prepare", "Reviewing or cleaning prepared data"],
    ["settings write", { launcherSaving: true }, "launch", "Saving game settings"],
    ["profile mutation", { profileBusy: true }, "mods", "Updating the saved mod profile"],
    ["support export", { diagnosticsBusy: true }, "help", "Creating a support file"],
    ["report upload", { reportUploading: true }, "help", "Sending the run report"],
    ["removal", { removalBusy: true }, "settings", "Reviewing or removing Preflight data"],
    ["update installation", { updateInstalling: true }, "settings", "Installing the verified Preflight update"],
  ] as const)("%s has one owner and reason", (_name, patch, owner, reason) => {
    expect(blockingWorkflow({ ...idle, ...patch })).toEqual({ owner, reason });
  });

  test("benchmark and report lifecycle details come from the same descriptor", () => {
    expect(blockingWorkflow({ ...idle, desktopSmokeRunning: true, desktopSmokeCancelling: true }))
      .toEqual({ owner: "benchmark", reason: "Stopping the startup benchmark" });
    expect(blockingWorkflow({ ...idle, reportUploading: true, reportFinalizing: true }))
      .toEqual({ owner: "help", reason: "Finishing the signed run-report receipt" });
    expect(blockingWorkflow({ ...idle, reportUploading: true, reportCancelling: true }))
      .toEqual({ owner: "help", reason: "Stopping the run report upload" });
  });

  test("priority is explicit when more than one renderer flag is stale at once", () => {
    expect(blockingWorkflow({
      ...idle,
      preparing: true,
      preparationPercent: 7,
      desktopSmokeRunning: true,
      updateInstalling: true,
    })).toEqual({ owner: "home", reason: "Preparing this mod setup · 7% complete" });
  });
});
