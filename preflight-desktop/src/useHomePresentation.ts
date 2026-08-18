import { useEffect, useState } from "react";
import { HOME_PRESENTATION_STORAGE_KEY } from "./desktopStorage";

export type HomePresentationMode = "hangar" | "compact";

export interface HomePresentationPreference {
  mode: HomePresentationMode;
  showPlaytime: boolean;
}

export const DEFAULT_HOME_PRESENTATION: HomePresentationPreference = Object.freeze({
  mode: "hangar",
  showPlaytime: true,
});

const HOME_PRESENTATION_EVENT = "preflight-home-presentation-change";

export function readHomePresentation(
  storage: Pick<Storage, "getItem"> = window.localStorage,
): HomePresentationPreference {
  try {
    const raw = storage.getItem(HOME_PRESENTATION_STORAGE_KEY);
    if (!raw) return DEFAULT_HOME_PRESENTATION;
    const decoded: unknown = JSON.parse(raw);
    if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) {
      return DEFAULT_HOME_PRESENTATION;
    }
    const candidate = decoded as Record<string, unknown>;
    return {
      // Preferences written by the earlier playtime-only slice have no mode and remain Hangar.
      mode: candidate.mode === "compact" ? "compact" : "hangar",
      showPlaytime: typeof candidate.showPlaytime === "boolean" ? candidate.showPlaytime : true,
    };
  } catch {
    return DEFAULT_HOME_PRESENTATION;
  }
}

function applyHomePresentation(preference: HomePresentationPreference): void {
  document.documentElement.dataset.homeMode = preference.mode;
  document.documentElement.dataset.homePlaytime = preference.showPlaytime ? "shown" : "hidden";
}

function publishHomePresentation(preference: HomePresentationPreference): void {
  applyHomePresentation(preference);
  window.dispatchEvent(new CustomEvent<HomePresentationPreference>(HOME_PRESENTATION_EVENT, { detail: preference }));
}

/** Applies persisted display preferences before React paints Home. */
export function initializeHomePresentation(): HomePresentationPreference {
  const preference = readHomePresentation();
  applyHomePresentation(preference);
  return preference;
}

/**
 * Retires the live renderer preference after all-data cleanup without recreating the removed key.
 */
export function resetHomePresentation(): void {
  publishHomePresentation(DEFAULT_HOME_PRESENTATION);
}

function persistHomePresentation(preference: HomePresentationPreference): void {
  try {
    window.localStorage.setItem(HOME_PRESENTATION_STORAGE_KEY, JSON.stringify(preference));
  } catch {
    // Display preferences remain usable for this session when WebView storage is denied.
  }
}

export function useHomePresentation() {
  const [preference, setPreference] = useState(readHomePresentation);

  useEffect(() => {
    const sync = (event: Event) => {
      const detail = (event as CustomEvent<HomePresentationPreference>).detail;
      const next = detail ?? readHomePresentation();
      applyHomePresentation(next);
      setPreference(next);
    };
    const syncStorage = (event: StorageEvent) => {
      if (event.key === HOME_PRESENTATION_STORAGE_KEY) {
        const next = readHomePresentation();
        applyHomePresentation(next);
        setPreference(next);
      }
    };
    window.addEventListener(HOME_PRESENTATION_EVENT, sync);
    window.addEventListener("storage", syncStorage);
    return () => {
      window.removeEventListener(HOME_PRESENTATION_EVENT, sync);
      window.removeEventListener("storage", syncStorage);
    };
  }, []);

  const commit = (next: HomePresentationPreference) => {
    persistHomePresentation(next);
    publishHomePresentation(next);
    setPreference(next);
  };

  const setMode = (mode: HomePresentationMode) => commit({ ...preference, mode });
  const setShowPlaytime = (showPlaytime: boolean) => commit({ ...preference, showPlaytime });

  return {
    mode: preference.mode,
    showPlaytime: preference.showPlaytime,
    setMode,
    setShowPlaytime,
  };
}
