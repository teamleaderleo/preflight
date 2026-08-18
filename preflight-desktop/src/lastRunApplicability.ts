import type { LastRun } from "./types";

export interface LaunchSetupIdentity {
  installRoot?: string | null;
  profileFingerprint?: string | null;
}

export type LaunchSetupApplicability = "applies" | "foreign" | "unknown";

/**
 * Compares durable launch identity with the setup Home would launch now.
 *
 * An installation mismatch is authoritative on its own. On the same installation, a missing
 * historical profile binding stays unknown only until the current profile identity is available.
 * Once it is, an old launch that cannot prove its profile is treated as foreign so transient Home
 * recovery cannot keep offering Relaunch after the player changes setup. Durable run evidence stays
 * available through its own support/history surfaces.
 */
export function launchSetupApplicability(
  identity: LaunchSetupIdentity | null | undefined,
  installRoot: string | null | undefined,
  profileFingerprint: string | null | undefined,
): LaunchSetupApplicability {
  if (!identity?.installRoot || !installRoot) return "unknown";
  if (identity.installRoot !== installRoot) return "foreign";
  if (!identity.profileFingerprint) return profileFingerprint ? "foreign" : "unknown";
  if (!profileFingerprint) return "unknown";
  return identity.profileFingerprint.toLowerCase() === profileFingerprint.toLowerCase()
    ? "applies"
    : "foreign";
}

/** Returns launch evidence only when the native bridge bound it to this exact setup. */
export function lastRunForCurrentProfile(
  lastRun: LastRun | null | undefined,
  installRoot: string | null | undefined,
  profileFingerprint: string | null | undefined,
): LastRun | null {
  return launchSetupApplicability(lastRun, installRoot, profileFingerprint) === "applies"
    ? lastRun ?? null
    : null;
}
