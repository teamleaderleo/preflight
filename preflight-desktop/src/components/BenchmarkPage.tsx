import { useState } from "react";
import { createBenchmarkShareText } from "../benchmarkShare";
import { CheckIcon, CopyIcon } from "../icons";
import type { useDesktopAutomation } from "../useDesktopAutomation";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { formatBytes, shortPath } from "../uiFormat";
import { startupComparisonPresentation } from "../speedScoreboardFormat";
import type { AppStatus, DesktopBenchmarkMeasurementOverhead, NoticeTone } from "../types";

type AutomationState = ReturnType<typeof useDesktopAutomation>;

interface BenchmarkPageProps {
  message: string;
  messageTone: NoticeTone;
  status: AppStatus;
  isReady: boolean;
  preparing: boolean;
  operationBlocked: boolean;
  nativeBlockReason: string | null;
  automation: AutomationState;
  onOpenHelp: () => void;
}

/*
 * The benchmark is the answer to "prove it", which is a real errand and a rare one. It is
 * reached from Speed rather than from a primary navigation slot, and it no longer carries the
 * support export: recovery and measurement are different errands that happened to share a file.
 */
export function BenchmarkPage({
  message,
  messageTone,
  status,
  isReady,
  preparing,
  operationBlocked,
  nativeBlockReason,
  automation,
  onOpenHelp,
}: BenchmarkPageProps) {
  const [benchmarkCopyState, setBenchmarkCopyState] = useState<"idle" | "copied" | "error">("idle");
  const {
    desktopSmokeProbe,
    desktopSmokeProbeBusy,
    desktopSmokeRunDirectory,
    desktopBenchmarkComparison,
    desktopSmokeCancelling,
    desktopSmokeRunning,
    checkDesktopAutomation,
    runDesktopAutomation,
    stopDesktopAutomation,
  } = automation;
  const benchmarkBlocked = !isReady
    || preparing
    || operationBlocked
    || nativeBlockReason !== null
    || status === "launching"
    || status === "running";
  const benchmarkMetric = desktopBenchmarkComparison?.metrics.processToMainMenuMs;
  const copyBenchmarkResult = async () => {
    if (!benchmarkMetric) return;
    try {
      await navigator.clipboard.writeText(createBenchmarkShareText(benchmarkMetric));
      setBenchmarkCopyState("copied");
    } catch {
      setBenchmarkCopyState("error");
    }
  };
  const benchmarkCopied = benchmarkCopyState === "copied";
  return (
    <div className="settings-page benchmark-page">
      <NoticeBanner message={message} tone={messageTone} />

      <section className="card benchmark-card">
        <div>
          <div className="heading-with-info">
            <h2>Startup benchmark</h2>
            <InfoTip label="About the benchmark">Opens Starsector twice and times each launch at the main menu: first without Preflight optimizations, then with them. Preflight closes only the Starsector process it started.</InfoTip>
          </div>
          <p>{isReady
            ? "Runs Starsector twice, once normally and once with Preflight, then compares the launch times."
            : "Choose Starsector on Home before running the benchmark."}</p>
          {isReady || desktopSmokeRunDirectory ? (
            <small>
              {isReady ? "Expect several minutes. Starsector opens and closes on its own." : null}
              {isReady && desktopSmokeRunDirectory ? " " : null}
              {desktopSmokeRunDirectory ? `Saved to ${shortPath(desktopSmokeRunDirectory)}` : null}
            </small>
          ) : null}
        </div>
        <div className="benchmark-card__actions">
          {desktopSmokeRunning ? (
            <button className="button button--quiet button--benchmark" type="button" onClick={() => void stopDesktopAutomation()} disabled={desktopSmokeCancelling}>{desktopSmokeCancelling ? "Stopping…" : "Stop benchmark"}</button>
          ) : (
            <button
              className="button button--primary button--benchmark"
              type="button"
              onClick={() => {
                setBenchmarkCopyState("idle");
                if (desktopSmokeProbe?.probe.ready) void runDesktopAutomation();
                else void checkDesktopAutomation(true);
              }}
              disabled={desktopSmokeProbeBusy || benchmarkBlocked}
              aria-describedby={nativeBlockReason ? "benchmark-native-block" : undefined}
            >
              {desktopSmokeProbeBusy ? "Checking…" : desktopSmokeProbe && !desktopSmokeProbe.probe.ready ? "Check again" : "Run benchmark"}
            </button>
          )}
          {nativeBlockReason ? <small id="benchmark-native-block">{nativeBlockReason}</small> : null}
          {desktopSmokeProbe && !desktopSmokeProbe.probe.ready ? (
            <>
              <small>Benchmark files are missing. Reinstall Preflight or make a support file.</small>
              <button className="button button--quiet button--compact" type="button" onClick={onOpenHelp}>Open Help</button>
            </>
          ) : null}
        </div>
      </section>

      {desktopBenchmarkComparison?.available ? (
        <section className="card benchmark-results" aria-label="Latest benchmark result">
          <div className="card__heading">
            <div><p className="eyebrow">Latest comparison</p><h2>Normal launch → Preflight</h2></div>
            <CheckIcon className="settings-check" />
          </div>
          <div className="benchmark-results__grid">
            <BenchmarkResult label="Main menu" metric={benchmarkMetric} unit="time" />
          </div>
          <BenchmarkContext comparison={desktopBenchmarkComparison} />
          {benchmarkMetric ? (
            <div className="benchmark-card__actions">
              <button
                className="icon-button icon-button--small"
                type="button"
                aria-label={benchmarkCopied ? "Benchmark result copied" : "Copy benchmark result"}
                title={benchmarkCopied ? "Benchmark result copied" : "Copy measured startup times and installation qualifier"}
                onClick={() => void copyBenchmarkResult()}
              >
                {benchmarkCopied ? <CheckIcon /> : <CopyIcon />}
              </button>
              {benchmarkCopyState === "copied"
                ? <small aria-live="polite">Benchmark result copied.</small>
                : benchmarkCopyState === "error"
                  ? <small aria-live="polite">Couldn’t copy the benchmark result.</small>
                  : null}
            </div>
          ) : null}
          <small>The saved result includes the game and mod versions plus raw timings.</small>
        </section>
      ) : null}

    </div>
  );
}

