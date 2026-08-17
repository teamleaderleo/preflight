import { ArrowIcon, GaugeIcon } from "../icons";
import type { PlaytimeSnapshot, WireframeHull } from "../types";
import type { SpeedStanding } from "../useSpeedRecord";
import { formatDuration, formatPlaytime } from "../uiFormat";
import { FlightInstrument } from "./FlightInstrument";

interface SpeedScoreboardProps {
  standing: SpeedStanding;
  isReady: boolean;
  playtime?: PlaytimeSnapshot;
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
  if (seconds < 60) return `${seconds} second${seconds === 1 ? "" : "s"}`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"}`;
  const hours = ms / 3_600_000;
  return `${hours < 10 ? hours.toFixed(1) : Math.round(hours)} hour${hours >= 2 ? "s" : ""}`;
}

/*
 * The page named Speed used to hold a switch, a disk figure and an accordion, and not one number
 * that was a speed. Measuring costs several minutes of the machine to itself, and the result was
 * kept only until the app closed.
 *
 * So this is the scoreboard for that effort, and it is deliberately the loudest thing on the
 * page. The multiplier is the headline because "5.5x" is the shape of the claim people repeat;
 * the two raw timings sit under it because a multiplier with nothing behind it is a marketing
 * number. The running total is frankly a vanity figure and is labelled as an estimate: it is
 * launches counted since the measurement, times the measured saving, and it says so.
 */
export function SpeedScoreboard({ standing, isReady, playtime, hull, onOpenBenchmark }: SpeedScoreboardProps) {
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
          <strong>Measure your startup time.</strong>
          <p className="scoreboard__prompt">Starsector opens twice so Preflight can compare launch times.</p>
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
