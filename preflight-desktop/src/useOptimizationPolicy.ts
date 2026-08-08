import { useCallback, useEffect, useState } from "react";
import type { OptimizationDomain, OptimizationPreset } from "./types";

export const OPTIMIZATION_PRESET_STORAGE_KEY = "preflight.optimizationPreset";
export const DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY = "preflight.disabledOptimizationDomains";

function savedOptimizationPreset(): OptimizationPreset {
  try {
    const saved = window.localStorage.getItem(OPTIMIZATION_PRESET_STORAGE_KEY);
    if (saved === "recommended" || saved === "conservative" || saved === "off") return saved;
  } catch {
    // A locked-down webview may deny storage; the safe product default still applies.
  }
  return "recommended";
}

function savedDisabledOptimizationDomains(): OptimizationDomain[] {
  try {
    const saved: unknown = JSON.parse(
      window.localStorage.getItem(DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY) ?? "[]",
    );
    if (!Array.isArray(saved)) return [];
    const domains = saved.filter(
      (value): value is OptimizationDomain => value === "prepared-textures" || value === "prepared-audio",
    );
    return [...new Set(domains)];
  } catch {
    return [];
  }
}

export function useOptimizationPolicy() {
  const [optimizationPreset, setOptimizationPreset] = useState<OptimizationPreset>(savedOptimizationPreset);
  const [disabledOptimizationDomains, setDisabledOptimizationDomains] = useState<OptimizationDomain[]>(
    savedDisabledOptimizationDomains,
  );

  useEffect(() => {
    try {
      window.localStorage.setItem(OPTIMIZATION_PRESET_STORAGE_KEY, optimizationPreset);
    } catch {
      // Selection remains valid for this session when persistent storage is unavailable.
    }
  }, [optimizationPreset]);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
        JSON.stringify(disabledOptimizationDomains),
      );
    } catch {
      // The validated selection remains active for this session.
    }
  }, [disabledOptimizationDomains]);

  const setOptimizationDomainEnabled = useCallback((domain: OptimizationDomain, enabled: boolean) => {
    setDisabledOptimizationDomains((current) => enabled
      ? current.filter((candidate) => candidate !== domain)
      : current.includes(domain) ? current : [...current, domain]);
  }, []);

  return {
    optimizationPreset,
    disabledOptimizationDomains,
    setOptimizationPreset,
    setOptimizationDomainEnabled,
  };
}
