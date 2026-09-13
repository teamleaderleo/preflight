import type { AdapterHealthSummary } from "./types";

export function adapterHealthLine(health: AdapterHealthSummary): string {
  switch (health.status) {
    case "ACTIVE":
      return "Fast launch ready";
    case "PARTIAL":
      return "Last run needs attention · Details";
    case "SAFE_FALLBACK":
      return "Optimizations unavailable · Details";
    case "DISABLED":
      return "Last run: optimizations off";
    case "PROBE_ONLY":
      return "Last run: compatibility check only";
    case "NO_TARGETS":
      return "Last run: no matching optimizations needed";
    case "ERROR":
      return "Run check incomplete · Details";
  }
}

// Older installed engines mark ordinary compatibility fallbacks for review too.
// Preserve their report, but promote only a failed run check or runtime failure.
export function adapterHealthNeedsAttention(health: AdapterHealthSummary): boolean {
  return health.status === "ERROR" || health.status === "SAFE_FALLBACK"
    || health.containedFailures > 0
    || health.evidenceKinds.some(kind => [
      "CACHE_REJECTION", "WRAPPER_FAILURE", "RUNTIME_INTEGRITY_FAILURE",
    ].includes(kind));
}
