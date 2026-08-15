export const PROTOCOL_VERSION = 1;
export const BUNDLE_FORMAT = "starsector-preflight-diagnostics-v1";
export const SUPPORT_EVIDENCE_FORMAT = "starsector-preflight-support-evidence-v1";
export const ZIP_CONTENT_TYPE = "application/zip";

export const MAX_FILE_BYTES = 512 * 1024;
export const MAX_CONTENT_BYTES = 5 * 1024 * 1024;
export const MAX_ARCHIVE_ENTRIES = 512;
export const MAX_MANIFEST_BYTES = 512 * 1024;
export const MAX_README_BYTES = 64 * 1024;
export const MAX_PRODUCT_VERSION_LENGTH = 128;
export const MAX_SUPPORT_RECORDS = 2_048;
export const MAX_SUPPORT_FIELDS_PER_RECORD = 512;
export const MAX_SUPPORT_STRING_CHARS = 512;
export const MAX_SUPPORT_FIELD_PATH_CHARS = 256;

export const SUPPORT_EVIDENCE_SEGMENTS = new Set([
  "active", "adapter", "adapterHealth", "adapters", "architecture", "available",
  "averageFps", "battleSize", "benchmark", "benchmarks", "bytes", "cache", "cacheHit",
  "cacheHits", "cacheMiss", "cacheMisses", "channels", "classCount", "configured",
  "containedFailure", "containedFailures", "count", "counts", "cpu", "createdAt",
  "current", "decline", "declined", "declineCount", "delta", "disabledOptimizationDomains",
  "domain", "domains", "durationMs", "enabled", "enabledMods", "engineVersion", "entries",
  "entryCount", "failure", "failures", "failureCount", "finishedAt", "format", "fps",
  "frame", "frames", "fullscreen", "gameVersion", "height", "hit", "hits", "identity",
  "identitySha256", "id", "improvementPercent", "kind", "launch", "launches", "maximum",
  "measurementOnly", "memoryMiB", "metrics", "minimum", "miss", "misses", "modCount",
  "modId", "modIds", "mods", "name", "observations", "optimized", "optimizationPreset",
  "phase", "platform", "preparedAudio", "preparedTextures", "preset", "process",
  "processToMainMenuMs", "profile", "profileFingerprint", "protocolVersion", "providerCount",
  "providers", "reason", "recommended", "resolution", "result", "results", "runtime",
  "seconds", "sha256", "size", "sound", "startedAt", "state", "status", "success",
  "successful", "summary", "timing", "timings", "total", "transformedClassCount",
  "transformedClasses", "uiScale", "version", "width", "antialiasing",
  "antialiasingSamples", "pixelConversions", "productVersion", "ready", "sampleCount",
  "samples", "selected", "storage", "totalBytes", "type", "value", "values",
]);

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
  // Internal quota routing for lease-aware grants. Optional so grants issued by the immediately
  // previous Worker revision remain valid through their short TTL during a rolling deployment.
  quotaDay?: string;
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
