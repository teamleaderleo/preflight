import baseStyles from "./styles.css?raw";
import semanticStyles from "./semantic-color.css?raw";
import { afterEach, expect, test } from "vitest";

const palettes = ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"] as const;

type Rgb = [number, number, number];
type Lab = [number, number, number];

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

function oklab(value: string): Lab {
  const [r, g, b] = hexRgb(value).map(linear) as Rgb;
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function oklabDistance(left: string, right: string) {
  const a = oklab(left);
  const b = oklab(right);
  return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
}

afterEach(() => {
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.palette;
  document.head.querySelectorAll("style").forEach((style) => style.remove());
});

test("semantic status roles stay perceptually distinct from every palette accent", () => {
  installStyles();

  for (const theme of ["light", "dark"] as const) {
    for (const palette of palettes) {
      const tokens = rootTokens(theme, palette);
      const roles = {
        accent: tokens.getPropertyValue("--accent").trim(),
        attention: tokens.getPropertyValue("--attention").trim(),
        warning: tokens.getPropertyValue("--warning").trim(),
        success: tokens.getPropertyValue("--success").trim(),
      };

      for (const role of ["attention", "warning", "success"] as const) {
        expect(oklabDistance(roles.accent, roles[role]), `${theme}/${palette} accent vs ${role}`).toBeGreaterThanOrEqual(0.1);
      }
      expect(oklabDistance(roles.attention, roles.warning), `${theme}/${palette} attention vs warning`).toBeGreaterThanOrEqual(0.08);
      expect(oklabDistance(roles.attention, roles.success), `${theme}/${palette} attention vs success`).toBeGreaterThanOrEqual(0.08);
      expect(oklabDistance(roles.warning, roles.success), `${theme}/${palette} warning vs success`).toBeGreaterThanOrEqual(0.08);
    }
  }
});

test("light semantic foregrounds clear small-text contrast on their soft fields", () => {
  installStyles();

  for (const palette of palettes) {
    const tokens = rootTokens("light", palette);
    for (const role of ["attention", "warning", "success"] as const) {
      const foreground = tokens.getPropertyValue(`--${role}`).trim();
      const background = tokens.getPropertyValue(`--${role}-soft`).trim();
      expect(contrastRatio(foreground, background), `${palette} ${role} contrast`).toBeGreaterThanOrEqual(4.5);
    }
  }
});

test("light focus indicator clears non-text contrast against every palette paper", () => {
  installStyles();

  for (const palette of palettes) {
    const tokens = rootTokens("light", palette);
    const accent = tokens.getPropertyValue("--accent").trim();
    const paper = tokens.getPropertyValue("--paper-solid").trim();
    expect(contrastRatio(accent, paper), `${palette} focus contrast`).toBeGreaterThanOrEqual(3);
  }
  expect(semanticStyles).toMatch(/:focus-visible\s*\{[^}]*outline-color:\s*var\(--accent\);/s);
});

test("light contrast is derived from each active palette instead of hard-coded to Hangar", () => {
  expect(semanticStyles).toContain("--ink-soft: color-mix(in srgb, var(--ink) 71%, var(--paper-solid));");
  expect(semanticStyles).toContain("--ink-faint: color-mix(in srgb, var(--ink) 66%, var(--paper-solid));");
  expect(semanticStyles).toContain("--console-soft: color-mix(in srgb, var(--console-ink) 69%, var(--paper-solid));");
  expect(semanticStyles).toContain("--line-strong: color-mix(in srgb, var(--ink) 35%, transparent);");
});

test("shared statuses consume semantic roles without taking over Home or Hangar composition", () => {
  expect(semanticStyles).toMatch(/\.status-chip\s*\{[^}]*color:\s*var\(--attention\);[^}]*background:\s*var\(--attention-soft\);/s);
  expect(semanticStyles).toMatch(/\.notice--info\s*\{[^}]*color:\s*var\(--attention\);[^}]*background:\s*var\(--attention-soft\);/s);
  expect(semanticStyles).toMatch(/\.notice--success\s*\{[^}]*color:\s*var\(--success\);[^}]*background:\s*var\(--success-soft\);/s);
  expect(semanticStyles).toMatch(/\.notice--error,\s*\.notice--warning,\s*\.activation-warning\)\s*\{[^}]*color:\s*var\(--warning\);/s);
  expect(semanticStyles).not.toMatch(/launch-console|home-|hangar-/);
});
