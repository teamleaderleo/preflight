import { RefreshIcon } from "../icons";
import type { LaunchSettings, LaunchSettingsUpdate } from "../types";
import { shortPath } from "../uiFormat";
import { GameMemorySelect } from "./GameMemorySelect";
import { battleSizePresets, battleSizeUpperBound, uiScaleMaximum } from "../gameSettingOptions";
import { ResolutionSelect, UiScaleSelect } from "./GameSettingSelects";
import { LaunchSettingsApplyBoundary } from "./LaunchSettingsApplyBoundary";

interface GameSettingsPageProps {
  settings: LaunchSettings | null;
  draft: LaunchSettingsUpdate | null;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  saveBlocked: boolean;
  saveBlockReason?: string;
  onChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onRefresh: () => void;
  onSave: (settingsToolsClosed: boolean) => void;
}

export function GameSettingsPage({
  settings,
  draft,
  loading,
  saving,
  dirty,
  saveBlocked,
  saveBlockReason,
  onChange,
  onRefresh,
  onSave,
}: GameSettingsPageProps) {
  if (!draft || !settings) {
    return (
      <div className="launch-page">
        <section className="card launch-loading">
          {loading ? "Reading game settings…" : (
            <>
              <p>Game settings unavailable</p>
              <button className="button button--quiet button--compact" type="button" onClick={onRefresh}>
                Try again
              </button>
            </>
          )}
        </section>
      </div>
    );
  }

  const diagnostics = [
    ...settings.preferences.diagnostics,
    ...settings.limits.diagnostics,
    ...settings.memory.diagnostics,
  ];
  return (
    <div className="launch-page">
      <div className="launch-settings-grid">
        <section className="card launch-settings-card">
          <div className="card__heading">
            <div><h2>Window and rendering</h2></div>
            <button className="icon-button icon-button--small" type="button" onClick={onRefresh} aria-label="Refresh launch settings" disabled={loading || saving}>
              <RefreshIcon className={loading ? "spin" : ""} />
            </button>
          </div>
          <label className="setting-field" htmlFor="launch-resolution">
            <span><strong>Resolution</strong></span>
            <ResolutionSelect id="launch-resolution" label="Resolution" value={draft.resolution} onChange={(resolution) => onChange({ resolution, uiScale: Math.min(draft.uiScale, uiScaleMaximum(settings, resolution)) })} />
          </label>
          <label className="setting-toggle">
            <span><strong>Fullscreen</strong></span>
            <input type="checkbox" aria-label="Fullscreen" checked={draft.fullscreen} onChange={(event) => onChange({ fullscreen: event.target.checked })} />
          </label>
          <label className="setting-toggle">
            <span><strong>Sound</strong></span>
            <input type="checkbox" aria-label="Sound" checked={draft.sound} onChange={(event) => onChange({ sound: event.target.checked })} />
          </label>
          <label className="setting-field" htmlFor="launch-aa">
            <span><strong>Antialiasing</strong><small>Off is recommended at 100%, 200%, or 300% UI scale</small></span>
            <select id="launch-aa" aria-label="Antialiasing" value={draft.antialiasingSamples} onChange={(event) => onChange({ antialiasingSamples: Number(event.target.value) })}>
              {settings.limits.antialiasingSamples.map((samples) => <option value={samples} key={samples}>{samples === 0 ? "Off" : `${samples} samples`}</option>)}
            </select>
          </label>
          <label className="setting-field" htmlFor="launch-scale">
            <span><strong>UI size</strong><small>Limited by the selected resolution</small></span>
            <UiScaleSelect id="launch-scale" label="UI size" settings={settings} resolution={draft.resolution} value={draft.uiScale} onChange={(uiScale) => onChange({ uiScale })} />
          </label>
        </section>

        <section className="card launch-settings-card">
          <div className="card__heading"><div><h2>Battle and memory</h2></div></div>
          <label className="setting-field" htmlFor="launch-battle-size">
            <span><strong>Battle size</strong></span>
            <input id="launch-battle-size" aria-label="Battle size" type="number" min={settings.limits.battleSizeMin ?? 1} max={battleSizeUpperBound(settings, draft.battleSize)} step="10" value={draft.battleSize} onChange={(event) => onChange({ battleSize: Number(event.target.value) })} />
          </label>
          <div className="battle-presets" role="group" aria-label="Battle size presets">
            {battleSizePresets(settings).map((preset) => (
              <button
                type="button"
                key={preset.value}
                className={draft.battleSize === preset.value ? "battle-preset battle-preset--selected" : "battle-preset"}
                aria-pressed={draft.battleSize === preset.value}
                onClick={() => onChange({ battleSize: preset.value })}
              >
                <strong>{preset.value}</strong><small>{preset.label}</small>
              </button>
            ))}
          </div>
          {(settings.limits.battleSizeMax ?? 0) < battleSizeUpperBound(settings, draft.battleSize) ? <p className="setting-help">Preflight writes the same gameplay preference without changing game files. Opening the vanilla battle-size slider can reset a value above {settings.limits.battleSizeMax}.</p> : null}
          <label className="setting-field" htmlFor={settings.memory.editable ? "launch-memory" : undefined}>
            <span><strong>Game memory</strong><small>{settings.memory.source ? shortPath(settings.memory.source) : settings.memory.reason ?? "Managed by the selected launcher"}</small></span>
            <GameMemorySelect id="launch-memory" label="Game memory" memory={settings.memory} value={draft.memoryMiB} onChange={(memoryMiB) => onChange({ memoryMiB })} />
          </label>
        </section>
      </div>

      {diagnostics.length > 0 ? <section className="card launch-diagnostics">{diagnostics.map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}</section> : null}

      {dirty || saving ? (
        <section className="card launch-save">
          <div className="launch-save__backup">
            <span>{settings.backup ? `Previous values: ${shortPath(settings.backup)}` : "The original launcher values are backed up before a change."}</span>
          </div>
          <LaunchSettingsApplyBoundary
            boundary={settings.applyBoundary}
            saving={saving}
            disabled={loading || saveBlocked}
            blockReason={saveBlocked ? saveBlockReason : undefined}
            onApply={onSave}
          />
        </section>
      ) : null}
    </div>
  );
}
