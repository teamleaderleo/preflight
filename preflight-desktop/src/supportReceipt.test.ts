import { describe, expect, test } from "vitest";
import type { ReportReceipt } from "./types";
import { supportSafeReportReceipt } from "./supportReceipt";

const RECEIPT: ReportReceipt = {
  protocolVersion: 1,
  caseId: "case-123",
  objectKey: "accepted/case-123.zip",
  bytes: 38_165,
  sha256: "a".repeat(64),
  productVersion: "0.1.0",
  receivedAt: "2026-08-16T12:00:00Z",
  retentionDeadline: "2026-08-30T12:00:00Z",
  deletion: {
    method: "DELETE",
    url: "https://reports.example.invalid/case-123",
    token: "secret-deletion-bearer",
  },
  signature: "server-integrity-field",
};

describe("supportSafeReportReceipt", () => {
  test("copies only support-safe display fields", () => {
    const safe = supportSafeReportReceipt(RECEIPT);

    expect(safe).toEqual({
      caseId: "case-123",
      bytes: 38_165,
      sha256: "a".repeat(64),
      productVersion: "0.1.0",
      receivedAt: "2026-08-16T12:00:00Z",
      retentionDeadline: "2026-08-30T12:00:00Z",
    });

    const copied = JSON.stringify(safe);
    expect(copied).not.toContain("secret-deletion-bearer");
    expect(copied).not.toContain("reports.example.invalid");
    expect(copied).not.toContain("objectKey");
    expect(copied).not.toContain("signature");
    expect(copied).not.toContain("deletion");
  });
});
