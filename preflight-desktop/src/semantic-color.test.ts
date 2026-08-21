import baseStyles from "./styles.css?raw";
import semanticStyles from "./semantic-color.css?raw";
import { afterEach, expect, test } from "vitest";

const palettes = ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"] as const;

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

afterEach(() => {
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.palette;
  document.head.querySelectorAll("style").forEach((style) => style.remove());
});

test("semantic status roles stay distinct from every palette accent", () => {
  installStyles();

  for (const theme of ["light", "dark"] as const) {
    for (const palette of palettes) {
      const tokens = rootTokens(theme, palette);
      const accent = tokens.getPropertyValue("--accent").trim();
      const attention = tokens.getPropertyValue("--attention").trim();
      const warning = tokens.getPropertyValue("--warning").trim();
      const success = tokens.getPropertyValue("--success").trim();

      expect(accent, `${theme}/${palette} accent`).not.toBe("");
      expect(attention, `${theme}/${palette} attention`).not.toBe("");
      expect(warning, `${theme}/${palette} warning`).not.toBe("");
      expect(success, `${theme}/${palette} success`).not.toBe("");
      expect(new Set([accent, attention, warning, success]).size, `${theme}/${palette} semantic separation`).toBe(4);
    }
  }
});

test("light contrast is derived from each active palette instead of hard-coded to Hangar", () => {
  expect(semanticStyles).toContain("--ink-soft: color-mix(in srgb, var(--ink) 71%, var(--paper-solid));");
  expect(semanticStyles).toContain("--ink-faint: color-mix(in srgb, var(--ink) 60%, var(--paper-solid));");
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
