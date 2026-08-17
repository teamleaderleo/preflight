import {
  benchmarkOperationReason,
  removalOperationReason,
  updateInstallOperationReason,
} from "./operationAvailability";
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

describe("operationAvailability", () => {
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

  test("explains only the native work that blocks data removal", () => {
    expect(removalOperationReason(idle)).toBeNull();
    expect(removalOperationReason({ ...idle, gamePid: 1234 }))
      .toBe("Close Starsector before removing Preflight data.");
    expect(removalOperationReason({ ...idle, reportUploadId: 5 }))
      .toBe("Wait for the run report upload to finish or cancel it before removing Preflight data.");
    expect(removalOperationReason({ ...idle, updateInstalling: true }))
      .toBe("Wait for the Preflight update to finish installing.");
  });

  test("explains only the native work that blocks update installation", () => {
    expect(updateInstallOperationReason(idle)).toBeNull();
    expect(updateInstallOperationReason({ ...idle, gamePid: 1234 }))
      .toBe("Close Starsector before installing a Preflight update.");
    expect(updateInstallOperationReason({ ...idle, preparationPid: 5678 }))
      .toBe("Wait for profile preparation to finish before installing an update.");
    expect(updateInstallOperationReason({ ...idle, reportUploadId: 9 }))
      .toBe("Wait for the run report upload to finish or cancel it before installing an update.");
    expect(updateInstallOperationReason({ ...idle, updateChecking: true }))
      .toBe("Wait for the current update check to finish before installing an update.");
    expect(updateInstallOperationReason({ ...idle, updateInstalling: true }))
      .toBe("A Preflight update is already being installed.");
  });
});

