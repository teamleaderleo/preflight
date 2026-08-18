import { useCallback, useEffect, useRef, useState } from "react";
import { getLaunchSettings, updateLaunchSettings } from "./bridge";
import type { Announce, LaunchSettings, LaunchSettingsUpdate } from "./types";
import { errorMessage } from "./uiFormat";

function draftFromSettings(settings: LaunchSettings): LaunchSettingsUpdate {
  return {
    resolution: settings.preferences.resolution ?? settings.settings?.resolution ?? "1280x720",
    fullscreen: settings.preferences.fullscreen,
    sound: settings.preferences.sound,
    antialiasingSamples: settings.preferences.antialiasingSamples ?? 0,
    uiScale: settings.preferences.uiScale ?? 1,
    battleSize: settings.preferences.battleSize ?? settings.limits.battleSizeDefault ?? 400,
    memoryMiB: settings.memory.editable ? settings.memory.maxHeapMiB : null,
  };
}

export function launcherSettingsChanged(settings: LaunchSettings | null, draft: LaunchSettingsUpdate | null): boolean {
  if (!settings || !draft) return false;
  const saved = draftFromSettings(settings);
  return draft.resolution !== saved.resolution
    || draft.fullscreen !== saved.fullscreen
    || draft.sound !== saved.sound
    || draft.antialiasingSamples !== saved.antialiasingSamples
    || draft.uiScale !== saved.uiScale
    || draft.battleSize !== saved.battleSize
    || draft.memoryMiB !== saved.memoryMiB;
}

export function useLauncherSettings(
  game: string | undefined,
  active: boolean,
  announce: Announce,
) {
  const [settings, setSettings] = useState<LaunchSettings | null>(null);
  const [draft, setDraft] = useState<LaunchSettingsUpdate | null>(null);
  const [loadedGame, setLoadedGame] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const request = useRef(0);
  const draftRevision = useRef(0);
  const savingRef = useRef(false);
  const currentGame = useRef(game);
  currentGame.current = game;

  const refresh = useCallback(async () => {
    const expectedGame = game;
    const currentRequest = ++request.current;
    savingRef.current = false;
    setSaving(false);
    if (!expectedGame) {
      setSettings(null);
      setDraft(null);
      setLoadedGame(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const result = await getLaunchSettings(expectedGame);
      if (currentRequest !== request.current || currentGame.current !== expectedGame) return;
      setSettings(result);
      setDraft(draftFromSettings(result));
      setLoadedGame(expectedGame);
      draftRevision.current += 1;
    } catch (error) {
      if (currentRequest === request.current && currentGame.current === expectedGame) announce(errorMessage(error), "error");
    } finally {
      if (currentRequest === request.current) setLoading(false);
    }
  }, [announce, game]);

  useEffect(() => {
    if (active && loadedGame !== game) void refresh();
  }, [active, game, loadedGame, refresh]);

  const changeDraft = useCallback((change: Partial<LaunchSettingsUpdate>) => {
    draftRevision.current += 1;
    setDraft((current) => current ? { ...current, ...change } : current);
  }, []);

  const save = async (settingsToolsClosed: boolean) => {
    const expectedGame = game;
    const submittedDraft = draft;
    if (!expectedGame || !submittedDraft || savingRef.current) return false;
    if (!settingsToolsClosed) {
      announce(
        settings?.applyBoundary.instruction
          ?? "Close Starsector, its launcher, settings editors, and mod managers before Apply.",
        "warning",
      );
      return false;
    }
    const currentRequest = ++request.current;
    const submittedRevision = draftRevision.current;
    savingRef.current = true;
    setLoading(false);
    setSaving(true);
    announce("Applying global game settings…");
    try {
      const result = await updateLaunchSettings(expectedGame, submittedDraft, true);
      if (currentRequest !== request.current || currentGame.current !== expectedGame) return;
      setSettings(result);
      setLoadedGame(expectedGame);
      if (draftRevision.current === submittedRevision) setDraft(draftFromSettings(result));
      announce("Global game settings applied and verified. Vanilla and Preflight launches will use the same values.", "success");
      return true;
    } catch (error) {
      if (currentRequest === request.current && currentGame.current === expectedGame) announce(errorMessage(error), "error");
      return false;
    } finally {
      if (currentRequest === request.current) {
        savingRef.current = false;
        setSaving(false);
      }
    }
  };

  const currentSettings = loadedGame === game ? settings : null;
  const currentDraft = loadedGame === game ? draft : null;
  return {
    settings: currentSettings,
    draft: currentDraft,
    loading,
    saving,
    dirty: launcherSettingsChanged(currentSettings, currentDraft),
    changeDraft,
    refresh,
    save,
  };
}
