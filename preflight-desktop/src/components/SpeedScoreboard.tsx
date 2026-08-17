import { ArrowIcon, GaugeIcon } from "../icons";
import type { LastRun, PlaytimeSnapshot, WireframeHull } from "../types";
import type { SpeedStanding } from "../useSpeedRecord";
import { formatDuration, formatPlaytime } from "../uiFormat";
import { FlightInstrument } from "./FlightInstrument";

interface SpeedScoreboardProps {
  standing: SpeedStanding;
  isReady: boolean;
  playtime?: PlaytimeSnapshot;
  lastRun?: LastRun | null;
  hull: WireframeHull;
  onOpenBenchmark: () => void;
}

function RecordedPlaytime({ playtime }: { playtime?: PlaytimeSnapshot }) {
  if (!playtime?.readable || playtime.launches <= 0 || playtime.totalMillis <= 0) return null;
  const total = formatPlaytime(playtime.totalMillis);
  const sessions = playtime.launches.toLocaleString();
  return (
    <div
      className="scoreboard__playtime"
      aria-label={`${total} recorded playtime across ${sessions} sessions`}
      title={`Across ${sessions} recorded sessions`}
    >
      <strong>{total}</strong>
      <span>recorded playtime</span>
    </div>
  );
}

/** The vanity total, which is read at a glance and never needs a decimal second. */
export function formatSavedTotal(ms: number): string {
  const seconds = Math.round(ms / 1_000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainderSeconds = seconds - minutes * 60;
  if (minutes < 60) {
    return remainderSeconds === 0 ? `${minutes}m` : `${minutes}m ${remainderSeconds}s`;
  }
  const hours = (ms / 3_600_000).toFixed(1);
  return `${hours}h`;
}

/**
 * Shows the speed multiplier and the vanity total if a benchmark has been run, or invites one.
 *
 * The multiplier is from the last paired benchmark. The vanity total is that multiplier against all
 * launches counted since the measurement, times the measured saving, and it says so.
 */
export function SpeedScoreboard({ standing, isReady, playtime, lastRun, hull, onOpenBenchmark }: SpeedScoreboardProps) {
  const { record, multiplier, totalSavedMs } = standing;

  if (!record || multiplier === null) {
    return (
      <section className="card scoreboard scoreboard--unmeasured" aria-label="Startup speed">
        <FlightInstrument hull={hull} />
        <div className="scoreboard__headline">
          <p className="eyebrow">Your startup</p>
          <strong className="scoreboard__figure scoreboard__figure--unknown" aria-hidden="true">?×</strong>
          <RecordedPlaytime playtime={playtime} />
        </div>
        <div className="scoreboard__body">
          <strong>{lastRun?.durationMillis ? `Last launch took ${formatDuration(lastRun.durationMillis)}` : "Measure your startup time."}</strong>
          <p className="scoreboard__prompt">
            {lastRun?.durationMillis
              ? "Starsector opens twice so Preflight can compare your measured speedup."
              : "Starsector opens twice so Preflight can compare launch times."}
          </p>
          <button className="button button--primary" type="button" onClick={onOpenBenchmark} disabled={!isReady}><GaugeIcon />Measure speed<ArrowIcon /></button>
        </div>
      </section>
    );
  }

  const measuredOn = new Date(record.recordedAt);
  const totalLaunches = record.bankedLaunches + record.fastLaunches;
  return (
    <section className="card scoreboard" aria-label="Startup speed">
      <FlightInstrument hull={hull} />
      <div className="scoreboard__headline">
        <p className="eyebrow">Measured on this computer</p>
        <strong className="scoreboard__figure">
          {multiplier.toFixed(1)}<span>× faster</span>
        </strong>
        <div className="scoreboard__split">
          <span><small>Normal</small>{formatDuration(record.measurementOnlyMs)}</span>
          <ArrowIcon aria-hidden="true" />
          <span className="scoreboard__split--won"><small>Preflight</small>{formatDuration(record.optimizedMs)}</span>
        </div>
        <RecordedPlaytime playtime={playtime} />
      </div>
      <div className="scoreboard__body">
        <div className="scoreboard__total">
          {totalLaunches > 0 ? (
            <>
              <strong>{formatSavedTotal(totalSavedMs)}</strong>
              <span>saved across {totalLaunches.toLocaleString()} matching launch{totalLaunches === 1 ? "" : "es"}.</span>
              <small>Estimated from the measured saving above.</small>
            </>
          ) : (
            <>
              <strong>{formatDuration(record.measurementOnlyMs - record.optimizedMs)} saved per launch</strong>
              <span>Based on this benchmark.</span>
            </>
          )}
        </div>
        <div className="scoreboard__actions">
          <button className="button button--quiet button--compact" type="button" onClick={onOpenBenchmark} disabled={!isReady}><GaugeIcon />Measure again</button>
          <small>Measured {measuredOn.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}</small>
        </div>
      </div>
    </section>
  );
}
