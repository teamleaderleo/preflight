import type { OperationSnapshot } from "./types";

export function benchmarkOperationReason(operation: OperationSnapshot): string | null {
  if (operation.preparationPid !== null) return "Wait for preparation to finish.";
  if (operation.reportUploadId !== null) return "Wait for the run report upload to finish.";
  if (operation.updateInstalling) return "Wait for the Preflight update to finish installing.";
  if (operation.updateChecking) return "Wait for the update check to finish.";
  if (operation.diagnosticsExporting) return "Wait for the support file to finish.";
  return null;
}
