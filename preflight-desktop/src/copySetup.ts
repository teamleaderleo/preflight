import type { CacheHealth, DesktopSnapshot, OptimizationPreset } from "./types";

export const COPY_SETUP_VERSION = 1 as const;
export const COPY_SETUP_MAX_MODS = 96;

const MAX_VERSION_CHARS = 64;
const MAX_FINGERPRINT_CHARS = 96;
const MAX_MOD_ID_CHARS = 96;
const MAX_MOD_NAME_CHARS = 120;
const MAX_MOD_VERSION_CHARS = 64;
const MAX_OUTCOME_CHARS = 32;

export interface CopySetupModObservation {
  id: string;
  displayName?: string | null;
  declaredVersion?: string | null;
}

export interface CopySetupObservations {
  preflightVersion: string;
  platform: DesktopSnapshot["platform"];
  starsectorReady: boolean;
  profileFingerprint?: string | null;
  /** null means the enabled-mod list could not be established; [] is a proven empty profile. */
  mods: CopySetupModObservation[] | null;
  launchSettings?: {
    resolution?: string | null;
    fullscreen?: boolean | null;
    sound?: boolean | null;
    maxHeapMiB?: number | null;
    initialHeapMiB?: number | null;
  } | null;
  optimizationPreset: OptimizationPreset;
  preparation?: {
    status: CacheHealth["status"];
    profileFingerprint?: string | null;
  } | null;
  latestLaunch?: {
    outcome?: string | null;
    startupMillis?: number | null;
    exitCode?: number | null;
  } | null;
}

export interface CopySetupPublicMod {
  id: string;
  displayName: string | null;
  declaredVersion: string | null;
}

export interface CopySetupPublicSummary {
  version: typeof COPY_SETUP_VERSION;
  preflightVersion: string;
  platform: DesktopSnapshot["platform"];
  starsectorReady: boolean;
  profileFingerprint: string | null;
  /** null means unavailable evidence; [] means an observed empty enabled-mod list. */
  mods: CopySetupPublicMod[] | null;
  omittedModCount: number | null;
  launchSettings: {
    resolution: string | null;
    fullscreen: boolean | null;
    sound: boolean | null;
    maxHeapMiB: number | null;
    initialHeapMiB: number | null;
  } | null;
  optimizationPreset: OptimizationPreset;
  preparation: {
    status: CacheHealth["status"];
    profileFingerprint: string | null;
  } | null;
  latestLaunch: {
    outcome: string | null;
    startupMillis: number | null;
    exitCode: number | null;
  } | null;
}

/**
 * Builds the complete public contract for Copy setup.
 *
 * Keep this field-by-field. The desktop has richer objects containing paths, diagnostics, command
 * arguments, report credentials, and arbitrary prose; none of those objects are spread or
 * serialized here. A future save-to-file action should consume this same summary/text generator.
 */
export function createCopySetupSummary(observations: CopySetupObservations): CopySetupPublicSummary {
  const mods = observations.mods === null
    ? null
    : observations.mods
      .map((mod) => ({
        id: boundedOptionalToken(mod.id, MAX_MOD_ID_CHARS) ?? "",
        displayName: boundedPublicScalar(mod.displayName, MAX_MOD_NAME_CHARS),
        declaredVersion: boundedPublicScalar(mod.declaredVersion, MAX_MOD_VERSION_CHARS),
      }))
      .filter((mod) => mod.id.length > 0)
      .sort(compareMods);
  const boundedMods = mods === null ? null : mods.slice(0, COPY_SETUP_MAX_MODS);

  return {
    version: COPY_SETUP_VERSION,
    preflightVersion: boundedLine(observations.preflightVersion, MAX_VERSION_CHARS),
    platform: observations.platform,
    starsectorReady: observations.starsectorReady,
    profileFingerprint: boundedOptionalToken(observations.profileFingerprint, MAX_FINGERPRINT_CHARS),
    mods: boundedMods,
    omittedModCount: mods === null ? null : Math.max(0, mods.length - boundedMods!.length),
    launchSettings: observations.launchSettings
      ? {
          resolution: boundedOptionalToken(observations.launchSettings.resolution, 32),
          fullscreen: optionalBoolean(observations.launchSettings.fullscreen),
          sound: optionalBoolean(observations.launchSettings.sound),
          maxHeapMiB: boundedInteger(observations.launchSettings.maxHeapMiB, 0, 1_048_576),
          initialHeapMiB: boundedInteger(observations.launchSettings.initialHeapMiB, 0, 1_048_576),
        }
      : null,
    optimizationPreset: observations.optimizationPreset,
    preparation: observations.preparation
      ? {
          status: observations.preparation.status,
          profileFingerprint: boundedOptionalToken(observations.preparation.profileFingerprint, MAX_FINGERPRINT_CHARS),
        }
      : null,
    latestLaunch: observations.latestLaunch
      ? {
          outcome: boundedOptionalToken(observations.latestLaunch.outcome, MAX_OUTCOME_CHARS),
          startupMillis: boundedInteger(observations.latestLaunch.startupMillis, 0, Number.MAX_SAFE_INTEGER),
          exitCode: boundedInteger(observations.latestLaunch.exitCode, -2_147_483_648, 2_147_483_647),
        }
      : null,
  };
}

export function createCopySetupText(observations: CopySetupObservations): string {
  return formatCopySetupSummary(createCopySetupSummary(observations));
}

