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

export interface DesktopSmokeProbe {
  protocol: number;
  probe: {
    ready: boolean;
    driver: {
      id: string;
      version: number;
      platform: string;
      capabilities: string[];
    } | null;
    diagnostics: string[];
  };
}

export interface DesktopSmokeStateEvent {
  state: "started" | "cancelling" | "cancelled" | "finished";
  pid: number;
  success?: boolean;
  detail?: string;
  runDirectory: string;
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

export interface ReportIntakeStatus {
  configured: boolean;
  origin: string | null;
  reason: string | null;
}

export interface ReportDeletion {
  method: "DELETE";
  url: string;
  token: string;
}

export interface ReportReceipt {
  protocolVersion: number;
  caseId: string;
  objectKey: string;
  bytes: number;
  sha256: string;
  productVersion: string;
  receivedAt: string;
  retentionDeadline: string;
  deletion: ReportDeletion;
  signature: string;
}

export interface ReportUploadStateEvent {
  state: "starting" | "uploading" | "finalizing" | "cancelling" | "cancelled" | "finished" | "failed";
  uploadId: number;
  uploadedBytes: number;
  totalBytes: number;
  caseId: string | null;
  receipt: ReportReceipt | null;
  detail: string | null;
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
  state: "started" | "cancelling" | "cancelled" | "finished";
  pid: number;
  success?: boolean;
}

export interface PreparationStateEvent extends RunStateEvent {
  detail?: string;
  report?: string;
}

export interface PreparationProgressEvent {
  pid: number;
  format: "preflight-preparation-progress-v1";
  phase: string;
  state: "started" | "completed";
  totalPhases: number;
  status?: "SUCCESS" | "FAILED" | "SKIPPED";
  durationMs?: number;
  metrics: Record<string, number>;
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

export interface CacheCleanupPlan {
  format: "starsector-preflight-cache-prune-v1";
  safe: boolean;
  applied: boolean;
  currentProfileFingerprint: string | null;
  survivingProfileFingerprints: string[];
  bytes: number;
  files: number;
  reachableTextureBlobs: number;
  reachablePreparedAudioBlobs: number;
  refusals: string[];
  groups: Array<{ reason: string; bytes: number; files: number }>;
  removals: Array<{ path: string; bytes: number; reason: string }>;
  removalsTruncated: boolean;
}

export type RemovalScope = "launcher" | "all-data";

export interface RemovalPlan {
  format: "preflight-removal-v1";
  scope: RemovalScope;
  safe: boolean;
  applied: boolean;
  bytes: number;
  files: number;
  targets: Array<{
    kind: "launch-integration" | "installed-engine" | "preflight-data";
    label: string;
    path: string;
    bytes: number;
    files: number;
  }>;
  refusals: string[];
  preserves: string[];
}

export interface UpdateStatus {
  format: "preflight-update-v1";
  configured: boolean;
  currentVersion: string;
  available: boolean;
  version: string | null;
  date: string | null;
  notes: string | null;
  reason: string | null;
}

export interface UpdateProgressEvent {
  state: "downloading" | "downloaded" | "installed";
  downloadedBytes: number;
  contentLength: number | null;
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