function BenchmarkContext({ comparison }: { comparison: AutomationState["desktopBenchmarkComparison"] }) {
  const runtime = comparison?.context?.optimized;
  const storage = comparison?.context?.storage;
  const overheads = [
    comparison?.context?.measurementOverhead?.measurementOnly,
    comparison?.context?.measurementOverhead?.optimized,
  ].filter((value): value is DesktopBenchmarkMeasurementOverhead => value !== null && value !== undefined);
  const overhead = overheads.reduce(
    (worst, value) => !worst || value.routeSharePercent > worst.routeSharePercent ? value : worst,
    undefined as (typeof overheads)[number] | undefined,
  );
  if (!runtime && !storage && !overhead) return null;
  return (
    <div className="benchmark-results__context" aria-label="Benchmark context">
      {runtime ? <span><strong>{compactNumber(runtime.cacheHits)}</strong> cache hits</span> : null}
      {runtime ? <span><strong>{compactNumber(runtime.fallbacks)}</strong> fallback{runtime.fallbacks === 1 ? "" : "s"}</span> : null}
      {runtime && runtime.failures > 0 ? <span className="benchmark-results__warning"><strong>{compactNumber(runtime.failures)}</strong> optimization failure{runtime.failures === 1 ? "" : "s"}</span> : null}
      {storage ? <span><strong>{formatBytes(storage.bytes)}</strong> total prepared data</span> : null}
      {overhead ? <span className={!overhead.withinBudget ? "benchmark-results__warning" : undefined}><strong>{overhead.routeSharePercent.toFixed(2)}%</strong> benchmark overhead</span> : null}
      {runtime?.memoryAvailablePercent !== null && runtime?.memoryAvailablePercent !== undefined
        ? <span className={runtime.memoryAvailablePercent < 10 ? "benchmark-results__warning" : undefined}><strong>{runtime.memoryAvailablePercent}%</strong> memory available</span>
        : null}
    </div>
  );
}

function compactNumber(value: number): string {
  return new Intl.NumberFormat(undefined, {
    notation: value >= 10_000 ? "compact" : "standard",
    maximumFractionDigits: value >= 1_000_000 ? 1 : 0,
  }).format(value);
}

export function BenchmarkResult({
  label,
  metric,
  unit,
}: {
  label: string;
  metric: { measurementOnly: number; optimized: number; improvementPercent: number | null } | undefined;
  unit: "time" | "fps";
}) {
  if (!metric) return null;
  const format = (value: number) => unit === "time"
    ? `${(value / 1_000).toFixed(2)}s`
    : value.toFixed(1);
  const comparison = unit === "time"
    ? startupComparisonPresentation(metric.measurementOnly, metric.optimized)
    : null;
  return (
    <div>
      <span>{label}</span>
      <strong>{format(metric.optimized)}</strong>
      <small>
        {format(metric.measurementOnly)} before
        {comparison ? ` · ${comparison.headline} · ${comparison.detail}` : metric.improvementPercent === null ? "" : ` · ${metric.improvementPercent.toFixed(1)}% change`}
      </small>
    </div>
  );
}
