export type CanaryResult = {
  format: "preflight-report-intake-canary-v1";
  origin: string;
  caseId: string;
  archiveBytes: number;
  sha256: string;
  receivedAt: string;
  retentionDeadline: string;
  deleted: true;
};

export function runCanary(requestedOrigin: string, fetchImpl?: typeof fetch): Promise<CanaryResult>;
export function canaryBundle(now?: Date): Uint8Array;
