import { useCallback, useEffect, useMemo, useState } from "react";
import { getResourceCostInspection } from "./bridge";
import type { ModResourceCost, ResourceCostReport } from "./types";

export type ResourceSortField = "memory" | "vram" | "pcm" | "bytecode" | "disk";

export interface UseResourceCostOptions {
  game: string | null | undefined;
  enabled?: boolean;
}

export function useResourceCost({ game, enabled = true }: UseResourceCostOptions) {
  const [report, setReport] = useState<ResourceCostReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedModId, setSelectedModId] = useState<string | null>(null);
  const [sortField, setSortField] = useState<ResourceSortField>("memory");
  const [searchQuery, setSearchQuery] = useState("");

  const refresh = useCallback(async () => {
    if (!game || !enabled) return;
    setLoading(true);
    setError(null);
    try {
      const data = await getResourceCostInspection(game);
      setReport(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [game, enabled]);

  useEffect(() => {
    if (enabled && game) {
      void refresh();
    }
  }, [enabled, game, refresh]);

  const sortedAndFilteredMods = useMemo(() => {
    if (!report?.mods) return [];
    let list = [...report.mods];

    // Filter by search query
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      list = list.filter(
        (m) =>
          m.id.toLowerCase().includes(q) ||
          m.name.toLowerCase().includes(q) ||
          m.version.toLowerCase().includes(q),
      );
    }

    // Sort by field descending
    list.sort((a, b) => {
      switch (sortField) {
        case "vram":
          return b.texture.residentBytes - a.texture.residentBytes;
        case "pcm":
          return b.audio.effectPcmBytes - a.audio.effectPcmBytes;
        case "bytecode":
          return b.bytecode.uncompressedBytecodeBytes - a.bytecode.uncompressedBytecodeBytes;
        case "disk":
          return b.totalDiskBytes - a.totalDiskBytes;
        case "memory":
        default:
          return b.estimatedMemoryBytes - a.estimatedMemoryBytes;
      }
    });

    return list;
  }, [report, searchQuery, sortField]);

  const selectedMod = useMemo<ModResourceCost | null>(() => {
    if (!selectedModId || !report?.mods) return null;
    return report.mods.find((m) => m.id === selectedModId) ?? null;
  }, [report, selectedModId]);

  return {
    report,
    loading,
    error,
    selectedModId,
    selectedMod,
    sortField,
    searchQuery,
    sortedAndFilteredMods,
    setSortField,
    setSearchQuery,
    setSelectedModId,
    refresh,
  };
}
