export const PROTOCOL_VERSION = 1;
export const BUNDLE_FORMAT = "starsector-preflight-diagnostics-v1";
export const ZIP_CONTENT_TYPE = "application/zip";

export const MAX_FILE_BYTES = 512 * 1024;
export const MAX_CONTENT_BYTES = 5 * 1024 * 1024;
export const MAX_ARCHIVE_ENTRIES = 512;
export const MAX_MANIFEST_BYTES = 512 * 1024;
export const MAX_README_BYTES = 64 * 1024;
export const MAX_PRODUCT_VERSION_LENGTH = 128;

export const RUN_FILES = new Set([
  "adapter-analysis.json",
  "adapter-health.json",
  "adapter.json",
  "benchmark-result.json",
  "comparison-result.json",
  "main-menu-ready.json",
  "menu-flushed.json",
  "menu.json",
  "operator-gameplay-result.json",
  "profile.json",
  "run.json",
  "runtime-process.json",
  "runtime-state.json",
  "runtime-frame-report.json",
  "runtime-adapter-health.json",
  "smoke-evidence.json",
  "summary.json",
]);

export const BENCHMARK_FILES = new Set([
  "benchmark-summary.json",
  "identity.json",
  "launch-settings.json",
  "prepare.json",
  "profile-baseline.json",
  "profile-settled.json",
  "results.jsonl",
  "session-config.json",
]);

export type CreateCaseRequest = {
  protocolVersion: number;
  productVersion: string;
  bytes: number;
  sha256: string;
};

export type GrantPurpose = "upload" | "delete";

export type GrantClaims = {
  v: number;
  purpose: GrantPurpose;
  caseId: string;
  objectKey: string;
  productVersion: string;
  bytes: number;
  sha256: string;
  exp: number;
};

export type Receipt = {
  protocolVersion: number;
  caseId: string;
  objectKey: string;
  bytes: number;
  sha256: string;
  productVersion: string;
  receivedAt: string;
  retentionDeadline: string;
  deletion: {
    method: "DELETE";
    url: string;
    token: string;
  };
  signature: string;
};
