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
