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
  installRoot?: string | null;
  profileFingerprint?: string | null;
  adapterHealth: AdapterHealthSummary | null;
  started?: string | null;
  ended?: string | null;
  wrapperPid?: number | null;
  wrapperStartedAt?: string | null;
  startupMillis?: number | null;
  outcome?: string | null;
  exitCode?: number | null;
}

export interface AdapterHealthSummary {
  format: "starsector-preflight-adapter-health-v1";
  status: "ACTIVE" | "PARTIAL" | "SAFE_FALLBACK" | "DISABLED" | "PROBE_ONLY" | "NO_TARGETS" | "ERROR";
  accelerationsActive: boolean;
  originalCodeRetained: boolean;
  reviewRecommended: boolean;
  transformationsApplied: number;
  registryTargets: number;
  containedFailures: number;
  evidenceKinds: string[];
  suggestedActions: string[];
}

export interface PlaytimeSnapshot {
  readable: boolean;
  totalMillis: number;
  longestSessionMillis: number;
  averageMillis: number;
  launches: number;
  sessionsWithoutDuration: number;
  first: string | null;
  last: string | null;
}

export interface WireframePoint {
  x: number;
  y: number;
}

export interface WireframeEngine extends WireframePoint {
  angle: number;
  width: number;
  length: number;
}

export interface WireframeMount extends WireframePoint {
  angle: number;
  size: "MEDIUM" | "LARGE";
  mount: string;
}

/**
 * The two loop families are dialled separately, as in the design page.
 *
 * A hull is outer loops marched off the sprite's alpha -- the silhouette and the voids punched
 * through it -- and inner loops marched off its lighting. They are the same kind of thing once
 * found, but they are not the same size, and a tolerance that reads well on a 200-point hull
 * outline flattens a 12-point flight deck. One shared dial with fixed multipliers was the
 * version before this in `docs/design/hangar-light/`, and it was rejected there for this reason.
 */
export interface WireframeTuning {
  outerDetail: number;
  outerSmooth: number;
  innerDetail: number;
  innerSmooth: number;
  height: number;
}

export interface WireframeInnerContour {
  height: number;
  points: WireframePoint[];
}

export interface WireframeTrace {
  holes: WireframePoint[][];
  inner: WireframeInnerContour[];
}

export interface WireframeHull {
  id: string;
  name: string;
  hullSize: string;
  style: string;
  bounds: WireframePoint[];
  engines: WireframeEngine[];
  mounts: WireframeMount[];
  featured: boolean;
  trace?: WireframeTrace;
  tuning?: WireframeTuning;
}

export interface WireframeHullCatalog {
  format: "preflight-wireframe-hulls-v1";
  hulls: WireframeHull[];
  skipped: number;
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
  playtime: PlaytimeSnapshot;
}

export interface DesktopHomeState {
  format: "starsector-preflight-desktop-home-state-v1";
  installRoot: string;
  cacheInspection: CacheInspection | null;
  profiles: ProfileList | null;
  launchSettings: LaunchSettings | null;
  errors: Partial<Record<"cacheInspection" | "profiles" | "launchSettings", string>>;
}

export interface RunStarted {
  pid: number;
}

export type AfterLaunchBehavior = "minimize" | "keep" | "quit";

export interface StopGameResult {
  inspected: number;
  stopped: number;
  stillRunning: number;
  skipped: number;
  forced: boolean;
}

export interface OperationSnapshot {
  format: "preflight-operation-state-v1";
  gamePid: number | null;
  gameRecovered: boolean;
  desktopSmokePid: number | null;
  desktopSmokeRunDirectory: string | null;
  preparationPid: number | null;
  reportUploadId: number | null;
  reportUploadTotalBytes: number | null;
  diagnosticsExporting: boolean;
  updateChecking: boolean;
  updateInstalling: boolean;
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
  comparison?: DesktopBenchmarkComparison | null;
}

export interface DesktopBenchmarkMetric {
  measurementOnly: number;
  optimized: number;
  delta: number;
  improvementPercent: number | null;
}

