import React, { useState, useMemo } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Data Contracts for Feature 10 (Resource Inspector Desktop UI)
export interface ResourceCostSummary {
  enabledModCount: number;
  totalDiskBytes: number;
  totalEstimatedMemoryBytes: number;
  textureVram: {
    textureCount: number;
    diskBytes: number;
    decodedBaseBytes: number;
    residentGpuBytes: number;
    paddingWasteBytes: number;
    mipChainUpperBoundBytes: number;
  };
  audioPcm: {
    soundCount: number;
    diskBytes: number;
    effectPcmBytes: number;
    effectCount: number;
    musicDiskBytes: number;
    musicCount: number;
    unreferencedCount: number;
    unreferencedDiskBytes: number;
  };
  bytecode: {
    jarCount: number;
    diskBytes: number;
    uncompressedBytecodeBytes: number;
    classCount: number;
    duplicateClasses: number;
  };
  preparedData: {
    preparedTextureBytes: number;
    preparedAudioBytes: number;
    janinoBytecodeBytes: number;
    specCacheBytes: number;
  };
}

export interface ModTextureEntry {
  logicalPath: string;
  width: number;
  height: number;
  channels: number;
  diskBytes: number;
  residentBytes: number;
  paddingWasteBytes: number;
  overridden: boolean;
}

export interface ModAudioEntry {
  logicalPath: string;
  kind: "effect" | "music" | "unreferenced";
  channels: number;
  sampleRate: number;
  durationSeconds: number;
  diskBytes: number;
  pcmBytes: number;
}

export interface ModBytecodeEntry {
  relativePath: string;
  diskBytes: number;
  uncompressedBytecodeBytes: number;
  classCount: number;
  duplicateClasses: string[];
}

export interface ModResourceCost {
  id: string;
  name: string;
  version: string;
  order: number;
  totalDiskBytes: number;
  estimatedMemoryBytes: number;
  texture: {
    count: number;
    diskBytes: number;
    decodedBytes: number;
    residentBytes: number;
    paddingWasteBytes: number;
    unmeasuredCount: number;
  };
  audio: {
    count: number;
    diskBytes: number;
    effectPcmBytes: number;
    musicBytes: number;
    unreferencedBytes: number;
  };
  bytecode: {
    jarCount: number;
    diskBytes: number;
    uncompressedBytecodeBytes: number;
    classCount: number;
    duplicateClassCount: number;
  };
  preparedData: {
    textureCacheBytes: number;
    audioCacheBytes: number;
    specCacheBytes: number;
  };
  details?: {
    textures: ModTextureEntry[];
    audio: ModAudioEntry[];
    bytecode: ModBytecodeEntry[];
  };
}

export interface ResourceCostReport {
  format: "starsector-preflight-resource-cost-v1";
  generatedAt: string;
  installRoot: string;
  profileFingerprint: string;
  scanDurationMs: number;
  summary: ResourceCostSummary;
  mods: ModResourceCost[];
}

