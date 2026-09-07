import { describe, expect, test } from "vitest";
import type { SupportReportReceipt } from "./reportLifecycleBridge";
import { supportSafeReportReceipt } from "./supportReceipt";

const RECEIPT: SupportReportReceipt & {
  deletion?: { url: string; token: string };
  objectKey?: string;
  signature?: string;
} = {
  caseId: "case-123",
  bytes: 38_165,
  sha256: "a".repeat(64),
  productVersion: "0.1.0",
  receivedAt: "2026-08-16T12:00:00Z",
  retentionDeadline: "2026-08-30T12:00:00Z",
  // These legacy fields model stale renderer data from an older build. Copying still projects only
  // the support-safe fields even if a caller hands the helper an object with extra properties.
  deletion: {
    url: "https://reports.example.invalid/case-123",
    token: "secret-deletion-bearer",
  },
  objectKey: "accepted/case-123.zip",
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
