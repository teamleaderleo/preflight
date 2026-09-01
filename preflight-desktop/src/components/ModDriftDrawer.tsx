import { useCallback, useEffect, useId, useMemo, useState } from "react";
import { formatBytes } from "../uiFormat";
import { ModDriftBadge } from "./ModDriftBadge";
import type { ModDriftFileCategory, ModDriftItem } from "../types";

export interface ModDriftDrawerProps {
  mod: ModDriftItem | null;
  isOpen: boolean;
  onClose: () => void;
  onTriggerPreparation?: () => void;
}

type FilterCategory = "ALL" | ModDriftFileCategory;

export function ModDriftDrawer({
  mod,
  isOpen,
  onClose,
  onTriggerPreparation,
}: ModDriftDrawerProps) {
  const [activeCategory, setActiveCategory] = useState<FilterCategory>("ALL");
  const [filterQuery, setFilterQuery] = useState("");
  const titleId = useId();

  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      if (event.key === "Escape" && isOpen) {
        onClose();
      }
    },
    [isOpen, onClose]
  );

  useEffect(() => {
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleKeyDown]);

  const filteredFiles = useMemo(() => {
    if (!mod) return [];
    return mod.modifiedFiles.filter((file) => {
      const categoryMatch =
        activeCategory === "ALL" || file.category === activeCategory;
      const queryMatch =
        !filterQuery.trim() ||
        file.path.toLowerCase().includes(filterQuery.toLowerCase());
      return categoryMatch && queryMatch;
    });
  }, [mod, activeCategory, filterQuery]);

  if (!isOpen || !mod) return null;

  const totalDiffBytes = mod.modifiedFiles.reduce((acc, f) => {
    const curr = f.currentSizeBytes ?? 0;
    const exp = f.expectedSizeBytes ?? 0;
    return acc + Math.abs(curr - exp);
  }, 0);

  return (
    <div className="drift-drawer-overlay" onClick={onClose} role="presentation">
      <aside
        className="card drift-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="drift-drawer__scanlines" aria-hidden="true" />

        {/* Header */}
        <header className="drift-drawer__header">
          <div className="drift-drawer__identity">
            <p className="eyebrow">Drift Inspector // Avionics Channel</p>
            <h2 id={titleId} className="drift-drawer__title">
              {mod.modName}
            </h2>
            <div className="drift-drawer__meta">
              <span className="drift-drawer__ver">v{mod.declaredVersion}</span>
              <span className="drift-drawer__id">[{mod.modId}]</span>
              <ModDriftBadge severity={mod.severity} />
            </div>
          </div>
          <button
            type="button"
            className="icon-button drift-drawer__close"
            onClick={onClose}
            aria-label="Close drift inspector"
          >
            ✕
          </button>
        </header>

        {/* Telemetry KPI row */}
        <section
          className="drift-drawer__telemetry"
          aria-label="Drift telemetry metrics"
        >
          <div className="drift-kpi">
            <span className="drift-kpi__label">Modified Files</span>
            <strong className="drift-kpi__value">
              {mod.modifiedFiles.length}
            </strong>
          </div>
          <div className="drift-kpi">
            <span className="drift-kpi__label">Total Checked</span>
            <strong className="drift-kpi__value">
              {mod.currentSignature.totalFiles}
            </strong>
          </div>
          <div className="drift-kpi">
            <span className="drift-kpi__label">Payload Delta</span>
            <strong className="drift-kpi__value">
              {formatBytes(totalDiffBytes)}
            </strong>
          </div>
          <div className="drift-kpi">
            <span className="drift-kpi__label">Bytecode Status</span>
            <strong
              className={`drift-kpi__value ${
                mod.severity === "BYTECODE_DRIFT" ? "drift-kpi__value--alert" : ""
              }`}
            >
              {mod.severity === "BYTECODE_DRIFT" ? "Diverged" : "Matched"}
            </strong>
          </div>
        </section>

        {/* Status Summary & Recommendation */}
        <div className="drift-drawer__summary">
          <p>
            <strong>Diagnosis:</strong> {mod.statusSummary}
          </p>
          {mod.recommendation ? (
            <p className="drift-drawer__rec">
              <strong>Recommended:</strong> {mod.recommendation}
            </p>
          ) : null}
        </div>

        {/* Filters and Search */}
        <div className="drift-drawer__controls">
          <div
            className="drift-drawer__filter-tabs"
            role="tablist"
            aria-label="Filter file category"
          >
            {(
              [
                "ALL",
                "CSV",
                "SCRIPT",
                "BYTECODE",
                "CONFIG",
                "GRAPHIC",
                "AUDIO",
                "METADATA",
              ] as FilterCategory[]
            ).map((cat) => (
              <button
                key={cat}
                type="button"
                role="tab"
                aria-selected={activeCategory === cat}
                className={`drift-filter-tab ${
                  activeCategory === cat ? "drift-filter-tab--active" : ""
                }`}
                onClick={() => setActiveCategory(cat)}
              >
                {cat === "ALL" ? "All" : cat}
              </button>
            ))}
          </div>
          <input
            type="search"
            className="drift-drawer__search"
            placeholder="Filter modified file paths…"
            value={filterQuery}
            onChange={(e) => setFilterQuery(e.target.value)}
            aria-label="Filter modified files by path"
          />
        </div>

        {/* File Diff Table */}
        <div
          className="drift-drawer__body"
          tabIndex={0}
          aria-label="Modified file diff list"
        >
          {filteredFiles.length === 0 ? (
            <div className="drift-drawer__empty">
              <span>
                {mod.modifiedFiles.length === 0
                  ? "No modified files detected."
                  : "No files match active filter."}
              </span>
            </div>
          ) : (
            <table className="drift-file-table">
              <thead>
                <tr>
                  <th scope="col">Status</th>
                  <th scope="col">Category</th>
                  <th scope="col">File Path</th>
                  <th scope="col">Size Delta</th>
                  <th scope="col">SHA Preview</th>
                </tr>
              </thead>
              <tbody>
                {filteredFiles.map((file) => {
                  const sizeDiff =
                    (file.currentSizeBytes ?? 0) - (file.expectedSizeBytes ?? 0);
                  const diffText =
                    sizeDiff === 0
                      ? "±0 B"
                      : sizeDiff > 0
                      ? `+${formatBytes(sizeDiff)}`
                      : `-${formatBytes(Math.abs(sizeDiff))}`;
                  return (
                    <tr
                      key={file.path}
                      className={`drift-file-row drift-file-row--${file.changeType.toLowerCase()}`}
                    >
                      <td>
                        <span
                          className={`drift-change-chip drift-change-chip--${file.changeType.toLowerCase()}`}
                        >
                          {file.changeType}
                        </span>
                      </td>
                      <td>
                        <span className="drift-cat-tag">{file.category}</span>
                      </td>
                      <td className="drift-file-path" title={file.path}>
                        <code>{file.path}</code>
                        {file.detail ? (
                          <small className="drift-file-detail">
                            {file.detail}
                          </small>
                        ) : null}
                      </td>
                      <td className="drift-file-size">
                        <code>{diffText}</code>
                      </td>
                      <td className="drift-file-sha">
                        <code>
                          {file.currentSha256
                            ? file.currentSha256.slice(0, 8)
                            : "none"}
                        </code>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* Footer actions */}
        <footer className="drift-drawer__footer">
          <button
            type="button"
            className="button button--quiet"
            onClick={onClose}
          >
            Dismiss
          </button>
          {onTriggerPreparation &&
          (mod.severity === "SAME_VERSION_DRIFT" ||
            mod.severity === "BYTECODE_DRIFT") ? (
            <button
              type="button"
              className="button button--primary"
              onClick={() => {
                onClose();
                onTriggerPreparation();
              }}
            >
              Re-run Preparation
            </button>
          ) : null}
        </footer>
      </aside>
    </div>
  );
}
