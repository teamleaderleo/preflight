import { describe, expect, it, afterEach } from "vitest";
import { battleSizePresets, displayPixels, resolutionChoices } from "./gameSettingOptions";
import { storageGroupLabel } from "./preparationOptions";
import { maximumUiScale } from "./uiFormat";
import type { LaunchSettings } from "./types";

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

  it.each([[4096, 2560], [3440, 1440], [2880, 1864]])("offers the native %ix%i mode even when it is not a common preset", (width, height) => {
    expect(resolutionChoices("1920x1080", width, height)).toContain(`${width}x${height}`);
  });

  it("does not offer an invalid detected display mode", () => {
    expect(resolutionChoices("1920x1080", 0, 0)).not.toContain("0x0");
    expect(resolutionChoices("1920x1080", 100000, 100000)).not.toContain("100000x100000");
  });

  it("keeps the current resolution even when it exceeds the display", () => {
    withDisplay(1280, 720, 1);
    expect(resolutionChoices("3840x2160", 1280, 720)).toContain("3840x2160");
  });
});

/**
 * The battle-size shortcuts.
 *
 * The numbers a player wants are the ones the installation actually names -- vanilla's 200/400 --
 * plus a few round steps above them. A mod that raises maxBattleSize moves the anchors, so the list
 * is derived rather than fixed. The numeric field accepts values beyond these shortcuts.
 */
describe("battle size presets", () => {
  function withLimits(limits: Partial<LaunchSettings["limits"]>): LaunchSettings {
    return {
      limits: {
        antialiasingSamples: [0],
        uiScaleMin: 1,
        uiScaleMax: 3,
        uiScaleStep: 0.05,
        battleSizeMin: 200,
        battleSizeDefault: 400,
        battleSizeMax: 400,
        battleSizeExtendedMax: 2_147_483_647,
        diagnostics: [],
        ...limits,
      },
    } as LaunchSettings;
  }

  it("names the installation's own anchors and the round steps above them", () => {
    expect(battleSizePresets(withLimits({}))).toEqual([
      { value: 200, label: "Minimum" },
      { value: 400, label: "Default" },
      { value: 600, label: "Larger" },
      { value: 1000, label: "Big" },
      { value: 1500, label: "Huge" },
      { value: 2000, label: "Extreme" },
    ]);
  });

  it("separates the vanilla ceiling from the default when a mod raises it", () => {
    const presets = battleSizePresets(withLimits({ battleSizeMax: 800 }));
    expect(presets).toContainEqual({ value: 400, label: "Default" });
    expect(presets).toContainEqual({ value: 800, label: "Vanilla max" });
  });

  it("never offers a value the settings command would refuse", () => {
    const presets = battleSizePresets(withLimits({
      battleSizeMin: 500,
      battleSizeDefault: 700,
      battleSizeMax: 700,
      battleSizeExtendedMax: 900,
    }));
    // The engine's old reported ceiling is compatibility data, not a product limit.
    expect(presets.map((preset) => preset.value)).toEqual([500, 600, 700, 1000, 1500, 2000]);
  });

  /**
   * An installation Preflight cannot read reports every battle-size limit as null -- run
   * `preflight launch-settings --json` with no --game to see it. A "Minimum" button holding 1 would
   * be inventing a game rule that no installation stated.
   */
  it("names only the steps when the installation stated no limits of its own", () => {
    const presets = battleSizePresets(withLimits({
      battleSizeMin: null,
      battleSizeDefault: null,
      battleSizeMax: null,
    }));
    expect(presets).toEqual([
      { value: 600, label: "Larger" },
      { value: 1000, label: "Big" },
      { value: 1500, label: "Huge" },
      { value: 2000, label: "Extreme" },
    ]);
  });
});

/**
 * Storage rows read as themselves.
 *
 * The engine's category ids -- "acceleration", "evidence" -- used to reach the breakdown raw,
 * which named the folder layout rather than what a player would lose by deleting it.
 */
describe("storage group labels", () => {
  it("explains the categories the engine reports", () => {
    expect(storageGroupLabel("acceleration").label).toBe("Prepared game data");
    expect(storageGroupLabel("evidence").detail).toMatch(/Launch timings/);
    expect(storageGroupLabel("discardable").label).toBe("Replaced cache data");
    expect(storageGroupLabel("configuration").label).toBe("Profiles and backups");
    expect(storageGroupLabel("application").label).toBe("Preflight itself");
  });

  it("still shows a category it has never heard of", () => {
    expect(storageGroupLabel("future-thing")).toEqual({ label: "future thing", detail: "" });
  });
});
