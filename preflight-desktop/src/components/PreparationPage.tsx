import { RefreshIcon, ShieldIcon, SparklesIcon } from "../icons";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { SpeedScoreboard } from "./SpeedScoreboard";
import { resourcePresets, storagePlanApplies, type usePreparation } from "../usePreparation";
import type { StorageCleanupPlan } from "../useCacheCleanup";
import type { SpeedStanding } from "../useSpeedRecord";
import { formatBytes } from "../uiFormat";
import type { LastRun, NoticeTone, OptimizationDomain, OptimizationPreset, PlaytimeSnapshot, WireframeHull } from "../types";
import { optimizationPresets, storageGroupLabel } from "../preparationOptions";

type PreparationState = ReturnType<typeof usePreparation>;

interface PreparationPageProps {
  message: string;
  messageTone: NoticeTone;
  isReady: boolean;
  optimizationPreset: OptimizationPreset;
  disabledOptimizationDomains: OptimizationDomain[];
  preparation: PreparationState;
  cleanupPlan: StorageCleanupPlan | null;
  cleanupBusy: boolean;
  operationBlocked: boolean;
  speedStanding: SpeedStanding;
  playtime?: PlaytimeSnapshot;
  lastRun?: LastRun | null;
  instrumentHull: WireframeHull;
  onOptimizationPresetChange: (preset: OptimizationPreset) => void;
  onOptimizationDomainChange: (domain: OptimizationDomain, enabled: boolean) => void;
  onReviewCleanup: () => void;
  onCleanCache: () => void;
  onDismissCleanup: () => void;
  onOpenBenchmark: () => void;
}

