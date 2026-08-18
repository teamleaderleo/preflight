import { useEffect, useState } from "react";
import { HOME_PRESENTATION_STORAGE_KEY } from "./desktopStorage";

export interface HomePresentationPreference {
  showPlaytime: boolean;
}

export const DEFAULT_HOME_PRESENTATION: HomePresentationPreference = Object.freeze({
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
    const showPlaytime = (decoded as Record<string, unknown>).showPlaytime;
    return typeof showPlaytime === "boolean"
      ? { showPlaytime }
      : DEFAULT_HOME_PRESENTATION;
  } catch {
    return DEFAULT_HOME_PRESENTATION;
  }
}

function applyHomePresentation(preference: HomePresentationPreference): void {
  document.documentElement.dataset.homePlaytime = preference.showPlaytime ? "shown" : "hidden";
}

/** Applies persisted display preferences before React paints Home. */
export function initializeHomePresentation(): HomePresentationPreference {
  const preference = readHomePresentation();
  applyHomePresentation(preference);
  return preference;
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

  const setShowPlaytime = (showPlaytime: boolean) => {
    const next = { showPlaytime };
    try {
      window.localStorage.setItem(HOME_PRESENTATION_STORAGE_KEY, JSON.stringify(next));
    } catch {
      // Display preferences remain usable for this session when WebView storage is denied.
    }
    applyHomePresentation(next);
    setPreference(next);
    window.dispatchEvent(new CustomEvent<HomePresentationPreference>(HOME_PRESENTATION_EVENT, { detail: next }));
  };

  return {
    showPlaytime: preference.showPlaytime,
    setShowPlaytime,
  };
}
