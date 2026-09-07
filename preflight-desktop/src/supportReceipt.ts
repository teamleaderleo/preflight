import type { SupportReportReceipt } from "./reportLifecycleBridge";

export interface SupportSafeReportReceipt {
  caseId: string;
  bytes: number;
  sha256: string;
  productVersion: string;
  receivedAt: string;
  retentionDeadline: string;
}

export function supportSafeReportReceipt(receipt: SupportReportReceipt): SupportSafeReportReceipt {
  return {
    caseId: receipt.caseId,
    bytes: receipt.bytes,
    sha256: receipt.sha256,
    productVersion: receipt.productVersion,
    receivedAt: receipt.receivedAt,
    retentionDeadline: receipt.retentionDeadline,
  };
}
