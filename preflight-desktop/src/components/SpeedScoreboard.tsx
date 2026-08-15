import { ArrowIcon, GaugeIcon } from "../icons";
import type { SpeedStanding } from "../useSpeedRecord";

interface SpeedScoreboardProps {
  standing: SpeedStanding;
  isReady: boolean;
  onOpenBenchmark: () => void;
}

/** Seconds with one decimal below a minute, then minutes and seconds. */
function formatDuration(ms: number): string {
  const seconds = ms / 1_000;
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${Math.round(seconds - minutes * 60)}s`;
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
export function SpeedScoreboard({ standing, isReady, onOpenBenchmark }: SpeedScoreboardProps) {
  const { record, multiplier, totalSavedMs } = standing;

  if (!record || multiplier === null) {
    return (
      <section className="card scoreboard scoreboard--unmeasured" aria-label="Startup speed">
        <div className="scoreboard__headline">
          <p className="eyebrow">How much faster?</p>
          <strong className="scoreboard__figure scoreboard__figure--unknown" aria-hidden="true">?</strong>
        </div>
        <div className="scoreboard__body">
          <p>Preflight has never been timed on this computer, so there is no honest number to put here.</p>
          <p className="scoreboard__prompt">One measured run opens Starsector twice and times both. It takes a few minutes and needs the machine to itself — after that this is a number, and it stays one.</p>
          <button className="button button--primary" type="button" onClick={onOpenBenchmark} disabled={!isReady}><GaugeIcon />Time it<ArrowIcon /></button>
        </div>
      </section>
    );
  }

  const measuredOn = new Date(record.recordedAt);
  return (
    <section className="card scoreboard" aria-label="Startup speed">
      <div className="scoreboard__headline">
        <p className="eyebrow">Startup on this computer</p>
        <strong className="scoreboard__figure">
          {multiplier.toFixed(1)}<span>× faster</span>
        </strong>
        <div className="scoreboard__split">
          <span><small>Normal</small>{formatDuration(record.measurementOnlyMs)}</span>
          <ArrowIcon aria-hidden="true" />
          <span className="scoreboard__split--won"><small>Preflight</small>{formatDuration(record.optimizedMs)}</span>
        </div>
      </div>
      <div className="scoreboard__body">
        <div className="scoreboard__total">
          {record.fastLaunches > 0 || record.bankedSavedMs > 0 ? (
            <>
              <strong>{formatSavedTotal(totalSavedMs)}</strong>
              <span>not spent staring at a loading screen, across {record.fastLaunches.toLocaleString()} fast launch{record.fastLaunches === 1 ? "" : "es"}.</span>
              <small>An estimate: launches counted here, times the saving measured above.</small>
            </>
          ) : (
            <>
              <strong>{formatDuration(record.measurementOnlyMs - record.optimizedMs)} a launch</strong>
              <span>from here on. This total grows every time you launch from Preflight.</span>
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
