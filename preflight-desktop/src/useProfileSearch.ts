import { useEffect, useState } from "react";
import { PROFILE_SEARCH_STORAGE_KEY } from "./desktopStorage";

const MAX_PROFILE_SEARCH_CHARACTERS = 100;
const PROFILE_SEARCH_EVENT = "preflight:profile-search";

export function validateProfileSearch(value: unknown): string {
  return typeof value === "string" && value.length <= MAX_PROFILE_SEARCH_CHARACTERS ? value : "";
}

export function readProfileSearch(
  storage?: Pick<Storage, "getItem">,
): string {
  try {
    return validateProfileSearch((storage ?? window.localStorage).getItem(PROFILE_SEARCH_STORAGE_KEY));
  } catch {
    return "";
  }
}

function persistProfileSearch(
  value: string,
  storage?: Pick<Storage, "setItem" | "removeItem">,
): void {
  try {
    if (value) (storage ?? window.localStorage).setItem(PROFILE_SEARCH_STORAGE_KEY, value);
    else (storage ?? window.localStorage).removeItem(PROFILE_SEARCH_STORAGE_KEY);
  } catch {
    // Search remains useful for this session when WebView storage is unavailable.
  }
}

function broadcastProfileSearch(value: string): void {
  window.dispatchEvent(new CustomEvent<string>(PROFILE_SEARCH_EVENT, { detail: value }));
}

/** Resets mounted profile-list consumers after all-data cleanup without recreating storage. */
export function resetProfileSearch(): void {
  broadcastProfileSearch("");
}

export function filterProfileNames<T extends { name: string }>(profiles: readonly T[], query: string): T[] {
  // Search is presentation-only, so keep its result independent of the machine/UI locale. Profile
  // identity and any future canonical-name policy remain owned by the profile model, not this filter.
  const needle = query.trim().toLowerCase();
  if (!needle) return [...profiles];
  return profiles.filter((profile) => profile.name.toLowerCase().includes(needle));
}

export function useProfileSearch() {
  const [query, setQueryState] = useState(readProfileSearch);

  useEffect(() => {
    const syncLocal = (event: Event) => {
      setQueryState(validateProfileSearch((event as CustomEvent<unknown>).detail));
    };
    const syncStorage = (event: StorageEvent) => {
      if (event.key === PROFILE_SEARCH_STORAGE_KEY) {
        setQueryState(validateProfileSearch(event.newValue));
      }
    };
    window.addEventListener(PROFILE_SEARCH_EVENT, syncLocal);
    window.addEventListener("storage", syncStorage);
    return () => {
      window.removeEventListener(PROFILE_SEARCH_EVENT, syncLocal);
      window.removeEventListener("storage", syncStorage);
    };
  }, []);

  const setQuery = (value: string) => {
    const next = validateProfileSearch(value);
    setQueryState(next);
    persistProfileSearch(next);
    broadcastProfileSearch(next);
  };

  return { query, setQuery };
}
