import type { ResourceCostSummary } from "../types";
import { formatBytes } from "../uiFormat";
import { InfoTip } from "./InfoTip";

interface ResourceScoreboardProps {
  summary: ResourceCostSummary | null;
  loading?: boolean;
}

export function ResourceScoreboard({ summary, loading }: ResourceScoreboardProps) {
  if (!summary && loading) {
    return (
      <div className="resource-scoreboard resource-scoreboard--loading" aria-busy="true">
        <div className="card resource-card skeleton-card" />
        <div className="card resource-card skeleton-card" />
        <div className="card resource-card skeleton-card" />
        <div className="card resource-card skeleton-card" />
        <div className="card resource-card skeleton-card" />
      </div>
    );
  }

  if (!summary) return null;

  const { textureVram, audioPcm, bytecode, preparedData } = summary;
  const vramWastePct =
    textureVram.residentGpuBytes > 0
      ? (textureVram.paddingWasteBytes * 100) / textureVram.residentGpuBytes
      : 0;

  const totalPreparedBytes =
    preparedData.preparedTextureBytes +
    preparedData.preparedAudioBytes +
    preparedData.janinoBytecodeBytes +
    preparedData.specCacheBytes;

  return (
    <div className="resource-scoreboard" role="region" aria-label="Resource telemetry summary">
      {/* 1. Total Estimated RAM */}
      <div className="card resource-card resource-card--primary">
        <div className="resource-card__header">
          <span className="resource-card__title">Total Est. RAM</span>
          <InfoTip label="About Estimated RAM">
            Estimated uncompressed runtime footprint in memory, combining GPU VRAM textures, OpenAL audio effects in RAM, and Java bytecode.
          </InfoTip>
        </div>
        <div className="resource-card__value">
          <span className="resource-card__number">{formatBytes(summary.totalEstimatedMemoryBytes)}</span>
        </div>
        <div className="resource-card__subtext">
          <span>{summary.enabledModCount} enabled mod{summary.enabledModCount === 1 ? "" : "s"} · {formatBytes(summary.totalDiskBytes)} disk</span>
        </div>
      </div>

      {/* 2. GPU Texture VRAM */}
      <div className="card resource-card">
        <div className="resource-card__header">
          <span className="resource-card__title">GPU Texture VRAM</span>
          <InfoTip label="About Texture VRAM">
            OpenGL VRAM allocated for uncompressed RGBA8888 textures padded to powers-of-two by Slick2D.
          </InfoTip>
        </div>
        <div className="resource-card__value">
          <span className="resource-card__number">{formatBytes(textureVram.residentGpuBytes)}</span>
          {vramWastePct >= 30 && (
            <span className="resource-badge resource-badge--warning" title="Power-of-two padding waste">
              {vramWastePct.toFixed(0)}% POT waste
            </span>
          )}
        </div>
        <div className="resource-card__subtext">
          <span>{textureVram.textureCount.toLocaleString()} textures · {formatBytes(textureVram.mipChainUpperBoundBytes)} mip ceiling</span>
        </div>
      </div>

      {/* 3. Audio Effect PCM */}
      <div className="card resource-card">
        <div className="resource-card__header">
          <span className="resource-card__title">Audio Effect PCM</span>
          <InfoTip label="About Audio PCM">
            Uncompressed 16-bit PCM resident in RAM for weapon and UI sound effects. Music is streamed from disk with 0 B resident memory.
          </InfoTip>
        </div>
        <div className="resource-card__value">
          <span className="resource-card__number">{formatBytes(audioPcm.effectPcmBytes)}</span>
          {audioPcm.unreferencedCount > 0 && (
            <span className="resource-badge resource-badge--muted" title="Unreferenced audio files">
              {audioPcm.unreferencedCount} unref
            </span>
          )}
        </div>
        <div className="resource-card__subtext">
          <span>{audioPcm.effectCount} effects in RAM · {audioPcm.musicCount} streamed ({formatBytes(audioPcm.musicDiskBytes)})</span>
        </div>
      </div>

      {/* 4. Bytecode & Classes */}
      <div className="card resource-card">
        <div className="resource-card__header">
          <span className="resource-card__title">Bytecode & Classes</span>
          <InfoTip label="About Bytecode">
            Uncompressed bytecode classes loaded into JVM Metaspace across all enabled mod JARs.
          </InfoTip>
        </div>
        <div className="resource-card__value">
          <span className="resource-card__number">{formatBytes(bytecode.uncompressedBytecodeBytes)}</span>
          {bytecode.duplicateClasses > 0 && (
            <span className="resource-badge resource-badge--danger" title="Cross-mod class collisions">
              {bytecode.duplicateClasses} collision{bytecode.duplicateClasses === 1 ? "" : "s"}
            </span>
          )}
        </div>
        <div className="resource-card__subtext">
          <span>{bytecode.classCount.toLocaleString()} classes in {bytecode.jarCount} JAR{bytecode.jarCount === 1 ? "" : "s"}</span>
        </div>
      </div>

      {/* 5. Prepared Data */}
      <div className="card resource-card">
        <div className="resource-card__header">
          <span className="resource-card__title">Prepared Caches</span>
          <InfoTip label="About Prepared Caches">
            Preflight fast-start cache artifacts including pre-swizzled textures, audio blobs, and Janino bytecode.
          </InfoTip>
        </div>
        <div className="resource-card__value">
          <span className="resource-card__number">{formatBytes(totalPreparedBytes)}</span>
        </div>
        <div className="resource-card__subtext">
          <span>{formatBytes(preparedData.preparedTextureBytes)} tex · {formatBytes(preparedData.preparedAudioBytes)} audio</span>
        </div>
      </div>
    </div>
  );
}