// UI Components for Feature 10
export function formatBytesHuman(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function ResourceScoreboard({ summary }: { summary: ResourceCostSummary }) {
  return (
    <section className="card resource-scoreboard" aria-label="Resource Cost Telemetry Scoreboard">
      <h2 className="orbitron-title">RESOURCE COST TELEMETRY SCOREBOARD</h2>
      <div className="scoreboard-grid">
        <div className="scoreboard-card" data-testid="scoreboard-vram">
          <span className="scoreboard-label">TOTAL RESIDENT VRAM (GPU)</span>
          <strong className="scoreboard-value mono">{formatBytesHuman(summary.textureVram.residentGpuBytes)}</strong>
          <span className="scoreboard-sub">
            POT Padding Waste: {formatBytesHuman(summary.textureVram.paddingWasteBytes)} (
            {summary.textureVram.residentGpuBytes > 0
              ? `${((summary.textureVram.paddingWasteBytes / summary.textureVram.residentGpuBytes) * 100).toFixed(0)}%`
              : "0%"}
            )
          </span>
        </div>

        <div className="scoreboard-card" data-testid="scoreboard-audio">
          <span className="scoreboard-label">EFFECT AUDIO PCM (RAM)</span>
          <strong className="scoreboard-value mono">{formatBytesHuman(summary.audioPcm.effectPcmBytes)}</strong>
          <span className="scoreboard-sub">
            {summary.audioPcm.effectCount} effects · {formatBytesHuman(summary.audioPcm.unreferencedDiskBytes)} unref waste
          </span>
        </div>

        <div className="scoreboard-card" data-testid="scoreboard-bytecode">
          <span className="scoreboard-label">BYTECODE & CLASSES</span>
          <strong className="scoreboard-value mono">{formatBytesHuman(summary.bytecode.uncompressedBytecodeBytes)}</strong>
          <span className="scoreboard-sub">
            {summary.bytecode.classCount.toLocaleString()} classes · {summary.bytecode.duplicateClasses} duplicates
          </span>
        </div>

        <div className="scoreboard-card" data-testid="scoreboard-prepared">
          <span className="scoreboard-label">PREPARED DATA CACHE</span>
          <strong className="scoreboard-value mono">
            {formatBytesHuman(
              summary.preparedData.preparedTextureBytes +
                summary.preparedData.preparedAudioBytes +
                summary.preparedData.janinoBytecodeBytes +
                summary.preparedData.specCacheBytes
            )}
          </strong>
          <span className="scoreboard-sub">
            Textures: {formatBytesHuman(summary.preparedData.preparedTextureBytes)} · Audio: {formatBytesHuman(summary.preparedData.preparedAudioBytes)}
          </span>
        </div>
      </div>
    </section>
  );
}

export function ResourceBreakdownChart({ mods, totalMemory }: { mods: ModResourceCost[]; totalMemory: number }) {
  return (
    <section className="card resource-chart-card" aria-label="Stacked Memory Breakdown Chart">
      <h3>Memory Allocation Breakdown by Mod</h3>
      <div className="stacked-bar-container" data-testid="stacked-bar">
        {mods.slice(0, 8).map((mod) => {
          const percent = totalMemory > 0 ? (mod.estimatedMemoryBytes / totalMemory) * 100 : 0;
          if (percent <= 0) return null;
          return (
            <div
              key={mod.id}
              className="stacked-bar-segment"
              style={{ width: `${percent.toFixed(1)}%` }}
              title={`${mod.name}: ${formatBytesHuman(mod.estimatedMemoryBytes)} (${percent.toFixed(1)}%)`}
              data-testid={`bar-segment-${mod.id}`}
            >
              <span className="segment-label">{mod.id}</span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

export function ModCostTable({
  mods,
  onSelectMod,
}: {
  mods: ModResourceCost[];
  onSelectMod: (mod: ModResourceCost) => void;
}) {
  const [sortField, setSortField] = useState<"vram" | "waste" | "audio" | "bytecode" | "disk" | "memory">("vram");
  const [sortAsc, setSortAsc] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [highWasteOnly, setHighWasteOnly] = useState(false);

  const handleSort = (field: typeof sortField) => {
    if (sortField === field) {
      setSortAsc(!sortAsc);
    } else {
      setSortField(field);
      setSortAsc(false);
    }
  };

  const filteredMods = useMemo(() => {
    return mods.filter((m) => {
      const matchesSearch =
        m.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        m.id.toLowerCase().includes(searchQuery.toLowerCase());
      if (!matchesSearch) return false;
      if (highWasteOnly) {
        const wasteRatio = m.texture.residentBytes > 0 ? m.texture.paddingWasteBytes / m.texture.residentBytes : 0;
        return wasteRatio > 0.5;
      }
      return true;
    });
  }, [mods, searchQuery, highWasteOnly]);

  const sortedMods = useMemo(() => {
    return [...filteredMods].sort((a, b) => {
      let valA = 0;
      let valB = 0;
      switch (sortField) {
        case "vram":
          valA = a.texture.residentBytes;
          valB = b.texture.residentBytes;
          break;
        case "waste":
          valA = a.texture.paddingWasteBytes;
          valB = b.texture.paddingWasteBytes;
          break;
        case "audio":
          valA = a.audio.effectPcmBytes;
          valB = b.audio.effectPcmBytes;
          break;
        case "bytecode":
          valA = a.bytecode.uncompressedBytecodeBytes;
          valB = b.bytecode.uncompressedBytecodeBytes;
          break;
        case "disk":
          valA = a.totalDiskBytes;
          valB = b.totalDiskBytes;
          break;
        case "memory":
          valA = a.estimatedMemoryBytes;
          valB = b.estimatedMemoryBytes;
          break;
      }
      return sortAsc ? valA - valB : valB - valA;
    });
  }, [filteredMods, sortField, sortAsc]);

  return (
    <section className="card mod-cost-table-card" aria-label="Mod Resource Cost Table">
      <div className="table-controls">
        <input
          type="text"
          placeholder="Filter mods by name or ID…"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          aria-label="Filter mods"
        />
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={highWasteOnly}
            onChange={(e) => setHighWasteOnly(e.target.checked)}
          />
          <span>Highlight High POT Waste (&gt;50%)</span>
        </label>
      </div>

      <table className="mod-cost-table" data-testid="mod-cost-table">
        <thead>
          <tr>
            <th>Mod Name / ID</th>
            <th onClick={() => handleSort("disk")} className="sortable-th">
              Disk {sortField === "disk" ? (sortAsc ? "▲" : "▼") : ""}
            </th>
            <th onClick={() => handleSort("vram")} className="sortable-th">
              GPU VRAM {sortField === "vram" ? (sortAsc ? "▲" : "▼") : ""}
            </th>
            <th onClick={() => handleSort("waste")} className="sortable-th">
              POT Waste {sortField === "waste" ? (sortAsc ? "▲" : "▼") : ""}
            </th>
            <th onClick={() => handleSort("audio")} className="sortable-th">
              Audio PCM {sortField === "audio" ? (sortAsc ? "▲" : "▼") : ""}
            </th>
            <th onClick={() => handleSort("bytecode")} className="sortable-th">
              Bytecode {sortField === "bytecode" ? (sortAsc ? "▲" : "▼") : ""}
            </th>
            <th onClick={() => handleSort("memory")} className="sortable-th">
              Est. Memory {sortField === "memory" ? (sortAsc ? "▲" : "▼") : ""}
            </th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {sortedMods.length === 0 ? (
            <tr>
              <td colSpan={8} className="empty-table-row">No mods match the filter criteria.</td>
            </tr>
          ) : (
            sortedMods.map((mod) => {
              const wasteRatio = mod.texture.residentBytes > 0 ? mod.texture.paddingWasteBytes / mod.texture.residentBytes : 0;
              const isHighWaste = wasteRatio > 0.5;
              return (
                <tr key={mod.id} data-testid={`mod-row-${mod.id}`} className={isHighWaste ? "row--high-waste" : ""}>
                  <td>
                    <strong>{mod.name}</strong>
                    <small className="mod-id-tag">{mod.id} v{mod.version}</small>
                  </td>
                  <td className="mono">{formatBytesHuman(mod.totalDiskBytes)}</td>
                  <td className="mono">{formatBytesHuman(mod.texture.residentBytes)}</td>
                  <td className={`mono ${isHighWaste ? "text--warning" : ""}`}>
                    {formatBytesHuman(mod.texture.paddingWasteBytes)}
                    {isHighWaste && <span className="badge badge--warning">{(wasteRatio * 100).toFixed(0)}%</span>}
                  </td>
                  <td className="mono">{formatBytesHuman(mod.audio.effectPcmBytes)}</td>
                  <td className="mono">{formatBytesHuman(mod.bytecode.uncompressedBytecodeBytes)}</td>
                  <td className="mono font-bold">{formatBytesHuman(mod.estimatedMemoryBytes)}</td>
                  <td>
                    <button className="button button--quiet button--compact" onClick={() => onSelectMod(mod)}>
                      Drilldown
                    </button>
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </section>
  );
}

export function ModAssetDrilldownDrawer({
  mod,
  onClose,
}: {
  mod: ModResourceCost;
  onClose: () => void;
}) {
  const [activeTab, setActiveTab] = useState<"textures" | "audio" | "bytecode">("textures");

  return (
    <div className="drawer-backdrop" role="dialog" aria-modal="true" aria-label={`Drilldown for ${mod.name}`}>
      <div className="card drilldown-drawer">
        <div className="drawer-header">
          <h2 className="orbitron-title">ASSET COST DRILLDOWN // {mod.name.toUpperCase()}</h2>
          <button className="button button--quiet" onClick={onClose}>Close</button>
        </div>

        <div className="drawer-tabs">
          <button
            className={`tab-button ${activeTab === "textures" ? "tab-button--active" : ""}`}
            onClick={() => setActiveTab("textures")}
          >
            Textures ({mod.details?.textures.length ?? mod.texture.count})
          </button>
          <button
            className={`tab-button ${activeTab === "audio" ? "tab-button--active" : ""}`}
            onClick={() => setActiveTab("audio")}
          >
            Audio ({mod.details?.audio.length ?? mod.audio.count})
          </button>
          <button
            className={`tab-button ${activeTab === "bytecode" ? "tab-button--active" : ""}`}
            onClick={() => setActiveTab("bytecode")}
          >
            Bytecode ({mod.details?.bytecode.length ?? mod.bytecode.jarCount})
          </button>
        </div>

        <div className="drawer-content">
          {activeTab === "textures" && (
            <div data-testid="tab-textures">
              <table className="asset-detail-table">
                <thead>
                  <tr>
                    <th>Asset Path</th>
                    <th>Resolution</th>
                    <th>Channels</th>
                    <th>VRAM (GPU)</th>
                    <th>POT Waste</th>
                  </tr>
                </thead>
                <tbody>
                  {(mod.details?.textures ?? []).map((tex) => (
                    <tr key={tex.logicalPath}>
                      <td className="asset-path">{tex.logicalPath}</td>
                      <td className="mono">{tex.width}x{tex.height}</td>
                      <td>{tex.channels === 4 ? "RGBA" : "RGB"}</td>
                      <td className="mono">{formatBytesHuman(tex.residentBytes)}</td>
                      <td className="mono">{formatBytesHuman(tex.paddingWasteBytes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {activeTab === "audio" && (
            <div data-testid="tab-audio">
              <table className="asset-detail-table">
                <thead>
                  <tr>
                    <th>Sound Path</th>
                    <th>Kind</th>
                    <th>Channels</th>
                    <th>Sample Rate</th>
                    <th>Duration</th>
                    <th>Decoded PCM</th>
                  </tr>
                </thead>
                <tbody>
                  {(mod.details?.audio ?? []).map((aud) => (
                    <tr key={aud.logicalPath}>
                      <td className="asset-path">{aud.logicalPath}</td>
                      <td>
                        <span className={`badge badge--${aud.kind}`}>[{aud.kind.toUpperCase()}]</span>
                      </td>
                      <td>{aud.channels === 2 ? "Stereo" : "Mono"}</td>
                      <td className="mono">{aud.sampleRate} Hz</td>
                      <td className="mono">{aud.durationSeconds.toFixed(2)}s</td>
                      <td className="mono">{formatBytesHuman(aud.pcmBytes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {activeTab === "bytecode" && (
            <div data-testid="tab-bytecode">
              <table className="asset-detail-table">
                <thead>
                  <tr>
                    <th>JAR File</th>
                    <th>Classes</th>
                    <th>Disk</th>
                    <th>Uncompressed</th>
                    <th>Shadowed / Duplicates</th>
                  </tr>
                </thead>
                <tbody>
                  {(mod.details?.bytecode ?? []).map((bc) => (
                    <tr key={bc.relativePath}>
                      <td className="asset-path">{bc.relativePath}</td>
                      <td className="mono">{bc.classCount}</td>
                      <td className="mono">{formatBytesHuman(bc.diskBytes)}</td>
                      <td className="mono">{formatBytesHuman(bc.uncompressedBytecodeBytes)}</td>
                      <td>
                        {bc.duplicateClasses.length > 0 ? (
                          <span className="badge badge--warning">
                            {bc.duplicateClasses.length} duplicates ({bc.duplicateClasses.join(", ")})
                          </span>
                        ) : (
                          "None"
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export function ResourceInspectorPage({
  report,
  loading,
  error,
  onRefresh,
}: {
  report: ResourceCostReport | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}) {
  const [selectedMod, setSelectedMod] = useState<ModResourceCost | null>(null);

  if (loading) {
    return (
      <div className="resource-page resource-page--loading">
        <h2 className="orbitron-title">INSPECTING RESOURCE & MEMORY FOOTPRINT…</h2>
        <p>Scanning textures, OGG audio headers, and bytecode across active mods.</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="resource-page resource-page--error" role="alert">
        <h2>Resource Inspection Failed</h2>
        <p>{error}</p>
        <button className="button button--primary" onClick={onRefresh}>Retry Inspection</button>
      </div>
    );
  }

  if (!report) return null;

  return (
    <div className="resource-inspector-page">
      <ResourceScoreboard summary={report.summary} />
      <ResourceBreakdownChart mods={report.mods} totalMemory={report.summary.totalEstimatedMemoryBytes} />
      <ModCostTable mods={report.mods} onSelectMod={(mod) => setSelectedMod(mod)} />
      {selectedMod && (
        <ModAssetDrilldownDrawer mod={selectedMod} onClose={() => setSelectedMod(null)} />
      )}
    </div>
  );
}

// ------------------- TEST SUITE -------------------
describe("Feature 10: Resource Inspector Desktop UI Test Suite", () => {
  const mockReport: ResourceCostReport = {
    format: "starsector-preflight-resource-cost-v1",
    generatedAt: "2026-08-18T15:10:00Z",
    installRoot: "/Applications/Starsector.app",
    profileFingerprint: "sha256_profile_cost",
    scanDurationMs: 142.5,
    summary: {
      enabledModCount: 3,
      totalDiskBytes: 850 * 1024 * 1024,
      totalEstimatedMemoryBytes: 4.8 * 1024 * 1024 * 1024,
      textureVram: {
        textureCount: 4210,
        diskBytes: 600 * 1024 * 1024,
        decodedBaseBytes: 2.8 * 1024 * 1024 * 1024,
        residentGpuBytes: 4.19 * 1024 * 1024 * 1024,
        paddingWasteBytes: 1.29 * 1024 * 1024 * 1024,
        mipChainUpperBoundBytes: 5.5 * 1024 * 1024 * 1024,
      },
      audioPcm: {
        soundCount: 1850,
        diskBytes: 200 * 1024 * 1024,
        effectPcmBytes: 580 * 1024 * 1024,
        effectCount: 1420,
        musicDiskBytes: 150 * 1024 * 1024,
        musicCount: 380,
        unreferencedCount: 50,
        unreferencedDiskBytes: 12 * 1024 * 1024,
      },
      bytecode: {
        jarCount: 12,
        diskBytes: 50 * 1024 * 1024,
        uncompressedBytecodeBytes: 120 * 1024 * 1024,
        classCount: 18450,
        duplicateClasses: 4,
      },
      preparedData: {
        preparedTextureBytes: 780 * 1024 * 1024,
        preparedAudioBytes: 510 * 1024 * 1024,
        janinoBytecodeBytes: 12 * 1024 * 1024,
        specCacheBytes: 4 * 1024 * 1024,
      },
    },
    mods: [
      {
        id: "graphicslib",
        name: "GraphicsLib",
        version: "1.12.1",
        order: 1,
        totalDiskBytes: 300 * 1024 * 1024,
        estimatedMemoryBytes: 2.1 * 1024 * 1024 * 1024,
        texture: {
          count: 1200,
          diskBytes: 250 * 1024 * 1024,
          decodedBytes: 1.5 * 1024 * 1024 * 1024,
          residentBytes: 2.0 * 1024 * 1024 * 1024,
          paddingWasteBytes: 0.5 * 1024 * 1024 * 1024,
          unmeasuredCount: 0,
        },
        audio: {
          count: 20,
          diskBytes: 10 * 1024 * 1024,
          effectPcmBytes: 80 * 1024 * 1024,
          musicBytes: 0,
          unreferencedBytes: 0,
        },
        bytecode: {
          jarCount: 1,
          diskBytes: 5 * 1024 * 1024,
          uncompressedBytecodeBytes: 20 * 1024 * 1024,
          classCount: 450,
          duplicateClassCount: 0,
        },
        preparedData: {
          textureCacheBytes: 200 * 1024 * 1024,
          audioCacheBytes: 50 * 1024 * 1024,
          specCacheBytes: 1 * 1024 * 1024,
        },
        details: {
          textures: [
            {
              logicalPath: "graphics/fx/distortion_map.png",
              width: 1024,
              height: 1024,
              channels: 4,
              diskBytes: 2 * 1024 * 1024,
              residentBytes: 4 * 1024 * 1024,
              paddingWasteBytes: 0,
              overridden: false,
            },
          ],
          audio: [
            {
              logicalPath: "sounds/fx/shield_impact.ogg",
              kind: "effect",
              channels: 2,
              sampleRate: 44100,
              durationSeconds: 1.5,
              diskBytes: 500 * 1024,
              pcmBytes: 529200,
            },
          ],
          bytecode: [
            {
              relativePath: "jars/GraphicsLib.jar",
              diskBytes: 5 * 1024 * 1024,
              uncompressedBytecodeBytes: 20 * 1024 * 1024,
              classCount: 450,
              duplicateClasses: [],
            },
          ],
        },
      },
      {
        id: "high_waste_mod",
        name: "High Waste Sprite Mod",
        version: "0.9.0",
        order: 2,
        totalDiskBytes: 150 * 1024 * 1024,
        estimatedMemoryBytes: 1.5 * 1024 * 1024 * 1024,
        texture: {
          count: 500,
          diskBytes: 120 * 1024 * 1024,
          decodedBytes: 400 * 1024 * 1024,
          residentBytes: 1.2 * 1024 * 1024 * 1024,
          paddingWasteBytes: 800 * 1024 * 1024, // >50% waste!
          unmeasuredCount: 0,
        },
        audio: {
          count: 5,
          diskBytes: 5 * 1024 * 1024,
          effectPcmBytes: 20 * 1024 * 1024,
          musicBytes: 0,
          unreferencedBytes: 5 * 1024 * 1024,
        },
        bytecode: {
          jarCount: 1,
          diskBytes: 2 * 1024 * 1024,
          uncompressedBytecodeBytes: 8 * 1024 * 1024,
          classCount: 120,
          duplicateClassCount: 2,
        },
        preparedData: {
          textureCacheBytes: 80 * 1024 * 1024,
          audioCacheBytes: 10 * 1024 * 1024,
          specCacheBytes: 500 * 1024,
        },
        details: {
          textures: [
            {
              logicalPath: "graphics/ships/odd_dimensions_ship.png",
              width: 513, // pads to 1024
              height: 513, // pads to 1024 -> massive waste!
              channels: 4,
              diskBytes: 1 * 1024 * 1024,
              residentBytes: 4 * 1024 * 1024,
              paddingWasteBytes: 2945000,
              overridden: false,
            },
          ],
          audio: [
            {
              logicalPath: "sounds/unused/theme.ogg",
              kind: "unreferenced",
              channels: 2,
              sampleRate: 44100,
              durationSeconds: 120.0,
              diskBytes: 5 * 1024 * 1024,
              pcmBytes: 0, // unreferenced costs 0 resident RAM
            },
          ],
          bytecode: [
            {
              relativePath: "jars/HighWaste.jar",
              diskBytes: 2 * 1024 * 1024,
              uncompressedBytecodeBytes: 8 * 1024 * 1024,
              classCount: 120,
              duplicateClasses: ["data.scripts.SharedUtil"],
            },
          ],
        },
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ========== TIER 1: HAPPY PATH EQUIVALENCE CLASS TESTS (>= 5 tests) ==========

  it("T1.1: renders Resource Cost telemetry scoreboard with VRAM, Audio PCM, Bytecode, and Prepared totals", () => {
    render(<ResourceInspectorPage report={mockReport} loading={false} error={null} onRefresh={vi.fn()} />);

    expect(screen.getByText("RESOURCE COST TELEMETRY SCOREBOARD")).toBeInTheDocument();

    const vramCard = screen.getByTestId("scoreboard-vram");
    expect(within(vramCard).getByText("4.19 GB")).toBeInTheDocument();
    expect(within(vramCard).getByText(/POT Padding Waste: 1.29 GB/)).toBeInTheDocument();

    const audioCard = screen.getByTestId("scoreboard-audio");
    expect(within(audioCard).getByText("580.0 MB")).toBeInTheDocument();
    expect(within(audioCard).getByText(/1420 effects/)).toBeInTheDocument();

    const bytecodeCard = screen.getByTestId("scoreboard-bytecode");
    expect(within(bytecodeCard).getByText("120.0 MB")).toBeInTheDocument();
    expect(within(bytecodeCard).getByText(/18,450 classes/)).toBeInTheDocument();

    const preparedCard = screen.getByTestId("scoreboard-prepared");
    expect(within(preparedCard).getByText("1.28 GB")).toBeInTheDocument();
  });

  it("T1.2: displays stacked memory breakdown bar chart with proportional mod segments", () => {
    render(<ResourceInspectorPage report={mockReport} loading={false} error={null} onRefresh={vi.fn()} />);

    const bar = screen.getByTestId("stacked-bar");
    expect(within(bar).getByTestId("bar-segment-graphicslib")).toBeInTheDocument();
    expect(within(bar).getByTestId("bar-segment-high_waste_mod")).toBeInTheDocument();
  });

  it("T1.3: renders sortable mod table with all resource columns and formatted values", () => {
    render(<ResourceInspectorPage report={mockReport} loading={false} error={null} onRefresh={vi.fn()} />);

    const table = screen.getByTestId("mod-cost-table");
    expect(within(table).getByText("GraphicsLib")).toBeInTheDocument();
    expect(within(table).getByText("graphicslib v1.12.1")).toBeInTheDocument();
    expect(within(table).getByText("High Waste Sprite Mod")).toBeInTheDocument();
  });

  it("T1.4: sorts mod table by VRAM, POT waste, and Audio PCM in ascending and descending orders", async () => {
    const user = userEvent.setup();
    render(<ResourceInspectorPage report={mockReport} loading={false} error={null} onRefresh={vi.fn()} />);

    const table = screen.getByTestId("mod-cost-table");
    const vramTh = within(table).getByRole("columnheader", { name: /GPU VRAM/ });

    // Initial descending sort: graphicslib (2.0 GB) before high_waste_mod (1.2 GB)
    const rowsBefore = within(table).getAllByRole("row").slice(1);
    expect(rowsBefore[0]).toHaveTextContent("GraphicsLib");

    // Click to toggle ascending
    await user.click(vramTh);
    const rowsAsc = within(table).getAllByRole("row").slice(1);
    expect(rowsAsc[0]).toHaveTextContent("High Waste Sprite Mod");
  });

  it("T1.5: opens asset drilldown drawer and navigates texture, audio, and bytecode sub-tabs", async () => {
    const user = userEvent.setup();
    render(<ResourceInspectorPage report={mockReport} loading={false} error={null} onRefresh={vi.fn()} />);

    const drilldownButtons = screen.getAllByRole("button", { name: "Drilldown" });
    await user.click(drilldownButtons[0]); // GraphicsLib

    const drawer = await screen.findByRole("dialog", { name: /Drilldown for GraphicsLib/ });
    expect(drawer).toBeInTheDocument();

    // Default textures tab
    expect(screen.getByTestId("tab-textures")).toHaveTextContent("graphics/fx/distortion_map.png");

    // Switch to audio tab
    await user.click(within(drawer).getByRole("button", { name: /Audio/ }));
    expect(screen.getByTestId("tab-audio")).toHaveTextContent("sounds/fx/shield_impact.ogg");
    expect(screen.getByTestId("tab-audio")).toHaveTextContent("[EFFECT]");

    // Switch to bytecode tab
    await user.click(within(drawer).getByRole("button", { name: /Bytecode/ }));
    expect(screen.getByTestId("tab-bytecode")).toHaveTextContent("jars/GraphicsLib.jar");
  });

  it("T1.6: filters mod list by search query and high POT waste checkbox", async () => {
    const user = userEvent.setup();
    render(<ResourceInspectorPage report={mockReport} loading={false} error={null} onRefresh={vi.fn()} />);

    const searchInput = screen.getByLabelText("Filter mods");
    await user.type(searchInput, "High Waste");

    expect(screen.getByText("High Waste Sprite Mod")).toBeInTheDocument();
    expect(screen.queryByText("GraphicsLib")).not.toBeInTheDocument();

    await user.clear(searchInput);
    expect(screen.getByText("GraphicsLib")).toBeInTheDocument();

    // High waste only checkbox
    const highWasteCheckbox = screen.getByRole("checkbox", { name: /Highlight High POT Waste/ });
    await user.click(highWasteCheckbox);

    // Only high_waste_mod has >50% waste
    expect(screen.getByText("High Waste Sprite Mod")).toBeInTheDocument();
    expect(screen.queryByText("GraphicsLib")).not.toBeInTheDocument();
  });

  // ========== TIER 2: BOUNDARY VALUE ANALYSIS & ERROR / FAULT INJECTION (>= 5 tests) ==========

  it("T2.1: handles empty mod list / vanilla-only configuration cleanly", () => {
    const emptyReport: ResourceCostReport = {
      ...mockReport,
      summary: {
        ...mockReport.summary,
        enabledModCount: 0,
        totalDiskBytes: 0,
        totalEstimatedMemoryBytes: 0,
      },
      mods: [],
    };

    render(<ResourceInspectorPage report={emptyReport} loading={false} error={null} onRefresh={vi.fn()} />);

    expect(screen.getByText("No mods match the filter criteria.")).toBeInTheDocument();
  });

  it("T2.2: handles extreme texture dimensions and power-of-two padding calculation", () => {
    const extremeMod: ModResourceCost = {
      ...mockReport.mods[0],
      id: "giant_atlas",
      name: "Giant 8K Atlas Mod",
      texture: {
        count: 1,
        diskBytes: 50 * 1024 * 1024,
        decodedBytes: 256 * 1024 * 1024,
        residentBytes: 256 * 1024 * 1024,
        paddingWasteBytes: 0,
        unmeasuredCount: 0,
      },
      details: {
        textures: [
          {
            logicalPath: "graphics/atlas/8k_sheet.png",
            width: 8192,
            height: 8192,
            channels: 4,
            diskBytes: 50 * 1024 * 1024,
            residentBytes: 256 * 1024 * 1024, // 8192 * 8192 * 4 = 268,435,456 B = 256 MB
            paddingWasteBytes: 0,
            overridden: false,
          },
        ],
        audio: [],
        bytecode: [],
      },
    };

    render(
      <ModAssetDrilldownDrawer mod={extremeMod} onClose={vi.fn()} />
    );

    expect(screen.getByText("8192x8192")).toBeInTheDocument();
    expect(screen.getByText("256.0 MB")).toBeInTheDocument();
  });

  it("T2.3: handles unreferenced audio files displaying badge and 0-byte resident OpenAL cost", () => {
    render(<ModAssetDrilldownDrawer mod={mockReport.mods[1]} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: /Audio/ }));

    expect(screen.getByText("[UNREFERENCED]")).toBeInTheDocument();
    expect(screen.getByText("sounds/unused/theme.ogg")).toBeInTheDocument();
    expect(screen.getByText("0 B")).toBeInTheDocument(); // 0-byte decoded PCM in OpenAL
  });

  it("T2.4: handles duplicate / shadowed class collisions displaying warning badge", () => {
    render(<ModAssetDrilldownDrawer mod={mockReport.mods[1]} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: /Bytecode/ }));

    expect(screen.getByText(/1 duplicates \(data.scripts.SharedUtil\)/)).toBeInTheDocument();
  });

  it("T2.5: handles resource inspection IPC timeout or failure with retry action", async () => {
    const user = userEvent.setup();
    const refreshSpy = vi.fn();

    render(
      <ResourceInspectorPage
        report={null}
        loading={false}
        error="Preflight engine timed out while scanning 12,000 textures."
        onRefresh={refreshSpy}
      />
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Preflight engine timed out while scanning 12,000 textures.");
    const retryBtn = screen.getByRole("button", { name: "Retry Inspection" });
    await user.click(retryBtn);

    expect(refreshSpy).toHaveBeenCalledTimes(1);
  });

  it("T2.6: correctly scales formatBytesHuman across B, KB, MB, and GB thresholds", () => {
    expect(formatBytesHuman(500)).toBe("500 B");
    expect(formatBytesHuman(2048)).toBe("2.0 KB");
    expect(formatBytesHuman(5 * 1024 * 1024)).toBe("5.0 MB");
    expect(formatBytesHuman(3.5 * 1024 * 1024 * 1024)).toBe("3.50 GB");
  });
});
