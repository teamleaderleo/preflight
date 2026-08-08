import { RefreshIcon, ShieldIcon, SparklesIcon } from "../icons";
import { resourcePresets, type usePreparation } from "../usePreparation";
import { formatBytes } from "../uiFormat";
import type { CacheCleanupPlan, OptimizationPreset } from "../types";

export const optimizationPresets: Array<{
  id: OptimizationPreset;
  label: string;
  description: string;
  badge: string;
}> = [
  {
    id: "recommended",
    label: "Recommended",
    description: "All reviewed startup and gameplay optimizations. True-size textures.",
    badge: "Default",
  },
  {
    id: "conservative",
    label: "Conservative",
    description: "Portable startup caches and padded textures. Gameplay adapters stay off.",
    badge: "Fallback",
  },
  {
    id: "off",
    label: "Off",
    description: "Wrapper and bounded process report only.",
    badge: "Troubleshoot",
  },
];

type PreparationState = ReturnType<typeof usePreparation>;

interface PreparationPageProps {
  message: string;
  isReady: boolean;
  optimizationPreset: OptimizationPreset;
  preparation: PreparationState;
  cleanupPlan: CacheCleanupPlan | null;
  cleanupBusy: boolean;
  operationBlocked: boolean;
  onOptimizationPresetChange: (preset: OptimizationPreset) => void;
  onReviewCleanup: () => void;
  onCleanCache: () => void;
  onDismissCleanup: () => void;
}

