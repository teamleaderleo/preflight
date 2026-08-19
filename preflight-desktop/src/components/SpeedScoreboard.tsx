import { useState } from "react";
import { ArrowIcon, GaugeIcon } from "../icons";
import { createPlaytimeShareText } from "../playtimeShare";
import type { LastRun, PlaytimeSnapshot, WireframeHull } from "../types";
import type { SpeedMeasurement, SpeedStanding } from "../useSpeedRecord";
import { formatDuration, formatPlaytime } from "../uiFormat";
import { startupComparisonPresentation } from "../speedScoreboardFormat";
import { FlightInstrument } from "./FlightInstrument";

interface SpeedScoreboardProps {
  standing: SpeedStanding;
  isReady: boolean;
  playtime?: PlaytimeSnapshot;
  lastRun?: LastRun | null;
  hull: WireframeHull;
  onOpenBenchmark: () => void;
}

function formatSessionContext(totalMillis: number): string {
  const compact = formatPlaytime(totalMillis);
  if (compact.endsWith("h")) return `${compact.slice(0, -1)} hours`;
  if (compact.endsWith("m")) return `${compact.slice(0, -1)} minutes`;
  return compact;
}

function RecordedPlaytime({ playtime }: { playtime?: PlaytimeSnapshot }) {
  if (!playtime?.readable || playtime.launches <= 0 || playtime.totalMillis <= 0) return null;
  const total = formatPlaytime(playtime.totalMillis);
  const sessions = playtime.launches.toLocaleString();
  return (
    <div
      className="scoreboard__playtime"
      aria-label={`${total} recorded playtime across ${sessions} sessions`}
      title={`Across ${sessions} recorded sessions · longest ${formatSessionContext(playtime.longestSessionMillis)}`}
    >
      <strong>{total}</strong>
      <span>recorded playtime</span>
    </div>
  );
}

function PlaytimeCopyButton({ playtime }: { playtime?: PlaytimeSnapshot }) {
  const [state, setState] = useState<"idle" | "copied" | "error">("idle");
  if (!playtime?.readable || playtime.launches <= 0 || playtime.totalMillis <= 0) return null;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(createPlaytimeShareText(playtime));
      setState("copied");
    } catch {
      setState("error");
    }
  };
  return (
    <>
      <button className="button button--quiet button--compact" type="button" onClick={() => void copy()}>
        Copy playtime
      </button>
      <small aria-live="polite">
        {state === "copied" ? "Playtime summary copied." : state === "error" ? "Couldn’t copy playtime." : `${playtime.launches.toLocaleString()} recorded sessions · longest ${formatSessionContext(playtime.longestSessionMillis)}`}
      </small>
    </>
  );
}

function ResultFigure({ measurement }: { measurement: SpeedMeasurement }) {
  const result = startupComparisonPresentation(
    measurement.measurementOnlyMs,
    measurement.optimizedMs,
  );
  if (result.kind === "similar") {
    return <><span aria-hidden="true">≈</span><span>about the same</span></>;
  }
  return (
    <>
      {Math.abs(result.improvementPercent).toFixed(0)}%
      <span>{result.kind === "faster" ? "less startup time" : "more startup time"}</span>
    </>
  );
}

export function SpeedScoreboard({ standing, isReady, playtime, lastRun, hull, onOpenBenchmark }: SpeedScoreboardProps) {
  const { record } = standing;

  if (!record) {
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
          {typeof lastRun?.startupMillis === "number" ? <p className="scoreboard__last-launch">Last Preflight launch: {formatDuration(lastRun.startupMillis)} to the menu.</p> : null}
          <p className="scoreboard__prompt">Starsector opens twice so Preflight can compare launch times.</p>
          <div className="scoreboard__actions">
            <button className="button button--primary" type="button" onClick={onOpenBenchmark} disabled={!isReady}><GaugeIcon />Measure speed<ArrowIcon /></button>
            <PlaytimeCopyButton playtime={playtime} />
          </div>
        </div>
      </section>
    );
  }

  const best = record.personalBest;
  const latest = record.latest;
  const bestResult = startupComparisonPresentation(best.measurementOnlyMs, best.optimizedMs);
  const latestResult = startupComparisonPresentation(latest.measurementOnlyMs, latest.optimizedMs);
  const bestMeasuredOn = new Date(best.recordedAt);
  const latestMeasuredOn = new Date(latest.recordedAt);
  return (
    <section className="card scoreboard" aria-label="Startup speed">
      <FlightInstrument hull={hull} />
      <div className="scoreboard__headline">
        <p className="eyebrow">Personal best</p>
        <strong className="scoreboard__figure"><ResultFigure measurement={best} /></strong>
        <div className="scoreboard__split">
          <span><small>Normal</small>{formatDuration(best.measurementOnlyMs)}</span>
          <ArrowIcon aria-hidden="true" />
          <span className={bestResult.kind === "faster" ? "scoreboard__split--won" : undefined}><small>Preflight</small>{formatDuration(best.optimizedMs)}</span>
        </div>
        <RecordedPlaytime playtime={playtime} />
      </div>
      <div className="scoreboard__body">
        <div className="scoreboard__total">
          <strong>Latest benchmark: {latestResult.headline}</strong>
          <span>{formatDuration(latest.measurementOnlyMs)} normal → {formatDuration(latest.optimizedMs)} Preflight · {latestResult.detail}.</span>
          <small>Controlled comparison recorded {latestMeasuredOn.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}.</small>
        </div>
        <div className="scoreboard__actions">
          <button className="button button--quiet button--compact" type="button" onClick={onOpenBenchmark} disabled={!isReady}><GaugeIcon />Measure current setup</button>
          <PlaytimeCopyButton playtime={playtime} />
          <small>Personal best recorded {bestMeasuredOn.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}</small>
        </div>
      </div>
    </section>
  );
}
