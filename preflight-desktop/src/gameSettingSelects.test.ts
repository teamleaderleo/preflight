import { describe, expect, it, afterEach } from "vitest";
import { displayPixels, resolutionChoices } from "./components/GameSettingSelects";
import { maximumUiScale } from "./uiFormat";

/**
 * The resolution list and the UI scale ceiling it feeds.
 *
 * The launcher offers whatever the panel can do. Reading `screen.width` instead of the panel gave a
 * Retina Mac a list that stopped at its scaled desktop, and the scale ceiling that follows from the
 * chosen resolution collapsed with it.
 */
describe("display pixels", () => {
  const screen = window.screen;
  const ratio = window.devicePixelRatio;

  afterEach(() => {
    Object.defineProperty(window, "screen", { value: screen, configurable: true });
    Object.defineProperty(window, "devicePixelRatio", { value: ratio, configurable: true });
  });

  function withDisplay(width: number, height: number, pixelRatio: number) {
    Object.defineProperty(window, "screen", { value: { width, height }, configurable: true });
    Object.defineProperty(window, "devicePixelRatio", { value: pixelRatio, configurable: true });
  }

  it("reports the panel behind a scaled Retina desktop", () => {
    withDisplay(1440, 932, 2);
    expect(displayPixels()).toEqual([2880, 1864]);
  });

  it("reports the panel behind fractional Windows scaling", () => {
    withDisplay(1280, 720, 1.5);
    expect(displayPixels()).toEqual([1920, 1080]);
  });

  it("leaves an unscaled display alone", () => {
    withDisplay(1920, 1080, 1);
    expect(displayPixels()).toEqual([1920, 1080]);
  });

  it("offers the modes the panel can drive, not the ones the desktop is scaled to", () => {
    withDisplay(1440, 932, 2);
    const [width, height] = displayPixels();
    const choices = resolutionChoices("1440x932", width, height);

    expect(choices).toContain("1920x1080");
    expect(choices).toContain("2560x1440");
    // The panel is 2880 x 1864, so a mode taller than that is still excluded.
    expect(choices).not.toContain("3840x2160");
  });

  it("lets the UI scale reach what the panel allows", () => {
    // What the scaled desktop used to allow, and what the panel behind it actually does.
    expect(maximumUiScale("1440x932")).toBeCloseTo(1.1, 5);
    expect(maximumUiScale("2880x1864")).toBeCloseTo(2.25, 5);
  });

  it("keeps the current resolution even when it exceeds the display", () => {
    withDisplay(1280, 720, 1);
    expect(resolutionChoices("3840x2160", 1280, 720)).toContain("3840x2160");
  });
});
