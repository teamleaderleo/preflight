import { render, screen } from "@testing-library/react";
import { BenchmarkPage, BenchmarkResult } from "./BenchmarkPage";

test.each([
  ["faster", 100_000, 75_000, /Faster · 25\.0% less startup time/],
  ["similar", 100_000, 99_500, /About the same · 0\.5% difference/],
  ["slower", 80_000, 100_000, /Slower · 25\.0% more startup time/],
])("renders a %s startup comparison without celebratory fallback wording", (_name, measurementOnly, optimized, expected) => {
  render(
    <BenchmarkResult
      label="Main menu"
      metric={{ measurementOnly, optimized, improvementPercent: (1 - optimized / measurementOnly) * 100 }}
      unit="time"
    />,
  );

  expect(screen.getByText(expected)).toBeInTheDocument();
  expect(screen.queryByText(/% better/)).not.toBeInTheDocument();
});

test("benchmark unavailability uses stable recovery guidance instead of raw probe diagnostics", () => {
  const staleProbeDiagnostic = "This build can’t run the startup benchmark. Create a support ZIP below.";
  render(
    <BenchmarkPage
      message=""
      messageTone="info"
      status="ready"
      isReady
      preparing={false}
      operationBlocked={false}
      nativeBlockReason={null}
      onOpenHelp={() => undefined}
      automation={{
        desktopSmokeProbe: {
          protocol: 1,
          probe: { ready: false, driver: null, diagnostics: [staleProbeDiagnostic] },
        },
        desktopSmokeProbeBusy: false,
        desktopSmokeRunDirectory: null,
        desktopBenchmarkComparison: null,
        desktopSmokeCancelling: false,
        desktopSmokeRunning: false,
        checkDesktopAutomation: () => Promise.resolve(),
        runDesktopAutomation: () => Promise.resolve(),
        stopDesktopAutomation: () => Promise.resolve(),
      } as never}
    />,
  );

  expect(screen.getByText("Benchmark files are missing. Reinstall Preflight or make a support file.")).toBeInTheDocument();
  expect(screen.queryByText(staleProbeDiagnostic)).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Check again" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Open Help" })).toBeInTheDocument();
});

test("describes the benchmark as two Preflight launches with only optimizations changing", () => {
  render(
    <BenchmarkPage
      message=""
      messageTone="info"
      status="ready"
      isReady
      preparing={false}
      operationBlocked={false}
      nativeBlockReason={null}
      onOpenHelp={() => undefined}
      automation={{
        desktopSmokeProbe: null,
        desktopSmokeProbeBusy: false,
        desktopSmokeRunDirectory: null,
        desktopBenchmarkComparison: {
          available: true,
          metrics: {
            processToMainMenuMs: {
              measurementOnly: 100_000,
              optimized: 75_000,
              delta: -25_000,
              improvementPercent: 25,
            },
            stutterBurdenMillisPerSecond: {
              measurementOnly: 80,
              optimized: 40,
              delta: -40,
              improvementPercent: 50,
            },
            repeatedSlowFramesPercent: {
              measurementOnly: 5,
              optimized: 2.5,
              delta: -2.5,
              improvementPercent: 50,
            },
            slowFramesPerMinute: {
              measurementOnly: 180,
              optimized: 90,
              delta: -90,
              improvementPercent: 50,
            },
            onePercentLowFps: {
              measurementOnly: 14,
              optimized: 16,
              delta: 2,
              improvementPercent: 14.29,
            },
          },
        },
        desktopSmokeCancelling: false,
        desktopSmokeRunning: false,
        checkDesktopAutomation: () => Promise.resolve(),
        runDesktopAutomation: () => Promise.resolve(),
        stopDesktopAutomation: () => Promise.resolve(),
      } as never}
    />,
  );

  expect(screen.getByText(/It compares startup and, when the route reaches campaign, settled frame pacing/)).toBeInTheDocument();
  expect(screen.getByLabelText("About the benchmark")).toHaveAccessibleDescription(/Both runs use Preflight with the same installation and mod setup/);
  expect(screen.getByRole("heading", { name: "Optimizations off → on" })).toBeInTheDocument();
  expect(screen.getByText(/100\.00s with optimizations off/)).toBeInTheDocument();
  expect(screen.getByText("Settled campaign smoothness")).toBeInTheDocument();
  expect(screen.getByText("40.00 ms/s")).toBeInTheDocument();
  expect(screen.getByText(/80\.00 ms\/s with optimizations off · 50\.0% improvement/)).toBeInTheDocument();
  expect(screen.getByText("16.0 FPS")).toBeInTheDocument();
  expect(screen.getByText(/Recurring stutter ranks ahead/)).toBeInTheDocument();
  expect(screen.queryByText(/normal launch/i)).not.toBeInTheDocument();
});
