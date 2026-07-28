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

export interface RunStateEvent {
  state: "started" | "finished";
  pid: number;
  success?: boolean;
  detail?: string;
}

export type AppStatus = "loading" | "ready" | "setup" | "error";
