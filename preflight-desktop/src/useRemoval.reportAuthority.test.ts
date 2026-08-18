import { describe, expect, it, vi } from "vitest";
import type { ReportState } from "./reportBridge";
import {
  fenceReportAuthorityBeforeAllDataRemoval,
  requireClearReportAuthorityBeforeAllDataRemoval,
} from "./useRemoval";

function reportState(overrides: Partial<ReportState> = {}): ReportState {
  return {
    configured: true,
    origin: "https://reports.example.test",
    reason: null,
    reports: [],
    automaticClaimed: null,
    legacyReceiptImported: false,
    backgroundUploadId: null,
    backgroundUploadTotalBytes: null,
    ...overrides,
  };
}

describe("all-data report authority guard", () => {
  it("refuses while an actionable report case retains deletion authority", async () => {
    await expect(requireClearReportAuthorityBeforeAllDataRemoval(async () => reportState({
      reports: [{
        state: "accepted",
        caseId: "11111111-1111-1111-1111-111111111111",
        bytes: 42,
        sha256: "a".repeat(64),
        productVersion: "0.1.0",
        receivedAt: "2026-08-18T10:30:00.000Z",
        retentionDeadline: "2026-09-02T10:30:00.000Z",
      }],
    }))).rejects.toThrow("Delete each uploaded report");
  });

  it("refuses while an automatic report upload can still publish authority", async () => {
    await expect(requireClearReportAuthorityBeforeAllDataRemoval(async () => reportState({
      backgroundUploadId: 77,
      backgroundUploadTotalBytes: 4096,
    }))).rejects.toThrow("Stop any automatic report upload first");
  });

  it("fences the empty native namespace before destructive removal", async () => {
    const clear = vi.fn(async () => true);
    await expect(fenceReportAuthorityBeforeAllDataRemoval(
      async () => reportState(),
      clear,
    )).resolves.toBeUndefined();
    expect(clear).toHaveBeenCalledTimes(1);
  });

  it("never clears native authority after a report-state refusal", async () => {
    const clear = vi.fn(async () => true);
    await expect(fenceReportAuthorityBeforeAllDataRemoval(
      async () => reportState({
        reports: [{
          state: "pending",
          caseId: "22222222-2222-2222-2222-222222222222",
          bytes: 42,
          sha256: "b".repeat(64),
          productVersion: null,
          receivedAt: null,
          retentionDeadline: null,
        }],
      }),
      clear,
    )).rejects.toThrow("Delete each uploaded report");
    expect(clear).not.toHaveBeenCalled();
  });

  it("propagates a native clear refusal before destructive removal", async () => {
    await expect(fenceReportAuthorityBeforeAllDataRemoval(
      async () => reportState(),
      async () => { throw new Error("authority became active"); },
    )).rejects.toThrow("authority became active");
  });

  it("fails closed when native report state cannot be read", async () => {
    await expect(requireClearReportAuthorityBeforeAllDataRemoval(async () => {
      throw new Error("native state unavailable");
    })).rejects.toThrow("native state unavailable");
  });
});
