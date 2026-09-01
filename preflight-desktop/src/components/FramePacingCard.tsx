import type { FramePacingDistribution, FramePacingSummary } from "../types";
import { formatDuration } from "../uiFormat";

function fps(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 1 });
}

function milliseconds(micros: number): string {
  return (micros / 1_000).toLocaleString(undefined, { maximumFractionDigits: 1 });
}

function microseconds(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function decimal(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function FramePacingRow({ label, result }: { label: string; result: FramePacingDistribution }) {
  const coverage = typeof result.activeMillis === "number"
    ? `${result.frames.toLocaleString()} frames · ${formatDuration(result.activeMillis)} active`
    : `${result.frames.toLocaleString()} frames`;
  const stutterBurden = result.stutterBurdenMillisPerSecond;
  const repeatedSlowFrames = result.repeatedSlowFramesPercent;
  const slowFramesPerMinute = result.slowFramesPerMinute;
  const smoothnessMetrics = typeof stutterBurden === "number"
    && typeof repeatedSlowFrames === "number"
    && typeof slowFramesPerMinute === "number"
    ? (
      <div className="frame-pacing-row__metrics frame-pacing-row__metrics--smoothness">
        <span><small>Stutter burden</small><strong>{decimal(stutterBurden)} ms/s</strong></span>
        <span><small>Repeated slow frames</small><strong>{decimal(repeatedSlowFrames)}%</strong></span>
        <span><small>Slow frames</small><strong>{decimal(slowFramesPerMinute)} / min</strong></span>
        <span><small>One-percent low</small><strong>{fps(result.onePercentLowFps)} FPS</strong></span>
        <span><small>p99 frame time</small><strong>{milliseconds(result.p99Micros)} ms</strong></span>
        <span><small>Average</small><strong>{fps(result.averageFps)} FPS</strong></span>
      </div>
    )
    : null;
  return (
    <div className="frame-pacing-row" role="group" aria-label={label}>
      <div className="frame-pacing-row__heading">
        <strong>{label}</strong>
        <small>{coverage}</small>
      </div>
      {smoothnessMetrics ?? (
        <div className="frame-pacing-row__metrics">
          <span><small>Average</small><strong>{fps(result.averageFps)} FPS</strong></span>
          <span><small>One-percent low</small><strong>{fps(result.onePercentLowFps)} FPS</strong></span>
          <span><small>p95 frame time</small><strong>{milliseconds(result.p95Micros)} ms</strong></span>
          <span><small>p99 frame time</small><strong>{milliseconds(result.p99Micros)} ms</strong></span>
        </div>
      )}
    </div>
  );
}

export function FramePacingCard({ framePacing }: { framePacing?: FramePacingSummary | null }) {
  const initialCampaign = framePacing?.initialCampaign;
  const settledCampaign = framePacing?.settledCampaign;
  const settledPausedCampaign = framePacing?.settledPausedCampaign;
  const settledUnpausedCampaign = framePacing?.settledUnpausedCampaign;
  const splitCampaign = settledPausedCampaign && settledUnpausedCampaign
    ? { paused: settledPausedCampaign, unpaused: settledUnpausedCampaign }
    : null;
  const campaign = settledCampaign ?? framePacing?.campaign ?? initialCampaign;
  const campaignLabel = settledCampaign
    ? "Campaign after 30 seconds"
    : framePacing?.campaign
      ? "Campaign"
      : "Campaign first 30 seconds";
  const combat = framePacing?.combat;
  if (!campaign && !splitCampaign && !combat) return null;

  return (
    <section className="card frame-pacing-card" aria-label="Latest frame pacing">
      <div className="card__heading">
        <div>
          <p className="eyebrow">Latest recorded session</p>
          <h2>Frame pacing</h2>
        </div>
        <small>Higher FPS and lower frame times are better.</small>
      </div>
      <div className="frame-pacing-card__sessions">
        {initialCampaign && settledCampaign ? <FramePacingRow label="Campaign first 30 seconds" result={initialCampaign} /> : null}
        {splitCampaign ? (
          <>
            <FramePacingRow label="Paused campaign after 30 seconds" result={splitCampaign.paused} />
            <FramePacingRow label="Unpaused campaign after 30 seconds" result={splitCampaign.unpaused} />
          </>
        ) : campaign ? <FramePacingRow label={campaignLabel} result={campaign} /> : null}
        {combat ? <FramePacingRow label="Combat" result={combat} /> : null}
      </div>
      <div className="frame-pacing-card__notes">
        {initialCampaign && settledCampaign ? (
          <small>These campaign rows come from the same launch: the first 30 seconds, then later play. They don’t compare optimizations off and on.</small>
        ) : null}
        {splitCampaign ? (
          <small>Paused and unpaused rows are disjoint active-state windows. Focus changes and pause-transition frames are excluded.</small>
        ) : null}
        {splitCampaign ? (
          <small>Recurring slow-frame clusters and excess slow-frame time rank ahead of isolated hitches or a single percentile.</small>
        ) : null}
        {framePacing?.measurementAverageMicros !== null && framePacing?.measurementAverageMicros !== undefined ? (
          <small>
            Recording cost averaged <strong>{microseconds(framePacing.measurementAverageMicros)} μs per frame</strong>. That is the recorder's own work, not a game-speed comparison.
          </small>
        ) : null}
        <small>Recorded locally after you opted in. The recorder doesn’t open or change save files.</small>
      </div>
    </section>
  );
}
