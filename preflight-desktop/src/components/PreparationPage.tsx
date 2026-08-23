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

function preparationReuseSummary(
  preparationPlan: PreparationState["preparationPlan"],
  textureStorage: PreparationState["textureStorage"],
): string | null {
  if (!preparationPlan || !storagePlanApplies(textureStorage)) return null;
  if (preparationPlan.reusableLooseBytes > 0 && preparationPlan.packHit) {
    return `${formatBytes(preparationPlan.reusableLooseBytes)} of compatible prepared texture data is already on disk. The current profile texture pack will be reused.`;
  }
  if (preparationPlan.reusableLooseBytes > 0) {
    return `${formatBytes(preparationPlan.reusableLooseBytes)} of compatible prepared texture data is already on disk.`;
  }
  if (preparationPlan.packHit) return "The current profile texture pack will be reused.";
  return null;
}

function preparationOverrideSummary(
  textureStorage: PreparationState["textureStorage"],
  resourcePreset: PreparationState["resourcePreset"],
): string | null {
  const consequences: string[] = [];
  if (textureStorage === "fastest") consequences.push("Keeps textures uncompressed and uses more disk");
  if (textureStorage === "compact") consequences.push("Keeps the textures observed during a real launch");
  if (textureStorage === "minimal") consequences.push("Skips prepared textures to use much less disk");
  if (resourcePreset === "gentle") consequences.push("Uses fewer preparation resources");
  if (resourcePreset === "eager") consequences.push("Uses more preparation resources");
  return consequences.length > 0 ? `${consequences.join(" · ")}.` : null;
}

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
  const reuseSummary = preparationReuseSummary(preparationPlan, textureStorage);
  const overrideSummary = preparationOverrideSummary(textureStorage, resourcePreset);
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
        <section className="card run-recovery cache-recovery" aria-label="Prepared data needs attention">
          <div>
            <p className="eyebrow">Current mod setup</p>
            <h2>{cacheHealth.status === "unknown" ? "Prepared data couldn't be checked" : cacheHealth.status === "unsafe" ? "Prepared data location needs attention" : "Prepared data needs repair"}</h2>
            <p>{cacheHealth.issues.map((issue) => issue.summary).join(" ")} {cacheHealth.status === "unsafe" || cacheHealth.status === "unknown" ? "Preflight left this prepared data in place. Starsector, mods, and saves stay unchanged." : "Preflight will rebuild only its prepared data for this mod setup. Starsector, mods, and saves stay unchanged."}</p>
            {cacheHealth.status === "repair-needed" ? (
              <details className="run-recovery__details">
                <summary>Repair details</summary>
                <small>{cacheHealth.repairFiles.toLocaleString()} prepared file{cacheHealth.repairFiles === 1 ? "" : "s"} · {formatBytes(cacheHealth.repairBytes)}</small>
              </details>
            ) : null}
          </div>
          <div className="run-recovery__actions">
            {cacheHealth.status === "repair-needed" ? <button className="button button--primary button--compact" type="button" onClick={() => void repairAndPrepare(false)} disabled={operationBlocked || cacheRepairing}>{cacheRepairing ? "Rebuilding…" : "Rebuild prepared data"}</button> : null}
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
              : "Preflight creates reusable startup data for your current mod setup, then reuses it on later launches."}</p>
        </div>
        <label className="simple-switch">
          <input type="checkbox" aria-label="Use Preflight optimizations" checked={optimizationPreset !== "off"} onChange={(event) => onOptimizationPresetChange(event.target.checked ? "recommended" : "off")} disabled={operationBlocked} />
          <span>{optimizationPreset === "off" ? "Off" : optimizationPreset === "conservative" ? "Compatibility" : "On"}</span>
        </label>
      </section>

      {!profilePrepared || preparing ? <section className="card prepare-action">
        <div>
          <strong>{preparationCancelling ? "Stopping preparation" : preparing ? preparationPhaseLabel ?? "Preparation is running" : preparationPlanLoading ? "Calculating disk requirement" : storageBlocked ? "Full preparation doesn’t fit" : preparationPlan?.safeToPrepare || !storagePlanApplies(textureStorage) ? "Ready to prepare" : "Preparation needs attention"}</strong>
          {preparing ? <span>{preparationPercent === null
            ? "Reconnected after restart · finished work stays reusable"
            : `${preparationPercent}% complete · finished work stays reusable`}</span>
            : storageBlocked
              ? <span>Minimal skips prepared textures and keeps the other startup caches.</span>
              : overrideSummary
                ? <span>{overrideSummary}</span>
                : null}
          {!preparing && reuseSummary ? <small className="field-note">{reuseSummary}</small> : null}
          {preparing && preparationPercent !== null ? <div className="preparation-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={preparationPercent}><span style={{ width: `${preparationPercent}%` }} /></div> : null}
        </div>
        <div className="prepare-actions">
          {preparing ? <button className="button button--quiet" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>{preparationCancelling ? "Stopping…" : "Stop safely"}</button> : null}
          <button className="button button--primary" type="button" onClick={() => void prepare(false, storageBlocked ? "minimal" : textureStorage)} disabled={operationBlocked || cacheRepairing || cacheHealth?.status === "repair-needed" || cacheHealth?.status === "unsafe" || cacheHealth?.status === "unknown" || !isReady || preparationPlanLoading || (!storageBlocked && !canPrepare)}><SparklesIcon />{preparing ? preparationPercent === null ? "Preparation in progress…" : "Preparing…" : preparationPlanLoading ? "Calculating…" : storageBlocked ? "Prepare with less disk" : "Prepare current mod setup"}</button>
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
          {/* The temporary build requirement matters before preparation, not on every launch. */}
          {!profilePrepared || preparing ? <div><span>Space needed while preparing</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.requiredFreeBytes) : "…"}</strong><small>Includes temporary build files and free-space reserve.</small></div> : null}
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
            {storagePlanApplies(textureStorage) && (preparationPlan?.reusableLooseBytes ?? 0) > 0 ? <div><span>Compatible prepared texture data on disk</span><strong>{formatBytes(preparationPlan?.reusableLooseBytes ?? 0)}</strong><small>Compatible texture blobs already present; this count can include alternate encodings that remain on disk while preparation uses another.</small></div> : null}
            {storagePlanApplies(textureStorage) && preparationPlan?.packHit ? <div><span>Current profile texture pack</span><strong>Will be reused</strong><small>The exact prepared texture pack matches this profile and the builder’s required entry order, so preparation will use it without rebuilding.</small></div> : null}
            <div><span>Finished texture data</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.predictedRetainedTextureBytes ?? preparationPlan.predictedPackBytes) : "…"}</strong><small>Preflight removes the temporary copies when preparation finishes.</small></div>
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
            <label className={`choice-card ${textureStorage === "compact" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Compact texture storage" checked={textureStorage === "compact"} onChange={() => setTextureStorage("compact")} disabled={operationBlocked} />
              <span><strong>Compact</strong><small>About a third of the preparation time and half the disk after one observed launch</small></span>
              <b>Efficient</b>
            </label>
            <label className={`choice-card ${textureStorage === "balanced" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Balanced texture storage" checked={textureStorage === "balanced"} onChange={() => setTextureStorage("balanced")} disabled={operationBlocked} />
              <span><strong>Balanced</strong><small>Lossless LZ4; raw only when compression doesn’t help</small></span>
              <b>Default</b>
            </label>
            <label className={`choice-card ${textureStorage === "fastest" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Uncompressed texture storage" checked={textureStorage === "fastest"} onChange={() => setTextureStorage("fastest")} disabled={operationBlocked} />
              <span><strong>Uncompressed</strong><small>More disk; useful for comparison and unusual hardware</small></span>
              <b>Advanced</b>
            </label>
            <label className={`choice-card ${textureStorage === "minimal" ? "choice-card--selected" : ""}`}>
              <input type="radio" name="texture-storage" aria-label="Minimal disk use" checked={textureStorage === "minimal"} onChange={() => setTextureStorage("minimal")} disabled={operationBlocked} />
              <span><strong>Minimal disk</strong><small>Skips prepared textures for much lower disk use and a smaller speedup</small></span>
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
            <h2>Prepared data</h2>
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
