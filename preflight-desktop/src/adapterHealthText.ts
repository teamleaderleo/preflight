import type { AdapterHealthSummary } from "./types";

export function adapterHealthLine(health: AdapterHealthSummary): string {
  switch (health.status) {
    case "ACTIVE":
      return "Last run: acceleration active";
    case "PARTIAL":
      return "Last run: acceleration active, with safe fallback";
    case "SAFE_FALLBACK":
      return "Last run: original game code used safely";
    case "DISABLED":
      return "Last run: optimizations off";
    case "PROBE_ONLY":
      return "Last run: compatibility check only";
    case "NO_TARGETS":
      return "Last run: no matching optimizations needed";
    case "ERROR":
      return "Last run: optimization check incomplete";
  }
}
