import { useEffect, useState } from "react";

export type ThemePreference = "system" | "light" | "dark";
type ResolvedTheme = Exclude<ThemePreference, "system">;

export const THEME_STORAGE_KEY = "preflight.theme";

function savedPreference(): ThemePreference {
  const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
  return saved === "light" || saved === "dark" || saved === "system" ? saved : "system";
}

function darkPreferenceQuery(): MediaQueryList | null {
  return typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;
}

function systemTheme(query: MediaQueryList | null): ResolvedTheme {
  return query?.matches ? "dark" : "light";
}

export function useTheme() {
  const [preference, setPreferenceState] = useState<ThemePreference>(savedPreference);
  const [resolved, setResolved] = useState<ResolvedTheme>(() => systemTheme(darkPreferenceQuery()));

  useEffect(() => {
    const query = darkPreferenceQuery();
    const update = () => setResolved(preference === "system" ? systemTheme(query) : preference);
    update();
    query?.addEventListener("change", update);
    return () => query?.removeEventListener("change", update);
  }, [preference]);

  useEffect(() => {
    document.documentElement.dataset.theme = resolved;
    document.body.classList.remove("theme-changing");
    void document.body.offsetWidth;
    document.body.classList.add("theme-changing");
    const finished = window.setTimeout(() => document.body.classList.remove("theme-changing"), 180);
    return () => window.clearTimeout(finished);
  }, [resolved]);

  const setPreference = (next: ThemePreference) => {
    window.localStorage.setItem(THEME_STORAGE_KEY, next);
    setPreferenceState(next);
  };

  return { preference, resolved, setPreference };
}
