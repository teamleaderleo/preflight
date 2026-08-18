import { describe, expect, it } from "vitest";
import type { ReportState } from "./reportBridge";
import { requireClearReportAuthorityBeforeAllDataRemoval } from "./useRemoval";

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

  it("allows removal only after native report state is clear", async () => {
    await expect(requireClearReportAuthorityBeforeAllDataRemoval(
      async () => reportState(),
    )).resolves.toBeUndefined();
  });

  it("fails closed when native report state cannot be read", async () => {
    await expect(requireClearReportAuthorityBeforeAllDataRemoval(async () => {
      throw new Error("native state unavailable");
    })).rejects.toThrow("native state unavailable");
  });
});
