import type { DriftSeverity } from "../types";

export interface ModDriftBadgeProps {
  severity: DriftSeverity;
  modifiedCount?: number;
  compact?: boolean;
  onClick?: () => void;
  disabled?: boolean;
  ariaLabel?: string;
}

interface SeverityMeta {
  label: string;
  glyph: string;
  toneClass: string;
  tooltip: string;
}

const SEVERITY_CONFIG: Record<DriftSeverity, SeverityMeta> = {
  PRISTINE: {
    label: "Pristine",
    glyph: "✓",
    toneClass: "drift-badge--pristine",
    tooltip: "Signatures match reference catalog exactly.",
  },
  SAME_VERSION_DRIFT: {
    label: "Content Drift",
    glyph: "Δ",
    toneClass: "drift-badge--content-drift",
    tooltip: "CSVs or config files modified locally under the same declared version.",
  },
  BYTECODE_DRIFT: {
    label: "Bytecode Drift",
    glyph: "⚡",
    toneClass: "drift-badge--bytecode-drift",
    tooltip: "Compiled JAR bytecode altered. In-memory classes may diverge from cache.",
  },
  CORRUPT_METADATA: {
    label: "Corrupt Metadata",
    glyph: "!",
    toneClass: "drift-badge--corrupt",
    tooltip: "mod_info.json is missing, malformed, or unparseable.",
  },
  VERSION_CHANGED: {
    label: "Version Changed",
    glyph: "↻",
    toneClass: "drift-badge--content-drift",
    tooltip: "Mod version differs from reference catalog.",
  },
  MISSING_MOD: {
    label: "Missing Mod",
    glyph: "✕",
    toneClass: "drift-badge--corrupt",
    tooltip: "Mod exists in reference but is missing from disk.",
  },
  NEW_MOD: {
    label: "New Mod",
    glyph: "+",
    toneClass: "drift-badge--pristine",
    tooltip: "Mod was newly added relative to baseline.",
  },
};

export function ModDriftBadge({
  severity,
  modifiedCount = 0,
  compact = false,
  onClick,
  disabled = false,
  ariaLabel,
}: ModDriftBadgeProps) {
  const meta = SEVERITY_CONFIG[severity] ?? SEVERITY_CONFIG.PRISTINE;
  const countDisplay = modifiedCount > 0 ? ` (${modifiedCount})` : "";
  const accessibleLabel = ariaLabel ?? `${meta.label}${countDisplay}: ${meta.tooltip}`;

  if (onClick) {
    return (
      <button
        type="button"
        className={`drift-badge ${meta.toneClass} ${compact ? "drift-badge--compact" : ""} drift-badge--interactive`}
        onClick={onClick}
        disabled={disabled}
        aria-label={accessibleLabel}
        title={meta.tooltip}
      >
        <span className="drift-badge__glyph" aria-hidden="true">{meta.glyph}</span>
        <span className="drift-badge__label">{compact ? meta.glyph : meta.label}</span>
        {modifiedCount > 0 && !compact ? (
          <span className="drift-badge__count">{modifiedCount}</span>
        ) : null}
      </button>
    );
  }

  return (
    <span
      className={`drift-badge ${meta.toneClass} ${compact ? "drift-badge--compact" : ""}`}
      role="status"
      aria-label={accessibleLabel}
      title={meta.tooltip}
    >
      <span className="drift-badge__glyph" aria-hidden="true">{meta.glyph}</span>
      <span className="drift-badge__label">{compact ? meta.glyph : meta.label}</span>
      {modifiedCount > 0 && !compact ? (
        <span className="drift-badge__count">{modifiedCount}</span>
      ) : null}
    </span>
  );
}