function formatCopySetupSummary(summary: CopySetupPublicSummary): string {
  const lines = [
    "Preflight setup (public)",
    `Summary version: ${summary.version}`,
    `Preflight: ${summary.preflightVersion || "unknown"}`,
    `Platform: ${summary.platform}`,
    `Starsector detected: ${summary.starsectorReady ? "yes" : "no"}`,
  ];

  if (summary.profileFingerprint) lines.push(`Profile fingerprint: ${summary.profileFingerprint}`);
  if (summary.mods === null) {
    lines.push("Enabled mods: unavailable");
  } else {
    lines.push(`Enabled mods: ${summary.mods.length + (summary.omittedModCount ?? 0)}`);
    for (const mod of summary.mods) {
      let value = mod.id;
      if (mod.displayName) value += ` — ${mod.displayName}`;
      if (mod.declaredVersion) value += ` [${mod.declaredVersion}]`;
      lines.push(`- ${value}`);
    }
    if ((summary.omittedModCount ?? 0) > 0) {
      lines.push(`- … ${summary.omittedModCount} more mod IDs omitted`);
    }
  }

  if (summary.launchSettings) {
    const settings: string[] = [];
    if (summary.launchSettings.maxHeapMiB !== null) settings.push(`max heap ${summary.launchSettings.maxHeapMiB} MiB`);
    if (summary.launchSettings.initialHeapMiB !== null) settings.push(`initial heap ${summary.launchSettings.initialHeapMiB} MiB`);
    if (summary.launchSettings.resolution) settings.push(summary.launchSettings.resolution);
    if (summary.launchSettings.fullscreen !== null) settings.push(summary.launchSettings.fullscreen ? "fullscreen" : "windowed");
    if (summary.launchSettings.sound !== null) settings.push(summary.launchSettings.sound ? "sound on" : "sound off");
    if (settings.length > 0) lines.push(`Launch settings: ${settings.join("; ")}`);
  }

  lines.push(`Optimization preset: ${summary.optimizationPreset}`);
  if (summary.preparation) {
    lines.push(`Preparation: ${summary.preparation.status}`);
    if (summary.preparation.profileFingerprint) {
      lines.push(`Preparation fingerprint: ${summary.preparation.profileFingerprint}`);
    }
  }
  if (summary.latestLaunch) {
    if (summary.latestLaunch.outcome) lines.push(`Latest launch outcome: ${summary.latestLaunch.outcome}`);
    if (summary.latestLaunch.startupMillis !== null) lines.push(`Latest launch startup: ${summary.latestLaunch.startupMillis} ms`);
    if (summary.latestLaunch.exitCode !== null) lines.push(`Latest launch exit code: ${summary.latestLaunch.exitCode}`);
  }

  return `${lines.join("\n")}\n`;
}

export async function writeCopySetupToClipboard(
  observations: CopySetupObservations,
  writeText: (text: string) => Promise<void> = (text) => navigator.clipboard.writeText(text),
): Promise<string> {
  const text = createCopySetupText(observations);
  await writeText(text);
  return text;
}

function compareMods(left: CopySetupPublicMod, right: CopySetupPublicMod): number {
  return compareText(left.id, right.id)
    || compareText(left.displayName ?? "", right.displayName ?? "")
    || compareText(left.declaredVersion ?? "", right.declaredVersion ?? "");
}

function compareText(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function boundedPublicScalar(value: string | null | undefined, maxChars: number): string | null {
  if (typeof value !== "string" || /[\u0000-\u001f\u007f]/u.test(value)) return null;
  const bounded = boundedLine(value, maxChars);
  if (!bounded || looksPrivatePublicScalar(bounded)) return null;
  return bounded;
}

function looksPrivatePublicScalar(value: string): boolean {
  return /(?:^|\s)\/(?:[^\s/]+\/)+[^\s/]*/u.test(value)
    || /\b[A-Za-z]:[\\/][^\s]*/u.test(value)
    || /(?:^|\s)\\\\[^\s\\]+\\[^\s\\]+/u.test(value)
    || /\b[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s]/u.test(value)
    || /\b(?:token|secret|password|passwd|authorization|credential|api[-_ ]?key)\s*[:=]/iu.test(value)
    || /\b(?:HOME|USER|USERNAME|PWD)\s*=/u.test(value);
}

function boundedOptionalLine(value: string | null | undefined, maxChars: number): string | null {
  if (typeof value !== "string") return null;
  const bounded = boundedLine(value, maxChars);
  return bounded.length > 0 ? bounded : null;
}

function boundedLine(value: string, maxChars: number): string {
  if (typeof value !== "string") return "";
  const compact = value.normalize("NFC").replace(/[\u0000-\u001f\u007f\s]+/gu, " ").trim();
  const chars = Array.from(compact);
  return chars.length <= maxChars ? compact : `${chars.slice(0, maxChars - 1).join("")}…`;
}

function boundedOptionalToken(value: string | null | undefined, maxChars: number): string | null {
  if (typeof value !== "string") return null;
  const token = value.normalize("NFC").trim();
  if (!token || Array.from(token).length > maxChars || !/^[\p{L}\p{N}._:+-]+$/u.test(token)) return null;
  return token;
}

function optionalBoolean(value: boolean | null | undefined): boolean | null {
  return typeof value === "boolean" ? value : null;
}

function boundedInteger(value: number | null | undefined, min: number, max: number): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= min && value <= max ? value : null;
}
