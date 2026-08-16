import { useEffect, useRef, useState } from "react";
import { PALETTE_STORAGE_KEY, THEME_STORAGE_KEY } from "./desktopStorage";

export { PALETTE_STORAGE_KEY, THEME_STORAGE_KEY } from "./desktopStorage";

export type ThemePreference = "system" | "light" | "dark";
export type PalettePreference = "blueprint" | "hangar";
type ResolvedTheme = Exclude<ThemePreference, "system">;

function savedPreference(): ThemePreference {
  const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
  return saved === "light" || saved === "dark" || saved === "system" ? saved : "system";
}

function savedPalette(): PalettePreference {
  return window.localStorage.getItem(PALETTE_STORAGE_KEY) === "hangar" ? "hangar" : "blueprint";
}

function darkPreferenceQuery(): MediaQueryList | null {
  return typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;
}

function systemTheme(query: MediaQueryList | null): ResolvedTheme {
  return query?.matches ? "dark" : "light";
}

/** What a preference means right now. The inline script in index.html answers this the same way. */
function resolveTheme(preference: ThemePreference): ResolvedTheme {
  return preference === "system" ? systemTheme(darkPreferenceQuery()) : preference;
}

export function useTheme() {
  const [preference, setPreferenceState] = useState<ThemePreference>(savedPreference);
  const [palette, setPaletteState] = useState<PalettePreference>(savedPalette);
  // Seeded from the saved preference, not from the system. Reading the system here made a saved
  // "light" on a dark desktop render dark and then correct itself, which is a second flash on top
  // of the one the pre-paint script exists to remove.
  const [resolved, setResolved] = useState<ResolvedTheme>(() => resolveTheme(savedPreference()));
  const settled = useRef(false);

  useEffect(() => {
    const query = darkPreferenceQuery();
    const update = () => setResolved(resolveTheme(preference));
    update();
    query?.addEventListener("change", update);
    return () => query?.removeEventListener("change", update);
  }, [preference]);

  useEffect(() => {
    document.documentElement.dataset.theme = resolved;
    // The crossfade belongs to a theme the user changed, not to the one the app opened with.
    // Animating the first application turns an instant correct paint into a visible flash.
    if (!settled.current) {
      settled.current = true;
      return;
    }
    document.body.classList.remove("theme-changing");
    void document.body.offsetWidth;
    document.body.classList.add("theme-changing");
    const finished = window.setTimeout(() => document.body.classList.remove("theme-changing"), 180);
    return () => window.clearTimeout(finished);
  }, [resolved]);

  useEffect(() => {
    document.documentElement.dataset.palette = palette;
  }, [palette]);

  const setPreference = (next: ThemePreference) => {
    window.localStorage.setItem(THEME_STORAGE_KEY, next);
    setPreferenceState(next);
  };

  const setPalette = (next: PalettePreference) => {
    window.localStorage.setItem(PALETTE_STORAGE_KEY, next);
    setPaletteState(next);
  };

  return { preference, resolved, palette, setPreference, setPalette };
}
