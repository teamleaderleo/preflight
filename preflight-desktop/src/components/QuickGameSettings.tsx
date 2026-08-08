import { ArrowIcon, CheckIcon } from "../icons";
import type { LaunchSettings, LaunchSettingsUpdate } from "../types";
import { GameMemorySelect } from "./GameMemorySelect";

interface QuickGameSettingsProps {
  settings: LaunchSettings;
  draft: LaunchSettingsUpdate;
  dirty: boolean;
  saving: boolean;
  disabled: boolean;
  onChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onOpenAll: () => void;
  onSave: () => void;
}

export function QuickGameSettings({
  settings,
  draft,
  dirty,
  saving,
  disabled,
  onChange,
  onOpenAll,
  onSave,
}: QuickGameSettingsProps) {
  return (
    <div className="quick-settings" aria-label="Common game settings">
      <div className="quick-settings__heading">
        <strong>Game setup</strong>
        <button className="text-button" type="button" onClick={onOpenAll}>All settings <ArrowIcon /></button>
      </div>
      <div className="quick-settings__grid">
        <label className="quick-control" htmlFor="home-resolution">
          <span>Resolution</span>
          <input id="home-resolution" aria-label="Home resolution" value={draft.resolution} onChange={(event) => onChange({ resolution: event.target.value })} inputMode="text" spellCheck={false} />
        </label>
        <label className="quick-control" htmlFor="home-battle-size">
          <span>Battle size</span>
          <input id="home-battle-size" aria-label="Home battle size" type="number" min={settings.limits.battleSizeMin ?? 1} max={settings.limits.battleSizeMax ?? Math.max(draft.battleSize, 400)} step="10" value={draft.battleSize} onChange={(event) => onChange({ battleSize: Number(event.target.value) })} />
        </label>
        <label className={`quick-control ${settings.memory.editable ? "" : "quick-control--read-only"}`} htmlFor={settings.memory.editable ? "home-memory" : undefined}>
          <span>RAM</span>
          <GameMemorySelect id="home-memory" label="Home game memory" memory={settings.memory} value={draft.memoryMiB} onChange={(memoryMiB) => onChange({ memoryMiB })} />
        </label>
      </div>
      <div className="quick-settings__toggles">
        <label><input type="checkbox" aria-label="Home fullscreen" checked={draft.fullscreen} onChange={(event) => onChange({ fullscreen: event.target.checked })} /><span>Fullscreen</span></label>
        <label><input type="checkbox" aria-label="Home sound" checked={draft.sound} onChange={(event) => onChange({ sound: event.target.checked })} /><span>Sound</span></label>
      </div>
      <button className={`button ${dirty ? "button--primary" : "button--quiet"} quick-settings__save`} type="button" onClick={onSave} disabled={!dirty || saving || disabled}>
        <CheckIcon />{saving ? "Saving…" : dirty ? "Apply changes" : "Settings applied"}
      </button>
    </div>
  );
}
