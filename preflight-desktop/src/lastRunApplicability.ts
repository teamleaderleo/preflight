import type { LastRun } from "./types";

/** Returns launch evidence only when the native bridge bound it to this exact setup. */
export function lastRunForCurrentProfile(
  lastRun: LastRun | null | undefined,
  installRoot: string | null | undefined,
  profileFingerprint: string | null | undefined,
): LastRun | null {
  if (!lastRun || !installRoot || !profileFingerprint) return null;
  if (lastRun.installRoot !== installRoot) return null;
  if (lastRun.profileFingerprint?.toLowerCase() !== profileFingerprint.toLowerCase()) return null;
  return lastRun;
}
