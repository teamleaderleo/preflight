import { benchmarkOperationReason } from "./operationAvailability";
import type { OperationSnapshot } from "./types";

const idle: OperationSnapshot = {
  format: "preflight-operation-state-v1",
  gamePid: null,
  gameRecovered: false,
  desktopSmokePid: null,
  desktopSmokeRunDirectory: null,
  preparationPid: null,
  reportUploadId: null,
  reportUploadTotalBytes: null,
  diagnosticsExporting: false,
  updateChecking: false,
  updateInstalling: false,
};

test("explains only the native work that blocks a startup benchmark", () => {
  expect(benchmarkOperationReason(idle)).toBeNull();
  expect(benchmarkOperationReason({ ...idle, diagnosticsExporting: true }))
    .toBe("Wait for the support file to finish.");
  expect(benchmarkOperationReason({ ...idle, updateChecking: true }))
    .toBe("Wait for the update check to finish.");
  expect(benchmarkOperationReason({ ...idle, reportUploadId: 7 }))
    .toBe("Wait for the run report upload to finish.");
  expect(benchmarkOperationReason({ ...idle, desktopSmokePid: 42, updateChecking: true }))
    .toBe("Wait for the update check to finish.");
});