export interface DesktopBenchmarkComparison {
  available: boolean;
  metrics: Record<string, DesktopBenchmarkMetric>;
  identity?: {
    profileFingerprint: string;
    benchmarkIdentitySha256: string;
  };
  context?: {
    measurementOnly?: DesktopBenchmarkRuntimeContext | null;
    optimized?: DesktopBenchmarkRuntimeContext | null;
    measurementOverhead?: {
      measurementOnly?: DesktopBenchmarkMeasurementOverhead | null;
      optimized?: DesktopBenchmarkMeasurementOverhead | null;
    };
    storage?: {
      scope: "all-prepared-data";
      bytes: number;
      files: number;
    } | null;
  };
}

export interface DesktopBenchmarkMeasurementOverhead {
  samples: number;
  totalNanos: number;
  averageMicros: number;
  maximumMicros: number;
  routeSharePercent: number;
  withinBudget: boolean;
}

export interface DesktopBenchmarkRuntimeContext {
  adapterMode?: string;
  cacheHits: number;
  cacheMisses: number;
  fallbacks: number;
  failures: number;
  memoryAvailablePercent: number | null;
}

/**
 * What preparation is asked to build. `balanced` and `fastest` choose how prepared textures are
 * stored; `minimal` skips them, which is the whole of the disk cost. A plan only ever describes the
 * two that build textures — `minimal` has nothing to plan.
 */
export type TextureStorage = "balanced" | "fastest" | "minimal";

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
export type OptimizationDomain = "prepared-textures" | "prepared-audio";

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
  applyBoundary: {
    kind: "quiescent-apply-v1";
    scope: "global-starsector-settings";
    instruction: string;
    confirmationLabel: string;
    confirmationOption: string;
    leaseScope: string;
  };
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
    battleSizeExtendedMax: number | null;
    diagnostics: string[];
  };
  memory: {
    available: boolean;
    editable: boolean;
    maxHeapMiB: number | null;
    initialHeapMiB: number | null;
    source: string | null;
    sourceKind: string | null;
    reason: string | null;
    diagnostics: string[];
    backup: string | null;
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
  memoryMiB: number | null;
}

export interface RunStateEvent {
  state: "started" | "running" | "cancelling" | "cancelled" | "finished";
  pid: number;
  success?: boolean;
  detail?: string;
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
  uncategorizedBytes: number;
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

export interface CacheHealth {
  format: "starsector-preflight-cache-health-v1";
  status: "unknown" | "unsafe" | "cold" | "ready" | "repair-needed";
  profileFingerprint: string | null;
  issues: Array<{
    artifact: string;
    summary: string;
    path: string;
  }>;
  repairBytes: number;
  repairFiles: number;
}

export interface CacheInspection {
  format: "starsector-preflight-cache-inspection-v1";
  cache: CacheSnapshot;
  health: CacheHealth;
}

export interface CacheRepair {
  format: "starsector-preflight-cache-repair-v1";
  safe: boolean;
  applied: boolean;
  status: "unknown" | "unsafe" | "profile-changed" | "cold" | "ready" | "repair-needed";
  profileFingerprint: string | null;
  bytes: number;
  files: number;
  targets: Array<{
    artifact: string;
    path: string;
    bytes: number;
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

export interface EvidenceCleanupPlan {
  format: "starsector-preflight-evidence-prune-v1";
  applied: boolean;
  keepRuns: number;
  keepBenchmarks: number;
  bytes: number;
  files: number;
  removedBytes: number;
  sessions: Array<{
    kind: "run" | "benchmark";
    name: string;
    path: string;
    bytes: number;
    files: number;
    modifiedMillis: number;
  }>;
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

export interface ProfileMutationPlan {
  format: "starsector-preflight-profile-mutation-v1";
  operation: "rename" | "duplicate" | "delete";
  name: string;
  targetName: string | null;
  profileFingerprint: string;
  active: boolean;
  modCount: number;
  applied: boolean;
  preparedDataKept: boolean;
  backup?: string;
}

export type AppStatus = "loading" | "ready" | "setup" | "launching" | "running" | "error";
export type NoticeTone = "info" | "success" | "warning" | "error";
export type Announce = (message: string, tone?: NoticeTone) => void;
