import { render, screen, within } from "@testing-library/react";
import { expect, test } from "vitest";
import { FramePacingCard } from "./FramePacingCard";

test("shows initial, settled, and combat distributions without raw frame details", () => {
  render(<FramePacingCard framePacing={{
    format: "starsector-preflight-frame-pacing-summary-v1",
    campaign: null,
    initialCampaign: {
      frames: 1_383,
      averageFps: 46.10,
      onePercentLowFps: 9.15,
      p95Micros: 44_500,
      p99Micros: 109_300,
    },
    settledCampaign: {
      frames: 4_091,
      averageFps: 55.47,
      onePercentLowFps: 20.45,
      p95Micros: 27_100,
      p99Micros: 48_900,
    },
    combat: {
      frames: 1_802,
      averageFps: 60,
      onePercentLowFps: 44.2,
      p95Micros: 18_000,
      p99Micros: 22_300,
    },
    measurementAverageMicros: 1.78,
  }} />);

  const initialCampaign = screen.getByRole("group", { name: "Campaign first 30 seconds" });
  expect(within(initialCampaign).getByText("46.1 FPS")).toBeInTheDocument();
  expect(within(initialCampaign).getByText("9.2 FPS")).toBeInTheDocument();
  const campaign = screen.getByRole("group", { name: "Campaign after 30 seconds" });
  expect(within(campaign).getByText("55.5 FPS")).toBeInTheDocument();
  expect(within(campaign).getByText("20.5 FPS")).toBeInTheDocument();
  expect(within(campaign).getByText("27.1 ms")).toBeInTheDocument();
  expect(screen.getByRole("group", { name: "Combat" })).toHaveTextContent("60 FPS");
  expect(screen.getByText(/recording cost averaged/i)).toHaveTextContent("1.78 μs per frame");
  expect(screen.getByText(/not a game-speed comparison/i)).toBeInTheDocument();
  expect(screen.getByText(/two periods from this same session/i)).toBeInTheDocument();
  expect(screen.getByText(/never reads or writes a save/i)).toBeInTheDocument();
});

test("omits recorder-cost copy when an older summary has no overhead measurement", () => {
  render(<FramePacingCard framePacing={{
    format: "starsector-preflight-frame-pacing-summary-v1",
    campaign: {
      frames: 500,
      averageFps: 50,
      onePercentLowFps: 30,
      p95Micros: 22_000,
      p99Micros: 33_000,
    },
    settledCampaign: null,
    combat: null,
    measurementAverageMicros: null,
  }} />);

  expect(screen.queryByText(/recording cost/i)).not.toBeInTheDocument();
  expect(screen.getByText(/never reads or writes a save/i)).toBeInTheDocument();
});

test("stays absent until a valid distribution was recorded", () => {
  const { container } = render(<FramePacingCard framePacing={null} />);
  expect(container).toBeEmptyDOMElement();
});
