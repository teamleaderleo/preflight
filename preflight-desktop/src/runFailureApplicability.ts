export interface RunFailureIdentity {
  installRoot: string;
  profileFingerprint: string;
}

export type RunFailureApplicability = "applies" | "pending" | "stale";

export function runFailureApplicability(
  failed: RunFailureIdentity,
  currentInstallRoot: string | undefined,
  currentProfileFingerprint: string | null,
  profileIdentityLoading: boolean,
): RunFailureApplicability {
  if (!currentInstallRoot || currentInstallRoot !== failed.installRoot) return "stale";
  if (profileIdentityLoading || !currentProfileFingerprint) return "pending";
  return currentProfileFingerprint === failed.profileFingerprint ? "applies" : "stale";
}
