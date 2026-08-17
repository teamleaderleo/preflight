import type { OperationSnapshot } from "./types";

export function benchmarkOperationReason(operation: OperationSnapshot): string | null {
  if (operation.preparationPid !== null) return "Wait for preparation to finish.";
  if (operation.reportUploadId !== null) return "Wait for the run report upload to finish.";
  if (operation.updateInstalling) return "Wait for the Preflight update to finish installing.";
  if (operation.updateChecking) return "Wait for the update check to finish.";
  if (operation.diagnosticsExporting) return "Wait for the support file to finish.";
  return null;
}

export function removalOperationReason(operation: OperationSnapshot): string | null {
  if (operation.gamePid !== null) return "Close Starsector before removing Preflight data.";
  if (operation.preparationPid !== null) {
    return "Wait for profile preparation to finish before removing Preflight data.";
  }
  if (operation.reportUploadId !== null) {
    return "Wait for the run report upload to finish or cancel it before removing Preflight data.";
  }
  if (operation.updateInstalling) return "Wait for the Preflight update to finish installing.";
  return null;
}

export function updateInstallOperationReason(operation: OperationSnapshot): string | null {
  if (operation.gamePid !== null) return "Close Starsector before installing a Preflight update.";
  if (operation.preparationPid !== null) {
    return "Wait for profile preparation to finish before installing an update.";
  }
  if (operation.reportUploadId !== null) {
    return "Wait for the run report upload to finish or cancel it before installing an update.";
  }
  if (operation.updateChecking) {
    return "Wait for the current update check to finish before installing an update.";
  }
  if (operation.updateInstalling) return "A Preflight update is already being installed.";
  return null;
}
