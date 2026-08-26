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
