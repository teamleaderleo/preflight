import { RefreshIcon, ShieldIcon, SparklesIcon } from "../icons";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { resourcePresets, type usePreparation } from "../usePreparation";
import { formatBytes } from "../uiFormat";
import type { CacheCleanupPlan, NoticeTone, OptimizationDomain, OptimizationPreset } from "../types";

export const optimizationPresets: Array<{
  id: OptimizationPreset;
  label: string;
  description: string;
  badge: string;
}> = [
  {
    id: "recommended",
    label: "Recommended",
    description: "All reviewed optimizations for this exact game and mod build.",
    badge: "Default",
  },
  {
    id: "conservative",
    label: "Conservative",
    description: "Startup caches only, with compatible padded textures and original gameplay code.",
    badge: "Compatibility",
  },
  {
    id: "off",
    label: "Off",
    description: "Launch and diagnostics only.",
    badge: "Troubleshoot",
  },
];

/**
 * What each cache category is, in words a player can act on.
 *
 * <p>The engine names these groups for itself -- "acceleration", "evidence" -- and the breakdown
 * used to print those ids raw, which says nothing about what is on disk or whether losing it costs
 * anything. Unknown ids still render, spaced out, rather than disappearing.
 */
const storageGroups: Record<string, { label: string; detail: string }> = {
  acceleration: {
    label: "Prepared game data",
    detail: "Textures, audio, and startup caches Preflight built. Rebuilt by preparing again.",
  },
  evidence: {
    label: "Reports and recordings",
    detail: "Launch timings, benchmarks, and diagnostics kept for the Benchmark page.",
  },
  configuration: {
    label: "Profiles and backups",
    detail: "Saved mod profiles, and the backup taken each time one is switched.",
  },
  application: {
    label: "Preflight itself",
    detail: "The installed copy of preflight.jar.",
  },
};

export function storageGroupLabel(id: string): { label: string; detail: string } {
  return storageGroups[id] ?? { label: id.replaceAll("-", " "), detail: "" };
}

type PreparationState = ReturnType<typeof usePreparation>;

