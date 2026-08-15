import { CheckIcon } from "../icons";
import type { useDesktopAutomation } from "../useDesktopAutomation";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { formatBytes, shortPath } from "../uiFormat";
import type { AppStatus, DesktopBenchmarkMeasurementOverhead, NoticeTone } from "../types";

type AutomationState = ReturnType<typeof useDesktopAutomation>;

interface BenchmarkPageProps {
  message: string;
  messageTone: NoticeTone;
  status: AppStatus;
  isReady: boolean;
  preparing: boolean;
  automation: AutomationState;
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
  automation,
}: BenchmarkPageProps) {
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
  const benchmarkBlocked = !isReady || preparing || status === "launching" || status === "running";
  return (
    <div className="settings-page benchmark-page">
      <NoticeBanner message={message} tone={messageTone} />

      <section className="card benchmark-card">
        <div>
          <div className="heading-with-info">
            <h2>Startup benchmark</h2>
            <InfoTip label="About the benchmark">Opens Starsector twice and times each launch at the main menu: first without Preflight optimizations, then with them. Preflight closes only the exact process it started.</InfoTip>
          </div>
          {/*
            * What this does used to live only in the tooltip beside the heading, so the one sentence
            * that explains why the game is about to open by itself was reachable by hover alone. A
            * benchmark that drives the machine unattended for minutes says so before it is started.
            */}
          <p>{isReady
            ? "Opens Starsector twice — once without Preflight, once with it — and times each at the main menu. Preflight closes only the process it started."
            : "Choose Starsector on Home before running the benchmark."}</p>
          {isReady ? <small>Expect several minutes. Starsector opens and closes on its own; leave the machine alone while it runs.</small> : null}
          {desktopSmokeRunDirectory ? <small>Latest evidence: {shortPath(desktopSmokeRunDirectory)}</small> : null}
        </div>
        <div className="benchmark-card__actions">
          {desktopSmokeRunning ? (
            <button className="button button--quiet button--benchmark" type="button" onClick={() => void stopDesktopAutomation()} disabled={desktopSmokeCancelling}>{desktopSmokeCancelling ? "Stopping…" : "Stop benchmark"}</button>
          ) : (
            <button className="button button--primary button--benchmark" type="button" onClick={() => desktopSmokeProbe?.probe.ready ? void runDesktopAutomation() : void checkDesktopAutomation(true)} disabled={desktopSmokeProbeBusy || benchmarkBlocked}>
              {desktopSmokeProbeBusy ? "Checking…" : desktopSmokeProbe && !desktopSmokeProbe.probe.ready ? "Check again" : "Run benchmark"}
            </button>
          )}
          {desktopSmokeProbe && !desktopSmokeProbe.probe.ready ? <small>{desktopSmokeProbe.probe.diagnostics[0] ?? "The startup benchmark isn’t available in this build."}</small> : null}
        </div>
      </section>

      {desktopBenchmarkComparison?.available ? (
        <section className="card benchmark-results" aria-label="Latest benchmark result">
          <div className="card__heading">
            <div><p className="eyebrow">Latest comparison</p><h2>Normal launch → Preflight</h2></div>
            <CheckIcon className="settings-check" />
          </div>
          <div className="benchmark-results__grid">
            <BenchmarkResult label="Main menu" metric={desktopBenchmarkComparison.metrics.processToMainMenuMs} unit="time" />
          </div>
          <BenchmarkContext comparison={desktopBenchmarkComparison} />
          <small>A paired run shows the difference on this setup. The saved receipt keeps exact identities and raw measurements.</small>
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
      {runtime ? <span><strong>{compactNumber(runtime.fallbacks)}</strong> safe fallback{runtime.fallbacks === 1 ? "" : "s"}</span> : null}
      {runtime && runtime.failures > 0 ? <span className="benchmark-results__warning"><strong>{compactNumber(runtime.failures)}</strong> contained failure{runtime.failures === 1 ? "" : "s"}</span> : null}
      {storage ? <span><strong>{formatBytes(storage.bytes)}</strong> total prepared data</span> : null}
      {overhead ? <span className={!overhead.withinBudget ? "benchmark-results__warning" : undefined}><strong>{overhead.routeSharePercent.toFixed(2)}%</strong> probe overhead</span> : null}
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

function BenchmarkResult({
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
  return (
    <div>
      <span>{label}</span>
      <strong>{format(metric.optimized)}</strong>
      <small>{format(metric.measurementOnly)} before{metric.improvementPercent === null ? "" : ` · ${metric.improvementPercent.toFixed(1)}% better`}</small>
    </div>
  );
}
