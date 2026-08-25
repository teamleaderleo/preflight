import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
import type { SpeedStanding } from "../useSpeedRecord";
import { SpeedScoreboard } from "./SpeedScoreboard";

function standing(): SpeedStanding {
  return {
    record: {
      version: 3,
      personalBest: {
        profileFingerprint: "12".repeat(32),
        benchmarkIdentitySha256: "34".repeat(32),
        measurementOnlyMs: 100_000,
        optimizedMs: 20_000,
        recordedAt: "2026-08-17T10:00:00.000Z",
      },
      latest: {
        profileFingerprint: "56".repeat(32),
        benchmarkIdentitySha256: "78".repeat(32),
        measurementOnlyMs: 80_000,
        optimizedMs: 100_000,
        recordedAt: "2026-08-18T10:00:00.000Z",
      },
    },
    rememberBenchmark: vi.fn(),
    countFastLaunch: vi.fn(),
    forget: vi.fn(),
  };
}

test("keeps the personal best trophy while showing an unfavorable latest benchmark", () => {
  render(
    <SpeedScoreboard
      standing={standing()}
      isReady
      onOpenBenchmark={vi.fn()}
    />,
  );

  expect(screen.getByText("Personal best")).toBeInTheDocument();
  expect(screen.getByText("less startup time")).toBeInTheDocument();
  expect(screen.getByText("Latest benchmark: Slower")).toBeInTheDocument();
  expect(screen.getByText(/25\.0% more startup time/)).toBeInTheDocument();
  expect(screen.getByText("Optimizations off")).toBeInTheDocument();
  expect(screen.getByText("Optimizations on")).toBeInTheDocument();
  expect(screen.queryByText("Normal")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Measure current setup/ })).toBeEnabled();
  expect(screen.queryByText(/matching launches/)).not.toBeInTheDocument();
});

test("unmeasured startup uses a neutral figure instead of implying a multiplier", () => {
  render(
    <SpeedScoreboard
      standing={{ ...standing(), record: null }}
      isReady
      onOpenBenchmark={vi.fn()}
    />,
  );

  expect(screen.getByText("—")).toBeInTheDocument();
  expect(screen.queryByText("?×")).not.toBeInTheDocument();
  expect(screen.getByText(/opens twice through Preflight/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Measure speed/ })).toBeEnabled();
});

test("keeps playtime session detail on hover and in the named accessible group while the copy utility stays icon-only", () => {
  render(
    <SpeedScoreboard
      standing={standing()}
      isReady
      playtime={{
        readable: true,
        totalMillis: 12_600_000,
        longestSessionMillis: 7_200_000,
        averageMillis: 4_200_000,
        launches: 3,
        sessionsWithoutDuration: 0,
        first: null,
        last: null,
      }}
      onOpenBenchmark={vi.fn()}
    />,
  );

  const playtime = screen.getByRole("group", { name: "3.5h recorded playtime across 3 sessions" });
  expect(playtime).toHaveAccessibleDescription("Longest session 2.0 hours");
  expect(playtime).toHaveAttribute(
    "title",
    "Across 3 recorded sessions · longest 2.0 hours",
  );
  expect(screen.getByRole("button", { name: "Copy playtime" })).toHaveAttribute("title", "Copy playtime summary");
  expect(screen.queryByText(/3 recorded sessions · longest/)).not.toBeInTheDocument();
  expect(screen.queryByText("Copy playtime")).not.toBeInTheDocument();
});
