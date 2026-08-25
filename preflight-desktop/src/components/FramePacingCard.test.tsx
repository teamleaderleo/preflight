import { render, screen, within } from "@testing-library/react";
import { expect, test } from "vitest";
import { FramePacingCard } from "./FramePacingCard";

test("shows the settled campaign and combat distributions without raw frame details", () => {
  render(<FramePacingCard framePacing={{
    format: "starsector-preflight-frame-pacing-summary-v1",
    campaign: null,
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

  const campaign = screen.getByRole("group", { name: "Campaign after warm-up" });
  expect(within(campaign).getByText("55.5 FPS")).toBeInTheDocument();
  expect(within(campaign).getByText("20.5 FPS")).toBeInTheDocument();
  expect(within(campaign).getByText("27.1 ms")).toBeInTheDocument();
  expect(screen.getByRole("group", { name: "Combat" })).toHaveTextContent("60 FPS");
  expect(screen.getByText(/never reads or writes a save/i)).toBeInTheDocument();
});

test("stays absent until a valid distribution was recorded", () => {
  const { container } = render(<FramePacingCard framePacing={null} />);
  expect(container).toBeEmptyDOMElement();
});
