import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
import type { WireframeHull } from "../types";
import type { SpeedStanding } from "../useSpeedRecord";
import { SpeedScoreboard } from "./SpeedScoreboard";

vi.mock("./FlightInstrument", () => ({ FlightInstrument: () => <div data-testid="instrument" /> }));

const hull: WireframeHull = {
  id: "test",
  name: "Test hull",
  hullSize: "FRIGATE",
  style: "MIDLINE",
  bounds: [],
  engines: [],
  mounts: [],
  featured: true,
};

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
      hull={hull}
      onOpenBenchmark={vi.fn()}
    />,
  );

  expect(screen.getByText("Personal best")).toBeInTheDocument();
  expect(screen.getByText("less startup time")).toBeInTheDocument();
  expect(screen.getByText("Latest benchmark: Slower")).toBeInTheDocument();
  expect(screen.getByText(/25\.0% more startup time/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Measure current setup/ })).toBeEnabled();
  expect(screen.queryByText(/matching launches/)).not.toBeInTheDocument();
});
