import type { ProfileList } from "./types";

/**
 * A saved profile name is safe launch context only when it is the one active match for the current
 * mod-set fingerprint. A stale profile refresh must fall back to the current mod setup instead of
 * naming the setup that was active before a successful switch.
 */
export function launchProfileNameFor(
  profiles: ProfileList | null,
  currentProfileFingerprint: string | null,
): string | null {
  if (!profiles) return null;
  const matches = profiles.profiles.filter((profile) =>
    profile.active
    && (!currentProfileFingerprint || profile.profileFingerprint === currentProfileFingerprint));
  return matches.length === 1 ? matches[0]?.name ?? null : null;
}