export function PreparationPage({
  message,
  isReady,
  optimizationPreset,
  preparation,
  cleanupPlan,
  cleanupBusy,
  operationBlocked,
  onOptimizationPresetChange,
  onReviewCleanup,
  onCleanCache,
  onDismissCleanup,
}: PreparationPageProps) {
  const {
    cache,
    cacheLoading,
    preparationCancelling,
    preparationPercent,
    preparationPhaseLabel,
    preparationPlan,
    preparationPlanLoading,
    preparing,
    resourcePreset,
    textureStorage,
    prepare,
    refreshCache,
    setResourcePreset,
    setTextureStorage,
    stopPreparation,
  } = preparation;
  const selectedOptimization = optimizationPresets.find((preset) => preset.id === optimizationPreset)
    ?? optimizationPresets[0];
  return (
    <div className="prepare-page">
      {message ? <div className="notice" role="status"><span>✦</span><p>{message}</p></div> : null}

      <section className="card optimization-card">
        <div className="card__heading">
          <div><p className="eyebrow">Runtime policy</p><h2>Optimizations</h2></div>
          <div className={`tiny-status ${optimizationPreset !== "off" ? "tiny-status--good" : ""}`}><span />{selectedOptimization.label}</div>
        </div>
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

      <div className="prepare-grid">
        <section className="card prepare-options">
          <div className="card__heading">
            <div><p className="eyebrow">Space and speed</p><h2>Texture storage</h2></div>
            <div className={`tiny-status ${cache?.currentProfileFingerprint ? "tiny-status--good" : ""}`}><span />{cacheLoading ? "Checking" : cache?.currentProfileFingerprint ? "Profile detected" : "Not prepared"}</div>
          </div>
          <label className={`choice-card ${textureStorage === "balanced" ? "choice-card--selected" : ""}`}>
            <input type="radio" name="texture-storage" checked={textureStorage === "balanced"} onChange={() => setTextureStorage("balanced")} disabled={operationBlocked} />
            <span><strong>Balanced</strong><small>Lossless LZ4; raw only when compression doesn’t help</small></span>
            <b>Default</b>
          </label>
          <label className={`choice-card ${textureStorage === "fastest" ? "choice-card--selected" : ""}`}>
            <input type="radio" name="texture-storage" checked={textureStorage === "fastest"} onChange={() => setTextureStorage("fastest")} disabled={operationBlocked} />
            <span><strong>Fastest</strong><small>Raw upload-ready pixels; several GB more for a small startup gain</small></span>
          </label>

          <div className="resource-heading"><strong>Preparation resources</strong><span>Only affects the one-time build</span></div>
          <div className="preset-row">
            {Object.entries(resourcePresets).map(([id, preset]) => (
              <button key={id} type="button" className={resourcePreset === id ? "preset preset--selected" : "preset"} onClick={() => setResourcePreset(id as keyof typeof resourcePresets)} disabled={operationBlocked}>
                <strong>{preset.label}</strong><span>{preset.workers} workers · {preset.memoryMib} MiB</span>
              </button>
            ))}
          </div>
        </section>

        <section className="card storage-card">
          <div className="card__heading">
            <div><p className="eyebrow">On this computer</p><h2>Preflight storage</h2></div>
            <button className="icon-button icon-button--small" type="button" onClick={() => void refreshCache()} aria-label="Refresh cache storage" disabled={cacheLoading || operationBlocked}><RefreshIcon className={cacheLoading ? "spin" : ""} /></button>
          </div>
          <strong className="storage-total">{cache ? formatBytes(cache.total.bytes) : "—"}</strong>
          <span className="storage-files">{cache ? `${cache.total.files.toLocaleString()} files` : "Reading cache…"}</span>
          <div className="storage-groups">
            {(cache?.groups ?? []).map((group) => <div key={group.id}><span>{group.id}</span><strong>{formatBytes(group.bytes)}</strong></div>)}
            {(cache?.uncategorizedBytes ?? 0) > 0 ? <div><span>Other Preflight data</span><strong>{formatBytes(cache?.uncategorizedBytes ?? 0)}</strong></div> : null}
          </div>
          <div className="storage-groups storage-plan" aria-label="Preparation storage plan">
            <div><span>Predicted additional</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.predictedAdditionalBytes) : "—"}</strong></div>
            <div><span>Conservative bound</span><strong>{preparationPlan ? formatBytes(preparationPlan.upperBoundAdditionalBytes) : "—"}</strong></div>
            <div><span>Safety reserve</span><strong>{preparationPlan ? formatBytes(preparationPlan.safetyReserveBytes) : "—"}</strong></div>
            <div><span>Available now</span><strong>{preparationPlan ? formatBytes(preparationPlan.usableBytes) : "—"}</strong></div>
          </div>
          {preparationPlan && !preparationPlan.safeToPrepare ? <p className="activation-warning">{preparationPlan.refusalReason}</p> : null}
          <p className="storage-note">Cleanup is always previewed before deletion.{(cache?.uncategorizedBytes ?? 0) > 0 ? " Other includes retained cache formats and files outside the active categories." : ""}</p>
          <button className="button button--quiet button--compact" type="button" onClick={onReviewCleanup} disabled={cleanupBusy || operationBlocked}>{cleanupBusy ? "Checking…" : "Review cleanup"}</button>
        </section>
      </div>

      <section className="card prepare-action">
        <div>
          <strong>{preparationCancelling ? "Stopping preparation" : preparing ? preparationPhaseLabel ?? "Preparation is running" : preparationPlanLoading ? "Calculating disk requirement" : preparationPlan?.safeToPrepare ? "There’s room to prepare this profile" : "Preparation needs attention"}</strong>
          <span>{preparing ? `${preparationPercent}% complete · finished artifacts stay reusable` : `${textureStorage === "balanced" ? "Balanced storage selected" : "Fastest raw storage selected"} · ${resourcePresets[resourcePreset].label.toLowerCase()} resource use`}</span>
          {preparing ? <div className="preparation-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={preparationPercent}><span style={{ width: `${preparationPercent}%` }} /></div> : null}
        </div>
        <div className="prepare-actions">
          {preparing ? <button className="button button--quiet" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>{preparationCancelling ? "Stopping…" : "Stop safely"}</button> : null}
          <button className="button button--primary" type="button" onClick={() => void prepare(false)} disabled={operationBlocked || !isReady || preparationPlanLoading || !preparationPlan?.safeToPrepare}><SparklesIcon />{preparing ? "Preparing…" : preparationPlanLoading ? "Calculating…" : "Prepare current profile"}</button>
        </div>
      </section>

      {cleanupPlan ? (
        <section className="card cleanup-review" aria-label="Cache cleanup review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Nothing removed yet</p><h2>{cleanupPlan.files === 0 ? "Everything here is still useful" : `Free ${formatBytes(cleanupPlan.bytes)}?`}</h2></div>
            <button className="text-button" type="button" onClick={onDismissCleanup} disabled={cleanupBusy}>Close</button>
          </div>
          {!cleanupPlan.safe ? <p className="activation-warning">{cleanupPlan.refusals.join(" ")}</p> : null}
          <p className="cleanup-summary">Preflight will keep the current profile and {Math.max(0, cleanupPlan.survivingProfileFingerprints.length - 1).toLocaleString()} named profile{cleanupPlan.survivingProfileFingerprints.length === 2 ? "" : "s"}. Game files, mods, saves, settings, and diagnostic evidence aren’t part of this cleanup.</p>
          {cleanupPlan.groups.length > 0 ? <div className="cleanup-groups">{cleanupPlan.groups.map((group) => <div key={group.reason}><span>{group.reason.replaceAll("-", " ")}</span><strong>{formatBytes(group.bytes)} · {group.files.toLocaleString()} files</strong></div>)}</div> : null}
          <div className="activation-review__footer">
            <span><ShieldIcon /> The plan is recalculated under the shared operation lock before deletion.</span>
            <button className="button button--danger" type="button" onClick={onCleanCache} disabled={!cleanupPlan.safe || cleanupPlan.files === 0 || cleanupBusy || operationBlocked}>{cleanupBusy ? "Cleaning…" : cleanupPlan.files === 0 ? "Nothing to remove" : `Remove ${cleanupPlan.files.toLocaleString()} files`}</button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
