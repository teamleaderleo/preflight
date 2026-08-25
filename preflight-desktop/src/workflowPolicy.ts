import type { Page } from "./components/DesktopShell";
import type { AppStatus } from "./types";

export interface BlockingWorkflow {
  owner: Page;
  reason: string;
}

export interface WorkflowState {
  preparing: boolean;
  preparationPercent: number | null;
  cacheRepairing: boolean;
  choosingInstall: boolean;
  restoringOperation: boolean;
  desktopSmokeRunning: boolean;
  desktopSmokeCancelling: boolean;
  status: AppStatus;
  cleanupBusy: boolean;
  launcherSaving: boolean;
  profileBusy: boolean;
  setupChecking: boolean;
  diagnosticsBusy: boolean;
  reportUploading: boolean;
  reportFinalizing: boolean;
  reportCancelling: boolean;
  removalBusy: boolean;
  updateInstalling: boolean;
}

/**
 * The renderer's app-wide ownership answer.
 *
 * Native commands still make the authoritative, operation-specific admission decision. This
 * descriptor owns only the broader desktop questions: whether ordinary mutations should wait and,
 * if they should, what workflow the user can return to for an explanation or cooperative stop.
 */
export function blockingWorkflow(state: WorkflowState): BlockingWorkflow | null {
  if (state.preparing) {
    return {
      reason: state.preparationPercent === null
        ? "Preparing this mod setup"
        : `Preparing this mod setup · ${state.preparationPercent}% complete`,
      owner: "home",
    };
  }
  if (state.cacheRepairing) {
    return { reason: "Repairing prepared data for this mod setup", owner: "speed" };
  }
  if (state.choosingInstall) {
    return { reason: "Choosing the Starsector installation", owner: "home" };
  }
  if (state.restoringOperation) {
    return { reason: "Checking for a previous Starsector launch", owner: "home" };
  }
  if (state.desktopSmokeRunning) {
    return {
      reason: state.desktopSmokeCancelling
        ? "Stopping the startup benchmark"
        : "Running the startup benchmark",
      owner: "benchmark",
    };
  }
  if (state.status === "launching") {
    return { reason: "Opening Starsector", owner: "home" };
  }
  if (state.status === "running") {
    return { reason: "Starsector is running", owner: "home" };
  }
  if (state.cleanupBusy) {
    return { reason: "Reviewing or cleaning prepared data", owner: "speed" };
  }
  if (state.launcherSaving) {
    return { reason: "Saving game settings", owner: "launch" };
  }
  if (state.profileBusy) {
    return { reason: "Updating the saved mod profile", owner: "mods" };
  }
  if (state.setupChecking) {
    return { reason: "Checking the mod setup", owner: "mods" };
  }
  if (state.diagnosticsBusy) {
    return { reason: "Creating a support file", owner: "help" };
  }
  if (state.reportUploading) {
    return {
      reason: state.reportFinalizing
        ? "Finishing the support upload"
        : state.reportCancelling
          ? "Stopping the support upload"
          : "Sending the support file",
      owner: "help",
    };
  }
  if (state.removalBusy) {
    return { reason: "Reviewing or removing Preflight data", owner: "settings" };
  }
  if (state.updateInstalling) {
    return { reason: "Installing the verified Preflight update", owner: "settings" };
  }
  return null;
}
