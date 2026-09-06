import type { AdapterHealthSummary } from "./types";

export function adapterHealthLine(health: AdapterHealthSummary): string {
  switch (health.status) {
    case "ACTIVE":
      return "Fast launch ready";
    case "PARTIAL":
      return "Some optimizations skipped · Details";
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
