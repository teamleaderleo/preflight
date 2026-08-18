import { ArrowIcon } from "../icons";
import type { LaunchSettings, LaunchSettingsUpdate } from "../types";
import { GameMemorySelect } from "./GameMemorySelect";
import { battleSizeUpperBound, uiScaleMaximum } from "../gameSettingOptions";
import { ResolutionSelect, UiScaleSelect } from "./GameSettingSelects";
import { LaunchSettingsApplyBoundary } from "./LaunchSettingsApplyBoundary";

interface QuickGameSettingsProps {
  settings: LaunchSettings;
  draft: LaunchSettingsUpdate;
  dirty: boolean;
  saving: boolean;
  editingDisabled: boolean;
  saveBlocked: boolean;
  saveBlockReason?: string;
  onChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onOpenAll: () => void;
  onSave: (settingsToolsClosed: boolean) => void;
}

export function QuickGameSettings({
  settings,
  draft,
  dirty,
  saving,
  editingDisabled,
  saveBlocked,
  saveBlockReason,
  onChange,
  onOpenAll,
  onSave,
}: QuickGameSettingsProps) {
  return (
    <div className={`quick-settings ${dirty || saving ? "quick-settings--dirty" : ""}`} aria-label="Common game settings">
      <div className="quick-settings__grid">
        <div className="quick-control quick-control--wide quick-control--resolution">
          <label htmlFor="home-resolution">Resolution</label>
          <div className="quick-resolution-row">
            <ResolutionSelect id="home-resolution" label="Home resolution" value={draft.resolution} disabled={editingDisabled} onChange={(resolution) => onChange({ resolution, uiScale: Math.min(draft.uiScale, uiScaleMaximum(settings, resolution)) })} />
            <button className="text-button" type="button" onClick={onOpenAll} disabled={editingDisabled}>All settings <ArrowIcon /></button>
          </div>
        </div>
        <label className="quick-control quick-control--battle-size" htmlFor="home-battle-size">
          <span>Battle size</span>
          <input id="home-battle-size" aria-label="Home battle size" type="number" min={settings.limits.battleSizeMin ?? 1} max={battleSizeUpperBound(settings, draft.battleSize)} step="10" value={draft.battleSize} disabled={editingDisabled} onChange={(event) => onChange({ battleSize: Number(event.target.value) })} />
        </label>
        <label className={`quick-control quick-control--memory ${settings.memory.editable ? "" : "quick-control--read-only"}`} htmlFor={settings.memory.editable ? "home-memory" : undefined}>
          <span>RAM</span>
          <GameMemorySelect id="home-memory" label="Home game memory" memory={settings.memory} value={draft.memoryMiB} disabled={editingDisabled} onChange={(memoryMiB) => onChange({ memoryMiB })} />
        </label>
        <label className="quick-control quick-control--antialiasing" htmlFor="home-aa">
          <span>Antialiasing</span>
          <select id="home-aa" aria-label="Home antialiasing" value={draft.antialiasingSamples} disabled={editingDisabled} onChange={(event) => onChange({ antialiasingSamples: Number(event.target.value) })}>
            {settings.limits.antialiasingSamples.map((samples) => <option value={samples} key={samples}>{samples === 0 ? "Off" : `${samples}×`}</option>)}
          </select>
        </label>
        <label className="quick-control quick-control--ui-scale" htmlFor="home-ui-scale">
          <span>UI size</span>
          <UiScaleSelect id="home-ui-scale" label="Home UI size" settings={settings} resolution={draft.resolution} value={draft.uiScale} disabled={editingDisabled} onChange={(uiScale) => onChange({ uiScale })} />
        </label>
      </div>
      <div className="quick-settings__toggles">
        <label><input type="checkbox" aria-label="Home fullscreen" checked={draft.fullscreen} disabled={editingDisabled} onChange={(event) => onChange({ fullscreen: event.target.checked })} /><span>Fullscreen</span></label>
        <label><input type="checkbox" aria-label="Home sound" checked={draft.sound} disabled={editingDisabled} onChange={(event) => onChange({ sound: event.target.checked })} /><span>Sound</span></label>
      </div>
      {(settings.limits.battleSizeMax ?? 0) < battleSizeUpperBound(settings, draft.battleSize) && draft.battleSize > (settings.limits.battleSizeMax ?? 0) ? <p className="quick-settings__hint">The vanilla settings slider ends at {settings.limits.battleSizeMax}; opening it can reset this extended value.</p> : null}
      {dirty || saving ? (
        <LaunchSettingsApplyBoundary
          boundary={settings.applyBoundary}
          saving={saving}
          disabled={saveBlocked}
          blockReason={saveBlocked ? saveBlockReason : undefined}
          className="quick-settings__apply"
          onApply={onSave}
        />
      ) : null}
    </div>
  );
}
