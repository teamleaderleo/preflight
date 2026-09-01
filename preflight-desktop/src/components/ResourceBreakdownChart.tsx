import type { ResourceCostSummary } from "../types";
import { formatBytes } from "../uiFormat";

interface ResourceBreakdownChartProps {
  summary: ResourceCostSummary | null;
}

export function ResourceBreakdownChart({ summary }: ResourceBreakdownChartProps) {
  if (!summary || summary.totalEstimatedMemoryBytes <= 0) return null;

  const total = summary.totalEstimatedMemoryBytes;
  const vram = summary.textureVram.residentGpuBytes;
  const audio = summary.audioPcm.effectPcmBytes;
  const bytecode = summary.bytecode.uncompressedBytecodeBytes;

  const vramPct = Math.max(0, (vram * 100) / total);
  const audioPct = Math.max(0, (audio * 100) / total);
  const bytecodePct = Math.max(0, (bytecode * 100) / total);

  return (
    <div className="card resource-breakdown-card" role="region" aria-label="Memory breakdown chart">
      <div className="resource-breakdown__header">
        <h3 className="resource-breakdown__heading">Runtime Memory Allocation Distribution</h3>
        <span className="field-note">{formatBytes(total)} active memory</span>
      </div>

      <div className="resource-breakdown__bar" role="progressbar" aria-valuenow={100} aria-valuemin={0} aria-valuemax={100}>
        <div
          className="resource-breakdown__segment resource-breakdown__segment--vram"
          style={{ width: `${vramPct}%` }}
          title={`GPU Texture VRAM: ${formatBytes(vram)} (${vramPct.toFixed(1)}%)`}
        />
        <div
          className="resource-breakdown__segment resource-breakdown__segment--audio"
          style={{ width: `${audioPct}%` }}
          title={`Audio Effect PCM: ${formatBytes(audio)} (${audioPct.toFixed(1)}%)`}
        />
        <div
          className="resource-breakdown__segment resource-breakdown__segment--bytecode"
          style={{ width: `${bytecodePct}%` }}
          title={`Bytecode & Metaspace: ${formatBytes(bytecode)} (${bytecodePct.toFixed(1)}%)`}
        />
      </div>

      <div className="resource-breakdown__legend">
        <div className="resource-legend-item">
          <span className="resource-legend-indicator resource-legend-indicator--vram" />
          <span className="resource-legend-label">GPU Texture VRAM</span>
          <span className="resource-legend-value">{formatBytes(vram)} ({vramPct.toFixed(1)}%)</span>
        </div>
        <div className="resource-legend-item">
          <span className="resource-legend-indicator resource-legend-indicator--audio" />
          <span className="resource-legend-label">Audio Effect PCM</span>
          <span className="resource-legend-value">{formatBytes(audio)} ({audioPct.toFixed(1)}%)</span>
        </div>
        <div className="resource-legend-item">
          <span className="resource-legend-indicator resource-legend-indicator--bytecode" />
          <span className="resource-legend-label">Bytecode & Metaspace</span>
          <span className="resource-legend-value">{formatBytes(bytecode)} ({bytecodePct.toFixed(1)}%)</span>
        </div>
      </div>
    </div>
  );
}
