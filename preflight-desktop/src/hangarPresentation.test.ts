import hangarPresentationStyles from "./hangarPresentation.css?raw";
import { expect, test } from "vitest";

test("light Hangar strengthens the active palette without touching dark stage tokens", () => {
  expect(hangarPresentationStyles).toMatch(
    /:root:not\(\[data-theme="dark"\]\) \.hangar-stage\s*\{[^}]*--instrument-line:\s*color-mix\([^;]+var\(--ink\)[^;]+var\(--accent\)[^;]+\);[^}]*--instrument-accent:\s*var\(--accent-strong\);/s,
  );
  expect(hangarPresentationStyles).not.toMatch(/:root\[data-theme="dark"\] \.hangar-stage/);
});

test("motion and tuning states spend the active palette on real interaction state", () => {
  expect(hangarPresentationStyles).toMatch(
    /\.hangar-motion-toggle\[data-motion="rotate"\]\s*\{[^}]*color:\s*var\(--accent-strong\);[^}]*box-shadow:/s,
  );
  expect(hangarPresentationStyles).toMatch(
    /\.hangar-direction-toggle\[data-motion="still"\]\s*\{[^}]*color:\s*var\(--ink-faint\);/s,
  );
  expect(hangarPresentationStyles).toMatch(
    /\.hangar-dial input:focus-visible\s*\{[^}]*outline:\s*2px solid color-mix\([^}]+var\(--accent\)/s,
  );
});