export function PreparationPage({
  message,
  messageTone,
  isReady,
  optimizationPreset,
  disabledOptimizationDomains,
  preparation,
  cleanupPlan,
  cleanupBusy,
  operationBlocked,
  speedStanding,
  playtime,
  lastRun,
  instrumentHull,
  onOptimizationPresetChange,
  onOptimizationDomainChange,
  onReviewCleanup,
  onCleanCache,
  onDismissCleanup,
  onOpenBenchmark,
}: PreparationPageProps) {
  const {
    cache,
    cacheHealth,
    cacheLoading,
    cacheRepairing,
    preparationCancelling,
    preparationPercent,
    preparationPhaseLabel,
    preparationPlan,
    preparationPlanLoading,
    preparing,
    profilePrepared,
    resourcePreset,
    textureStorage,
    prepare,
    repairAndPrepare,
    refreshCache,
    setResourcePreset,
    setTextureStorage,
    stopPreparation,
  } = preparation;
  const storageBlocked = storagePlanApplies(textureStorage)
    && Boolean(preparationPlan && !preparationPlan.safeToPrepare);
  const canPrepare = !storagePlanApplies(textureStorage)
    || Boolean(preparationPlan?.safeToPrepare);
  const settledLayout = profilePrepared
    && !preparing
    && !cleanupPlan
    && !message
    && (!cacheHealth || cacheHealth.status === "ready" || cacheHealth.status === "cold");
  return (
    <div className={`prepare-page${settledLayout ? " prepare-page--settled" : ""}`}>
      <NoticeBanner message={message} tone={messageTone} />

      {cleanupPlan ? (
        <section className="card cleanup-review" aria-label="Cache cleanup review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Cleanup review</p><h2>{cleanupPlan.files === 0 ? "Nothing to remove" : `Free ${formatBytes(cleanupPlan.bytes)}?`}</h2></div>
            <button className="text-button" type="button" onClick={onDismissCleanup} disabled={cleanupBusy}>Close</button>
          </div>
          {!cleanupPlan.cache.safe ? <p className="activation-warning">{cleanupPlan.cache.refusals.join(" ")}</p> : null}
          <p className="cleanup-summary">Keeps the current profile, saved profiles, {cleanupPlan.evidence.keepRuns} recent launch reports, and {cleanupPlan.evidence.keepBenchmarks} benchmarks. Game files, mods, saves, and settings aren’t touched.</p>
          <div className="cleanup-groups">
            {cleanupPlan.cache.bytes > 0 ? <div><span>Unused prepared data</span><strong>{formatBytes(cleanupPlan.cache.bytes)} · {cleanupPlan.cache.files.toLocaleString()} files</strong></div> : null}
            {cleanupPlan.evidence.bytes > 0 ? <div><span>Old reports and benchmarks</span><strong>{formatBytes(cleanupPlan.evidence.bytes)} · {cleanupPlan.evidence.files.toLocaleString()} files</strong></div> : null}
          </div>
          <div className="activation-review__footer">
            <span><ShieldIcon /> Cleanup is recalculated before anything is removed.</span>
            <button className="button button--primary" type="button" onClick={onCleanCache} disabled={!cleanupPlan.cache.safe || cleanupPlan.files === 0 || cleanupBusy || operationBlocked}>{cleanupBusy ? "Cleaning…" : cleanupPlan.files === 0 ? "Nothing to remove" : `Free ${formatBytes(cleanupPlan.bytes)}`}</button>
          </div>
        </section>
      ) : null}

      {/*
        * "Prove it" is a real errand and a rare one, so the benchmark is reached from here rather
        * than from a primary navigation slot. It leads the page because the result of having done
        * it is the one thing this page is named after and used to be missing entirely.
        */}
      <SpeedScoreboard standing={speedStanding} isReady={isReady} playtime={playtime} lastRun={lastRun} hull={instrumentHull} onOpenBenchmark={onOpenBenchmark} />

      {cacheHealth?.status === "repair-needed" || cacheHealth?.status === "unsafe" || cacheHealth?.status === "unknown" ? (
        <section className="card run-recovery cache-recovery" aria-label="Prepared data repair">
          <div>
            <p className="eyebrow">Current profile</p>
            <h2>{cacheHealth.status === "unknown" ? "Current mod setup couldn't be inspected" : cacheHealth.status === "unsafe" ? "Cache location needs attention" : "Prepared data needs repair"}</h2>
            <p>{cacheHealth.issues.map((issue) => issue.summary).join(" ")} {cacheHealth.status === "unsafe" || cacheHealth.status === "unknown" ? "Preflight refused to remove anything." : "Only the listed profile metadata and pack will be removed; shared cache blobs, game files, mods, and saves stay in place."}</p>
            <small>{cacheHealth.repairFiles.toLocaleString()} artifact{cacheHealth.repairFiles === 1 ? "" : "s"} · {formatBytes(cacheHealth.repairBytes)}</small>
          </div>
          <div className="run-recovery__actions">
            {cacheHealth.status === "repair-needed" ? <button className="button button--primary button--compact" type="button" onClick={() => void repairAndPrepare(false)} disabled={operationBlocked || cacheRepairing}>{cacheRepairing ? "Repairing…" : "Repair and rebuild"}</button> : null}
          </div>
        </section>
      ) : null}

      <section className="card preflight-summary-card">
        <div>
          <div className="heading-with-info">
            <h2>Optimizations</h2>
            <InfoTip label="About Preflight optimizations">Before changing runtime code, Preflight checks that it exactly matches a reviewed version. Anything unfamiliar stays untouched.</InfoTip>
          </div>
          {/*
            * The switch stated its own position and nothing else, so the page named Speed opened
            * on a control whose consequence was written down only in the tooltip beside it. Both
            * positions are legitimate choices -- off is the first thing to try when the game
            * misbehaves -- and each says what the next launch will do.
            */}
          <p>{optimizationPreset === "off"
            ? "Preflight won’t apply optimizations. Prepared data stays here for when you turn them back on."
            : optimizationPreset === "conservative"
              ? "Compatibility mode uses startup caches with the game’s original code. Try it if the default mode causes trouble."
              : "Preflight prepares your mods once, then reuses that work to start the game faster."}</p>
        </div>
        <label className="simple-switch">
          <input type="checkbox" aria-label="Use Preflight optimizations" checked={optimizationPreset !== "off"} onChange={(event) => onOptimizationPresetChange(event.target.checked ? "recommended" : "off")} disabled={operationBlocked} />
          <span>{optimizationPreset === "off" ? "Off" : optimizationPreset === "conservative" ? "Compatibility" : "On"}</span>
        </label>
      </section>

      {!profilePrepared || preparing ? <section className="card prepare-action">
        <div>
          <strong>{preparationCancelling ? "Stopping preparation" : preparing ? preparationPhaseLabel ?? "Preparation is running" : preparationPlanLoading ? "Calculating disk requirement" : storageBlocked ? "Full preparation doesn’t fit" : preparationPlan?.safeToPrepare || !storagePlanApplies(textureStorage) ? "Ready to prepare" : "Preparation needs attention"}</strong>
          <span>{preparing ? `${preparationPercent}% complete · finished artifacts stay reusable` : storageBlocked ? "Minimal preparation uses a few megabytes and still speeds up startup." : `${textureStorage === "balanced" ? "Balanced storage selected" : textureStorage === "fastest" ? "Fastest raw storage selected" : "Minimal disk use selected · no prepared textures"} · ${resourcePresets[resourcePreset].label.toLowerCase()} resource use`}</span>
          {preparing ? <div className="preparation-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={preparationPercent}><span style={{ width: `${preparationPercent}%` }} /></div> : null}
        </div>
        <div className="prepare-actions">
          {preparing ? <button className="button button--quiet" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>{preparationCancelling ? "Stopping…" : "Stop safely"}</button> : null}
          <button className="button button--primary" type="button" onClick={() => void prepare(false, storageBlocked ? "minimal" : textureStorage)} disabled={operationBlocked || cacheRepairing || cacheHealth?.status === "repair-needed" || cacheHealth?.status === "unsafe" || cacheHealth?.status === "unknown" || !isReady || preparationPlanLoading || (!storageBlocked && !canPrepare)}><SparklesIcon />{preparing ? "Preparing…" : preparationPlanLoading ? "Calculating…" : storageBlocked ? "Prepare with less disk" : "Prepare current profile"}</button>
        </div>
      </section> : null}

      <section className="card storage-card storage-card--compact">
        <div className="card__heading">
          <div>
            <div className="heading-with-info"><h2>Storage</h2><InfoTip label="About Preflight storage">Prepared files are shared across matching mod sets. Above 12 GB, Preflight quietly removes data that no current or saved profile needs. Game files, mods, saves, and settings are never touched.</InfoTip></div>
          </div>
          <button className="icon-button icon-button--small" type="button" onClick={() => void refreshCache()} aria-label="Refresh cache storage" disabled={cacheLoading || operationBlocked}><RefreshIcon className={cacheLoading ? "spin" : ""} /></button>
        </div>
        <div className="storage-summary-row">
          <div><strong className="storage-total">{cache ? formatBytes(cache.groups.find((group) => group.id === "acceleration")?.bytes ?? 0) : "…"}</strong><span className="storage-files">Prepared data</span></div>
          <div><span>Reports and benchmarks</span><strong>{cache ? formatBytes(cache.groups.find((group) => group.id === "evidence")?.bytes ?? 0) : "…"}</strong><small>Old sessions can be removed without slowing launches.</small></div>
          {/* The conservative build bound matters before preparation, not on every launch. */}
          {!profilePrepared || preparing ? <div><span>Free space needed to prepare</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.requiredFreeBytes) : "…"}</strong><small>Finished data uses much less.</small></div> : null}
          <button className="button button--quiet button--compact" type="button" onClick={onReviewCleanup} disabled={cleanupBusy || operationBlocked}>{cleanupBusy ? "Checking…" : "Review cleanup"}</button>
        </div>
        {preparationPlan && !preparationPlan.safeToPrepare ? (
          <div className="storage-refusal">
            <p className="activation-warning">{preparationPlan.refusalReason}</p>
          </div>
        ) : null}
        <details className="storage-breakdown">
          <summary>Storage details</summary>
          <div className="storage-breakdown__grid">
            {(cache?.groups ?? []).map((group) => {
              const { label, detail } = storageGroupLabel(group.id);
              return <div key={group.id}><span>{label}</span><strong>{formatBytes(group.bytes)}</strong>{detail ? <small>{detail}</small> : null}</div>;
            })}
            {(cache?.uncategorizedBytes ?? 0) > 0 ? <div><span>Other Preflight data</span><strong>{formatBytes(cache?.uncategorizedBytes ?? 0)}</strong><small>Files that don’t fit a category above.</small></div> : null}
            <div><span>Preparing this profile adds</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.predictedAdditionalBytes) : "…"}</strong><small>A one-off cost for the current mod list. Preparation won’t start unless the larger figure above fits.</small></div>
            <div><span>Free on this disk</span><strong>{preparationPlan ? formatBytes(preparationPlan.usableBytes) : "…"}</strong><small>Space currently free where Preflight stores its data.</small></div>
          </div>
        </details>
      </section>

      <details className="card settings-disclosure preflight-advanced">
        <summary><span><strong>Advanced controls</strong><small>Disk, speed, and resource limits</small></span></summary>
        <div className="settings-disclosure__body preflight-advanced__body">
          <section>
            <h2>Launch policy</h2>
            <div className="optimization-choices" role="radiogroup" aria-label="Optimization preset">
              {optimizationPresets.map((preset) => (
                <label className={`choice-card ${optimizationPreset === preset.id ? "choice-card--selected" : ""}`} key={preset.id}>
                  <input type="radio" name="optimization-preset" aria-label={`${preset.label} optimizations`} checked={optimizationPreset === preset.id} onChange={() => onOptimizationPresetChange(preset.id)} disabled={operationBlocked} />
                  <span><strong>{preset.label}</strong><small>{preset.description}</small></span>
                  <b>{preset.badge}</b>
                </label>
              ))}
            </div>
          </section>

          <section>
            <h2>Texture storage</h2>
            <label className={`choice-card ${textureStorage === "balanced" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Balanced texture storage" checked={textureStorage === "balanced"} onChange={() => setTextureStorage("balanced")} disabled={operationBlocked} />
              <span><strong>Balanced</strong><small>Lossless LZ4; raw only when compression doesn’t help</small></span>
              <b>Default</b>
            </label>
            <label className={`choice-card ${textureStorage === "fastest" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Fastest texture storage" checked={textureStorage === "fastest"} onChange={() => setTextureStorage("fastest")} disabled={operationBlocked} />
              <span><strong>Fastest</strong><small>Several GB more for a small startup gain</small></span>
            </label>
            <label className={`choice-card ${textureStorage === "minimal" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Minimal disk use" checked={textureStorage === "minimal"} onChange={() => setTextureStorage("minimal")} disabled={operationBlocked} />
              <span><strong>Use almost no disk</strong><small>Skips prepared textures: megabytes instead of gigabytes, and a smaller speedup</small></span>
            </label>
          </section>

          <section>
            <h2>Preparation resources</h2>
            <div className="preset-row">
              {Object.entries(resourcePresets).map(([id, preset]) => (
                <button key={id} type="button" className={resourcePreset === id ? "preset preset--selected" : "preset"} onClick={() => setResourcePreset(id as keyof typeof resourcePresets)} disabled={operationBlocked}>
                  <strong>{preset.label}</strong><span>{preset.workers} workers · {preset.memoryMib} MiB</span>
                </button>
              ))}
            </div>
          </section>

          <section>
            <h2>Prepared caches</h2>
            <div className="optimization-domain-list">
              <label className="optimization-domain">
                <input type="checkbox" aria-label="Prepared textures" checked={!disabledOptimizationDomains.includes("prepared-textures")} onChange={(event) => onOptimizationDomainChange("prepared-textures", event.target.checked)} disabled={operationBlocked || optimizationPreset === "off"} />
                <span><strong>Textures</strong><small>Prepared only when the GPU supports them</small></span>
              </label>
              <label className="optimization-domain">
                <input type="checkbox" aria-label="Prepared audio" checked={!disabledOptimizationDomains.includes("prepared-audio")} onChange={(event) => onOptimizationDomainChange("prepared-audio", event.target.checked)} disabled={operationBlocked || optimizationPreset === "off"} />
                <span><strong>Audio</strong><small>Reused only when the files and decoder match</small></span>
              </label>
            </div>
          </section>
        </div>
      </details>

    </div>
  );
}
