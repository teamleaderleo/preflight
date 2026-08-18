import type { LastRun } from "./types";

export interface LaunchSetupIdentity {
  installRoot?: string | null;
  profileFingerprint?: string | null;
}

export type LaunchSetupApplicability = "applies" | "foreign" | "unknown";

/**
 * Compares durable launch identity with the setup Home would launch now.
 *
 * An installation mismatch is authoritative on its own. Profile applicability stays unknown until
 * the current fingerprint is available, so ordinary identity refreshes do not retire valid evidence.
 */
export function launchSetupApplicability(
  identity: LaunchSetupIdentity | null | undefined,
  installRoot: string | null | undefined,
  profileFingerprint: string | null | undefined,
): LaunchSetupApplicability {
  if (!identity?.installRoot || !installRoot) return "unknown";
  if (identity.installRoot !== installRoot) return "foreign";
  if (!identity.profileFingerprint || !profileFingerprint) return "unknown";
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