interface PreparationPageProps {
  message: string;
  messageTone: NoticeTone;
  isReady: boolean;
  optimizationPreset: OptimizationPreset;
  disabledOptimizationDomains: OptimizationDomain[];
  preparation: PreparationState;
  cleanupPlan: CacheCleanupPlan | null;
  cleanupBusy: boolean;
  operationBlocked: boolean;
  onOptimizationPresetChange: (preset: OptimizationPreset) => void;
  onOptimizationDomainChange: (domain: OptimizationDomain, enabled: boolean) => void;
  onReviewCleanup: () => void;
  onCleanCache: () => void;
  onDismissCleanup: () => void;
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
  onOptimizationPresetChange,
  onOptimizationDomainChange,
  onReviewCleanup,
  onCleanCache,
  onDismissCleanup,
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
  return (
    <div className="prepare-page">
      <NoticeBanner message={message} tone={messageTone} />

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
            <InfoTip label="About Preflight optimizations">Preflight applies only transformations reviewed for the exact game and mod build. A fingerprint mismatch keeps the original code.</InfoTip>
          </div>
          <p>{optimizationPreset === "off" ? "Starsector will launch without Preflight’s runtime optimizations." : optimizationPreset === "conservative" ? "Compatibility mode is enabled for the next launch." : "Enabled for the next launch."}</p>
        </div>
        <label className="simple-switch">
          <input type="checkbox" aria-label="Use Preflight optimizations" checked={optimizationPreset !== "off"} onChange={(event) => onOptimizationPresetChange(event.target.checked ? "recommended" : "off")} disabled={operationBlocked} />
          <span>{optimizationPreset === "off" ? "Off" : "On"}</span>
        </label>
      </section>

      {!profilePrepared || preparing ? <section className="card prepare-action">
        <div>
          <strong>{preparationCancelling ? "Stopping preparation" : preparing ? preparationPhaseLabel ?? "Preparation is running" : preparationPlanLoading ? "Calculating disk requirement" : preparationPlan?.safeToPrepare ? "There’s room to prepare this profile" : "Preparation needs attention"}</strong>
          <span>{preparing ? `${preparationPercent}% complete · finished artifacts stay reusable` : `${textureStorage === "balanced" ? "Balanced storage selected" : textureStorage === "fastest" ? "Fastest raw storage selected" : "Minimal disk use selected · no prepared textures"} · ${resourcePresets[resourcePreset].label.toLowerCase()} resource use`}</span>
          {preparing ? <div className="preparation-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={preparationPercent}><span style={{ width: `${preparationPercent}%` }} /></div> : null}
        </div>
        <div className="prepare-actions">
          {preparing ? <button className="button button--quiet" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>{preparationCancelling ? "Stopping…" : "Stop safely"}</button> : null}
          <button className="button button--primary" type="button" onClick={() => void prepare(false)} disabled={operationBlocked || cacheRepairing || cacheHealth?.status === "repair-needed" || cacheHealth?.status === "unsafe" || cacheHealth?.status === "unknown" || !isReady || preparationPlanLoading || !preparationPlan?.safeToPrepare}><SparklesIcon />{preparing ? "Preparing…" : preparationPlanLoading ? "Calculating…" : "Prepare current profile"}</button>
        </div>
      </section> : null}

      <section className="card storage-card storage-card--compact">
        <div className="card__heading">
          <div>
            <div className="heading-with-info"><h2>Storage</h2><InfoTip label="About Preflight storage">Prepared data is content-addressed and reused by matching mod profiles. Cleanup is previewed and never includes game files, mods, saves, or settings.</InfoTip></div>
          </div>
          <button className="icon-button icon-button--small" type="button" onClick={() => void refreshCache()} aria-label="Refresh cache storage" disabled={cacheLoading || operationBlocked}><RefreshIcon className={cacheLoading ? "spin" : ""} /></button>
        </div>
        <div className="storage-summary-row">
          <div><strong className="storage-total">{cache ? formatBytes(cache.total.bytes) : "—"}</strong><span className="storage-files">{cache ? `${cache.total.files.toLocaleString()} files` : "Reading cache…"}</span></div>
          {/*
            * These two figures sit a card apart and differ by roughly three times, which read as a
            * contradiction until each said what it was: one is what preparation expects to write,
            * the other is the conservative bound it refuses below. Naming the bound as a bound stops
            * it being read as a standing disk requirement.
            */}
          <div><span>Free space to start</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.requiredFreeBytes) : "—"}</strong><small>Safety bound checked before writing, not the amount used.</small></div>
          <button className="button button--quiet button--compact" type="button" onClick={onReviewCleanup} disabled={cleanupBusy || operationBlocked}>{cleanupBusy ? "Checking…" : "Review cleanup"}</button>
        </div>
        {preparationPlan && !preparationPlan.safeToPrepare ? (
          <div className="storage-refusal">
            <p className="activation-warning">{preparationPlan.refusalReason}</p>
            {textureStorage === "fastest" ? (
              <button className="button button--quiet button--compact" type="button" onClick={() => setTextureStorage("balanced")} disabled={operationBlocked}>Use Balanced storage</button>
            ) : null}
          </div>
        ) : null}
        <details className="storage-breakdown">
          <summary>Storage details</summary>
          <div className="storage-breakdown__grid">
            {(cache?.groups ?? []).map((group) => {
              const { label, detail } = storageGroupLabel(group.id);
              return <div key={group.id}><span>{label}</span><strong>{formatBytes(group.bytes)}</strong>{detail ? <small>{detail}</small> : null}</div>;
            })}
            {(cache?.uncategorizedBytes ?? 0) > 0 ? <div><span>Other Preflight data</span><strong>{formatBytes(cache?.uncategorizedBytes ?? 0)}</strong><small>Under Preflight’s folder but not in a known category.</small></div> : null}
            <div><span>Preparing this profile adds</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.predictedAdditionalBytes) : "—"}</strong><small>Estimated one-off cost for the current mod list, on top of the total above. Preparation refuses to start unless the larger safety bound fits.</small></div>
            <div><span>Free on this disk</span><strong>{preparationPlan ? formatBytes(preparationPlan.usableBytes) : "—"}</strong><small>Space left where Preflight stores its data, right now.</small></div>
          </div>
        </details>
      </section>

      <details className="card settings-disclosure preflight-advanced">
        <summary><span><strong>Advanced controls</strong><small>Compatibility, cache storage, and preparation resources</small></span></summary>
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
                <span><strong>Textures</strong><small>Validated texture pack with a live GPU capability gate</small></span>
              </label>
              <label className="optimization-domain">
                <input type="checkbox" aria-label="Prepared audio" checked={!disabledOptimizationDomains.includes("prepared-audio")} onChange={(event) => onOptimizationDomainChange("prepared-audio", event.target.checked)} disabled={operationBlocked || optimizationPreset === "off"} />
                <span><strong>Audio</strong><small>Decoded audio with exact cache and decoder identities</small></span>
              </label>
            </div>
          </section>
        </div>
      </details>

      {cleanupPlan ? (
        <section className="card cleanup-review" aria-label="Cache cleanup review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Cleanup review</p><h2>{cleanupPlan.files === 0 ? "Everything here is still useful" : `Free ${formatBytes(cleanupPlan.bytes)}?`}</h2></div>
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
