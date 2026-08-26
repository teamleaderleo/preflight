import baseStyles from "./styles.css?raw";
import semanticStyles from "./semantic-color.css?raw";
import { afterEach, expect, test } from "vitest";

const palettes = ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"] as const;
type Rgb = [number, number, number];

function installStyles() {
  const style = document.createElement("style");
  style.textContent = `${baseStyles}\n${semanticStyles}`;
  document.head.append(style);
  return style;
}

function rootTokens(theme: "light" | "dark", palette: (typeof palettes)[number]) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.palette = palette;
  return getComputedStyle(document.documentElement);
}

function hexRgb(value: string): Rgb {
  expect(value).toMatch(/^#[0-9a-f]{6}$/i);
  return [1, 3, 5].map((offset) => Number.parseInt(value.slice(offset, offset + 2), 16) / 255) as Rgb;
}

function linear(channel: number) {
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
}

function relativeLuminance(value: string) {
  const [r, g, b] = hexRgb(value).map(linear) as Rgb;
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrastRatio(foreground: string, background: string) {
  const lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
  const darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
  return (lighter + 0.05) / (darker + 0.05);
}

afterEach(() => {
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.palette;
  document.head.querySelectorAll("style").forEach((style) => style.remove());
});

test("shared focus indicator clears non-text contrast in every palette and theme", () => {
  installStyles();

  for (const theme of ["light", "dark"] as const) {
    for (const palette of palettes) {
      const tokens = rootTokens(theme, palette);
      const accent = tokens.getPropertyValue("--accent").trim();
      const paper = tokens.getPropertyValue("--paper-solid").trim();
      expect(contrastRatio(accent, paper), `${theme}/${palette} focus contrast`).toBeGreaterThanOrEqual(3);
    }
  }

  expect(semanticStyles).toMatch(
    /:root\[data-theme\] :where\(button, input, select, summary, a\):focus-visible\s*\{[^}]*outline-color:\s*var\(--accent\);/s,
  );
});
