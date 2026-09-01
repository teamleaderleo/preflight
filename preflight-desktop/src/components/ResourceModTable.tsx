import type { ModResourceCost } from "../types";
import type { ResourceSortField } from "../useResourceCost";
import { formatBytes } from "../uiFormat";

interface ResourceModTableProps {
  mods: ModResourceCost[];
  sortField: ResourceSortField;
  searchQuery: string;
  onSortChange: (field: ResourceSortField) => void;
  onSearchChange: (query: string) => void;
  onSelectMod: (modId: string) => void;
}

export function ResourceModTable({
  mods,
  sortField,
  searchQuery,
  onSortChange,
  onSearchChange,
  onSelectMod,
}: ResourceModTableProps) {
  const getSortIcon = (field: ResourceSortField) => {
    if (sortField !== field) return "↕";
    return "↓";
  };

  return (
    <div className="card resource-table-card">
      <div className="resource-table__toolbar">
        <div className="resource-table__search-wrapper">
          <input
            type="search"
            className="input resource-table__search"
            placeholder="Search mods by name or ID…"
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            aria-label="Filter mod resource list"
          />
        </div>
        <div className="resource-table__count field-note">
          Showing {mods.length} mod{mods.length === 1 ? "" : "s"}
        </div>
      </div>

      <div className="resource-table-container">
        <table className="resource-table" aria-label="Per-mod resource cost breakdown">
          <thead>
            <tr>
              <th className="resource-th--mod">Mod (Order)</th>
              <th
                className="resource-th--clickable"
                onClick={() => onSortChange("disk")}
                title="Sort by disk size"
              >
                Disk {getSortIcon("disk")}
              </th>
              <th
                className="resource-th--clickable"
                onClick={() => onSortChange("vram")}
                title="Sort by GPU Texture VRAM"
              >
                VRAM (GPU) {getSortIcon("vram")}
              </th>
              <th
                className="resource-th--clickable"
                onClick={() => onSortChange("pcm")}
                title="Sort by Audio Effect PCM"
              >
                Audio PCM {getSortIcon("pcm")}
              </th>
              <th
                className="resource-th--clickable"
                onClick={() => onSortChange("bytecode")}
                title="Sort by Bytecode size"
              >
                Bytecode {getSortIcon("bytecode")}
              </th>
              <th className="resource-th--prepared">Prepared</th>
              <th
                className="resource-th--clickable"
                onClick={() => onSortChange("memory")}
                title="Sort by Total Estimated Memory"
              >
                Est. Total {getSortIcon("memory")}
              </th>
              <th className="resource-th--actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {mods.length === 0 ? (
              <tr>
                <td colSpan={8} className="resource-table__empty">
                  No mods matched the filter criteria.
                </td>
              </tr>
            ) : (
              mods.map((mod) => {
                const vramWastePct =
                  mod.texture.residentBytes > 0
                    ? (mod.texture.paddingWasteBytes * 100) / mod.texture.residentBytes
                    : 0;
                const totalPrepared =
                  mod.preparedData.textureCacheBytes +
                  mod.preparedData.audioCacheBytes +
                  mod.preparedData.specCacheBytes;

                return (
                  <tr key={mod.id} className="resource-tr">
                    <td className="resource-td--mod">
                      <div className="resource-mod-info">
                        <div className="resource-mod-title-row">
                          <strong className="resource-mod-name">{mod.name}</strong>
                          <span className="resource-order-badge">#{mod.order}</span>
                        </div>
                        <div className="resource-mod-meta">
                          <span className="resource-mod-id">{mod.id}</span>
                          <span className="resource-mod-version">v{mod.version}</span>
                        </div>
                      </div>
                    </td>

                    <td className="resource-td--number">{formatBytes(mod.totalDiskBytes)}</td>

                    <td className="resource-td--number">
                      <div className="resource-cell-stacked">
                        <span>{formatBytes(mod.texture.residentBytes)}</span>
                        {vramWastePct >= 25 && (
                          <span
                            className="resource-mini-badge resource-mini-badge--warning"
                            title={`${formatBytes(mod.texture.paddingWasteBytes)} power-of-two padding waste`}
                          >
                            {vramWastePct.toFixed(0)}% waste
                          </span>
                        )}
                        {mod.shadowedByOverrides?.texturesOverridden > 0 && (
                          <span
                            className="resource-mini-badge resource-mini-badge--muted"
                            title={`${mod.shadowedByOverrides.texturesOverridden} textures shadowed by higher priority mods`}
                          >
                            {mod.shadowedByOverrides.texturesOverridden} shadowed
                          </span>
                        )}
                      </div>
                    </td>

                    <td className="resource-td--number">
                      <div className="resource-cell-stacked">
                        <span>{formatBytes(mod.audio.effectPcmBytes)}</span>
                        {mod.audio.unreferencedBytes > 0 && (
                          <span
                            className="resource-mini-badge resource-mini-badge--muted"
                            title={`${formatBytes(mod.audio.unreferencedBytes)} unreferenced audio on disk`}
                          >
                            unref audio
                          </span>
                        )}
                      </div>
                    </td>

                    <td className="resource-td--number">
                      <div className="resource-cell-stacked">
                        <span>{formatBytes(mod.bytecode.uncompressedBytecodeBytes)}</span>
                        {mod.bytecode.duplicateClassCount > 0 && (
                          <span
                            className="resource-mini-badge resource-mini-badge--danger"
                            title={`${mod.bytecode.duplicateClassCount} duplicate class collisions with other mods`}
                          >
                            {mod.bytecode.duplicateClassCount} dups
                          </span>
                        )}
                      </div>
                    </td>

                    <td className="resource-td--number">{formatBytes(totalPrepared)}</td>

                    <td className="resource-td--number resource-td--total">
                      <strong>{formatBytes(mod.estimatedMemoryBytes)}</strong>
                    </td>

                    <td className="resource-td--actions">
                      <button
                        type="button"
                        className="button button--quiet button--compact"
                        onClick={() => onSelectMod(mod.id)}
                        title={`Inspect ${mod.name} resources`}
                      >
                        Inspect
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
