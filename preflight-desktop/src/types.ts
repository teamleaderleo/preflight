export interface LaunchTarget {
  installRoot: string;
  launcher: string;
  kind: string;
  score: number;
  source: string;
}

export interface LastRun {
  directory: string;
  modifiedAt: string;
}

export interface DesktopSnapshot {
  protocol: number;
  engineVersion: string;
  platform: "mac" | "linux" | "windows" | "other";
  ready: boolean;
  selected: LaunchTarget | null;
  candidates: LaunchTarget[];
  diagnostics: string[];
  preflightHome: string;
  cachePresent: boolean;
  lastRun: LastRun | null;
}

export interface RunStarted {
  pid: number;
}

export interface PreparationStoragePlan {
  format: "preflight-preparation-storage-plan-v1";
  profileFingerprint: string;
  textureStorage: "balanced" | "fastest";
  cacheDirectory: string;
  packPath: string;
  candidateEntries: number;
  hashedEntries: number;
  uniqueContent: number;
  supportedContent: number;
  unsupportedContent: number;
  failedContent: number;
  uniqueSourceBytes: number;
  uniquePixelBytes: number;
  reusableLooseBytes: number;
  predictedLooseBytes: number;
  upperLooseBytes: number;
  predictedPackBytes: number;
  upperPackBytes: number;
  predictedMetadataBytes: number;
  upperMetadataBytes: number;
  predictedAdditionalBytes: number;
  upperBoundAdditionalBytes: number;
  safetyReserveBytes: number;
  requiredFreeBytes: number;
  usableBytes: number;
  remainingAfterUpperBoundBytes: number;
  packHit: boolean;
  complete: boolean;
  safeToPrepare: boolean;
  refusalReason: string | null;
  diagnostics: string[];
  durationMs: number;
}

export type OptimizationPreset = "recommended" | "conservative" | "off";

export interface DiagnosticsExport {
  format: "starsector-preflight-diagnostics-export-v1";
  output: string;
  bytes: number;
  sha256: string;
  files: number;
  runs: number;
  benchmarks: number;
  included: Array<{ entry: string; bytes: number; sha256: string }>;
  skipped: Array<{ entry: string; reason: string }>;
}

export interface LaunchSettings {
  format: "starsector-preflight-launch-settings-v1";
  directLaunchAvailable: boolean;
  reason: string | null;
  settings: {
    resolution: string;
    fullscreen: boolean;
    sound: boolean;
    javaOptions: string[];
  } | null;
  preferences: {
    resolution: string | null;
    fullscreen: boolean;
    sound: boolean;
    antialiasingSamples: number | null;
    uiScale: number | null;
    battleSize: number | null;
    diagnostics: string[];
  };
  limits: {
    antialiasingSamples: number[];
    uiScaleMin: number;
    uiScaleMax: number;
    uiScaleStep: number;
    battleSizeMin: number | null;
    battleSizeDefault: number | null;
    battleSizeMax: number | null;
    diagnostics: string[];
  };
  changed: boolean;
  backup: string | null;
}

export interface LaunchSettingsUpdate {
  resolution: string;
  fullscreen: boolean;
  sound: boolean;
  antialiasingSamples: number;
  uiScale: number;
  battleSize: number;
}

export interface RunStateEvent {
  state: "started" | "finished";
  pid: number;
  success?: boolean;
}

export interface PreparationStateEvent extends RunStateEvent {
  detail?: string;
  report?: string;
}

export interface CacheGroup {
  id: "acceleration" | "evidence" | "configuration" | "application" | string;
  bytes: number;
  files: number;
}

export interface CacheSnapshot {
  format: "starsector-preflight-cache-v1";
  root: string;
  present: boolean;
  total: { bytes: number; files: number };
  groups: CacheGroup[];
  currentProfileFingerprint: string | null;
  profiles: Array<{
    fingerprint: string;
    current: boolean;
    bytes: number;
    indexBytes: number;
    manifestBytes: number;
    lastModifiedMillis: number;
  }>;
}

export interface NamedProfile {
  name: string;
  installRoot: string;
  enabledMods: string[];
  modCount: number;
  profileFingerprint: string;
  savedAt: string;
  sameInstall: boolean;
  active: boolean;
  canActivate: boolean;
  missingMods: string[];
  file: string;
}

export interface ProfileList {
  format: "starsector-preflight-profile-list-v1";
  installRoot: string;
  enabledMods: string[];
  profiles: NamedProfile[];
  diagnostics: string[];
}

export interface ProfileActivationPlan {
  format: "starsector-preflight-profile-activation-v1";
  name: string;
  installRoot: string;
  savedInstallRoot: string;
  sameInstall: boolean;
  active: boolean;
  canActivate: boolean;
  applied: boolean;
  enable: string[];
  disable: string[];
  missingMods: string[];
  atomicReplace?: boolean;
  backup?: string;
}

export type AppStatus = "loading" | "ready" | "setup" | "running" | "error";
