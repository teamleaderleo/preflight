import { CheckIcon, RefreshIcon } from "../icons";
import type { LaunchSettings, LaunchSettingsUpdate } from "../types";
import { maximumUiScale, shortPath } from "../uiFormat";
import { GameMemorySelect } from "./GameMemorySelect";

interface GameSettingsPageProps {
  settings: LaunchSettings | null;
  draft: LaunchSettingsUpdate | null;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  disabled: boolean;
  onChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onRefresh: () => void;
  onSave: () => void;
}

export function GameSettingsPage({
  settings,
  draft,
  loading,
  saving,
  dirty,
  disabled,
  onChange,
  onRefresh,
  onSave,
}: GameSettingsPageProps) {
  if (!draft || !settings) {
    return <div className="launch-page"><section className="card launch-loading">{loading ? "Reading game settings…" : "Game settings unavailable"}</section></div>;
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
            <div><p className="eyebrow">Display</p><h2>Window and rendering</h2></div>
            <button className="icon-button icon-button--small" type="button" onClick={onRefresh} aria-label="Refresh launch settings" disabled={loading || saving}>
              <RefreshIcon className={loading ? "spin" : ""} />
            </button>
          </div>
          <label className="setting-field" htmlFor="launch-resolution">
            <span><strong>Resolution</strong></span>
            <input id="launch-resolution" aria-label="Resolution" value={draft.resolution} onChange={(event) => onChange({ resolution: event.target.value })} inputMode="text" spellCheck={false} />
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
          <label className="setting-slider" htmlFor="launch-scale">
            <span><strong>UI scaling</strong><b>{Math.round(draft.uiScale * 100)}%</b></span>
            <input id="launch-scale" aria-label="UI scaling" type="range" min={settings.limits.uiScaleMin} max={maximumUiScale(draft.resolution) ?? settings.limits.uiScaleMax} step={settings.limits.uiScaleStep} value={draft.uiScale} onChange={(event) => onChange({ uiScale: Number(event.target.value) })} />
          </label>
        </section>

        <section className="card launch-settings-card">
          <div className="card__heading"><div><p className="eyebrow">Simulation</p><h2>Battle and memory</h2></div></div>
          <label className="setting-slider" htmlFor="launch-battle-size">
            <span><strong>Deployment-point budget</strong><b>{draft.battleSize}</b></span>
            <input id="launch-battle-size" aria-label="Deployment-point budget" type="range" min={settings.limits.battleSizeMin ?? 1} max={settings.limits.battleSizeMax ?? Math.max(draft.battleSize, 400)} step="10" value={draft.battleSize} onChange={(event) => onChange({ battleSize: Number(event.target.value) })} />
          </label>
          <div className="battle-bounds">
            <span>Minimum {settings.limits.battleSizeMin ?? "unknown"}</span>
            <span>Default {settings.limits.battleSizeDefault ?? "unknown"}</span>
            <span>Maximum {settings.limits.battleSizeMax ?? "unknown"}</span>
          </div>
          <label className="setting-field" htmlFor={settings.memory.editable ? "launch-memory" : undefined}>
            <span><strong>Game memory</strong><small>{settings.memory.source ? shortPath(settings.memory.source) : settings.memory.reason ?? "Managed by the selected launcher"}</small></span>
            <GameMemorySelect id="launch-memory" label="Game memory" memory={settings.memory} value={draft.memoryMiB} onChange={(memoryMiB) => onChange({ memoryMiB })} />
          </label>
        </section>
      </div>

      {diagnostics.length > 0 ? <section className="card launch-diagnostics">{diagnostics.map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}</section> : null}

      <section className="card launch-save">
        <div><span>{settings.backup ? `Previous values: ${shortPath(settings.backup)}` : "The original launcher values are backed up before a change."}</span></div>
        <button className={`button ${dirty ? "button--primary" : "button--quiet"}`} type="button" onClick={onSave} disabled={!dirty || loading || saving || disabled}>
          <CheckIcon />{saving ? "Saving…" : dirty ? "Save changes" : "Settings applied"}
        </button>
      </section>
    </div>
  );
}
