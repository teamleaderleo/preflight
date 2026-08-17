import styles from "./styles.css?raw";

test("responsive layouts keep document scrolling locked to bounded workspaces", () => {
  expect(styles).toMatch(/body\s*\{[^}]*overflow:\s*hidden;/s);
  expect(styles).toMatch(/@media \(max-width: 1000px\)[\s\S]*?\.app-shell\s*\{[^}]*height:\s*100dvh;[^}]*overflow:\s*hidden;/);
  expect(styles).toMatch(/@media \(max-width: 1000px\)[\s\S]*?\.page-viewport,[\s\S]*?overflow-y:\s*auto;/);
});

test("the drafting surface supports explicit themes while motion preferences remain first-class", () => {
  expect(styles).toMatch(/:root\s*\{[^}]*color-scheme:\s*light;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\]\s*\{[^}]*color-scheme:\s*dark;/s);
  expect(styles).toMatch(/\.quick-control select\s*\{[^}]*color-scheme:\s*light;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\] \.quick-control select\s*\{[^}]*color-scheme:\s*dark;/s);
  expect(styles).toContain("@media (prefers-reduced-motion: reduce)");
  expect(styles).toMatch(/prefers-reduced-motion:[\s\S]*?transition-duration:\s*0\.01ms !important;/);
  expect(styles).toMatch(/prefers-reduced-motion:[\s\S]*?\.flight-instrument__drift\s*\{[^}]*animation:\s*none;/);
});

test("navigation motion stays brief and the home illustration is structural", () => {
  expect(styles).toMatch(/\.page-viewport--entering\s*\{[^}]*animation:\s*workspace-enter 80ms/s);
  expect(styles).toContain("@keyframes workspace-enter");
  expect(styles).toContain("@keyframes flight-plot-in");
  expect(styles).toMatch(/\.flight-plot\s*\{[^}]*pointer-events:\s*none;[^}]*animation:\s*flight-plot-in 520ms/s);
});

test("the Hangar reference colors are locked by exact value", () => {
  expect(styles).toMatch(/:root\s*\{[^}]*--ink:\s*#241d14;[^}]*--cream:\s*#efe8dc;[^}]*--paper-solid:\s*#f6f1e8;[^}]*--accent:\s*#9a6a24;[^}]*--accent-strong:\s*#7d5518;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\]\s*\{[^}]*--accent:\s*#c8944a;[^}]*--accent-strong:\s*#edc07a;[^}]*--ink:\s*#e6ded0;[^}]*--cream:\s*#100e0a;[^}]*--paper-solid:\s*#191510;/s);
});

test("every palette has an explicit swatch and structural accent", () => {
  expect(styles).toMatch(/:root\[data-palette="blueprint"\]\s*\{[^}]*--accent:\s*#6079ad;[^}]*--accent-strong:\s*#425f98;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\]\[data-palette="blueprint"\]\s*\{[^}]*--accent-strong:\s*#8fa8dd;/s);
  expect(styles).toMatch(/:root\[data-palette="phosphor"\]\s*\{[^}]*--accent:\s*#598759;[^}]*--accent-strong:\s*#3c6e39;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\]\[data-palette="phosphor"\]\s*\{[^}]*--accent-strong:\s*#88b788;/s);
  for (const name of ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"]) {
    expect(styles).toContain(`.palette-switch__swatch--${name}`);
  }
});

test("the wireframe instrument follows every palette in both themes", () => {
  for (const name of ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"]) {
    expect(styles).toMatch(new RegExp(`:root\\[data-palette="${name}"\\] :is\\(\\.scoreboard, \\.hangar-stage\\)\\s*\\{[^}]*--instrument-accent:`, "s"));
    expect(styles).toMatch(new RegExp(`:root\\[data-theme="dark"\\]\\[data-palette="${name}"\\] :is\\(\\.scoreboard, \\.hangar-stage\\)\\s*\\{[^}]*--instrument-accent:`, "s"));
  }
});

test("the grounds preserve the exact Hangar Light drafting surface", () => {
  expect(styles).toContain("--cream: #efe8dc");
  expect(styles).toContain("--paper-solid: #f6f1e8");
  expect(styles).toContain("--ink: #241d14");
  expect(styles).toMatch(/:root\[data-theme="dark"\]\s*\{[^}]*--cream:\s*#100e0a;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\]\s*\{[^}]*--paper-solid:\s*#191510;/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\]\s*\{[^}]*--ink:\s*#e6ded0;/s);
});

/*
 * Rotating the old blue palette toward gold landed the accent and the warning within a few degrees
 * of each other, which is the one collision a near-monochrome scheme cannot absorb: "this is a
 * link" and "this needs your attention" stop being distinguishable. Warning is pushed to rust and
 * success keeps a real green, so the check is that they are still nowhere near the accent hue.
 */
test("status colours stay separable from the structural gold", () => {
  expect(styles).toContain("--warning: #d55336");
  expect(styles).toContain("--success: #4f6b45");
  // Measured, not eyeballed: the shipped #b0631f sat 0.041 from the gold accent in OKLab, closer
  // than the pair this rule was written to reject. #d55336 is 0.119 from the accent and 0.127 from
  // the error red, which is the nearest thing to both that still reads as a warning.
});

test("active controls look active without relying on gradients", () => {
  expect(styles).toContain("--action: #9a6a24");
  expect(styles).toMatch(/\.button--primary\s*\{[^}]*background:\s*var\(--action\);/s);
  expect(styles).not.toMatch(/\.button--primary\s*\{[^}]*linear-gradient/s);
  expect(styles).toMatch(/\.simple-switch:has\(input:checked\)\s*\{[^}]*background:\s*var\(--accent-soft\);/s);
  expect(styles).toMatch(/:root\[data-theme="dark"\] :is\(\.simple-switch, \.profile-menu > summary\)/);
});

test("wide, narrow, and short windows keep content inside the desktop shell", () => {
  expect(styles).toMatch(/\.main\s*\{[^}]*min-width:\s*0;/s);
  expect(styles).toMatch(/@media \(min-width: 721px\)[\s\S]*?\.launch-console--configured\s*\{[^}]*grid-template-areas:[^}]*"battle memory antialiasing ui-scale"[^}]*grid-template-columns:\s*repeat\(4, minmax\(0, 1fr\)\);/);
  expect(styles).toMatch(/@media \(min-width: 721px\)[\s\S]*?\.launch-console--configured \.quick-settings,[\s\S]*?display:\s*contents;/);
  expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.launch-console\s*\{[^}]*grid-template-columns:\s*1fr;/);
  expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.home-overview\s*\{[^}]*grid-template-columns:\s*1fr;/);
  expect(styles).toMatch(/@media \(max-width: 780px\)[\s\S]*?\.brand,[\s\S]*?\.nav,[\s\S]*?\.sidebar__footer\s*\{[^}]*flex-shrink:\s*0;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.main\s*\{[^}]*padding-top:\s*20px;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.prepare-page\s*\{[^}]*gap:\s*10px;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.settings-page\s*\{[^}]*gap:\s*10px;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.settings-page \.removal-card\s*\{[^}]*margin-top:\s*0;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.help-page :is\(\.support-card, \.help-links-card\)\s*\{[^}]*padding-top:\s*21px;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.prepare-page \.scoreboard\s*\{[^}]*padding:\s*14px 22px;/);
  expect(styles).toMatch(/\.preferences-card > \.preference-block:only-child\s*\{[^}]*grid-column:\s*1 \/ -1;[^}]*grid-template-columns:\s*minmax\(220px, 0\.7fr\) minmax\(320px, 1\.3fr\);/s);
  expect(styles).toMatch(/@media \(max-width: 760px\)[\s\S]*?\.preferences-card,[\s\S]*?grid-template-columns:\s*1fr;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.home-fact > small\s*\{[^}]*display:\s*none;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.page-viewport--home\s*\{[^}]*overflow-y:\s*auto;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 681px\)[\s\S]*?\.hangar-stage\s*\{[^}]*min-height:\s*410px;/);
});

test("optimization presets stay readable at the default desktop width", () => {
  expect(styles).toMatch(/@media \(max-width: 1200px\)[\s\S]*?\.optimization-choices\s*\{[^}]*grid-template-columns:\s*1fr;/);
});

test("supporting copy stays legible while dense evidence remains compact", () => {
  expect(styles).toContain('--font-body: "IBM Plex Sans Variable"');
  expect(styles).toContain('--font-data: "B612 Mono"');
  expect(styles).toContain("--text-support: 14px");
  expect(styles).toMatch(/\.notice\s*\{[^}]*font-size:\s*var\(--text-support\);/s);
  expect(styles).toMatch(/\.report-review > p,[\s\S]*?font-size:\s*var\(--text-support\);/);
  expect(styles).toMatch(/\.report-facts strong,[\s\S]*?font-size:\s*13px;/);
  expect(styles).toMatch(/\.optimization-domain small\s*\{[^}]*font-size:\s*var\(--text-support\);/s);
  expect(styles).toMatch(/\.optimization-domain-card\s*\{[^}]*display:\s*flex;[^}]*padding:\s*24px;/s);
});

test("the sidebar mark keeps the app artwork legible at icon size", () => {
  expect(styles).toMatch(/\.brand__mark\s*\{[^}]*width:\s*44px;[^}]*border-radius:\s*10px;/s);
});

test("focus and pointer targets cover every native desktop control", () => {
  expect(styles).toContain(":where(button, input, select, summary, a):focus-visible");
  expect(styles).toMatch(/button\s*\{[^}]*min-height:\s*44px;/s);
  expect(styles).toMatch(/\.icon-button\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s);
  expect(styles).toMatch(/\.text-button\s*\{[^}]*min-height:\s*44px;/s);
  expect(styles).toMatch(/@media \(max-width: 780px\)[\s\S]*?\.nav__item\s*\{[^}]*width:\s*44px;/);
  expect(styles).toMatch(/\.page-title:focus-visible\s*\{[^}]*text-decoration-color:\s*var\(--accent\);/s);
  // The tooltip is portalled into body and positioned from JS, so hover and focus are handled in
  // InfoTip.tsx; the stylesheet only has to make the open state visible.
  expect(styles).toMatch(/\.info-tip__content\s*\{[^}]*position:\s*fixed;/s);
  expect(styles).toMatch(/\.info-tip__content--open\s*\{[^}]*visibility:\s*visible;/s);
});

/*
 * A palette rotates what the app is made of. What it must not do is let a status colour drift
 * into the accent: "this is a link" and "this needs your attention" have to stay distinguishable,
 * and the hangar palette already had to move its warning to rust for exactly that reason once the
 * accent became gold. So the rule is not that the palettes share a warning -- they do not -- it is
 * that in every one of them the warning and the accent are still far apart.
 */
test("no palette lets a status colour collide with its accent", () => {
  const hue = (value: string) => {
    const [red, green, blue] = [0, 2, 4].map((at) => {
      const channel = parseInt(value.slice(1 + at, 3 + at), 16) / 255;
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    const cube = (v: number) => Math.cbrt(v);
    const long = cube(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue);
    const medium = cube(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue);
    const short = cube(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue);
    const a = 1.9779984951 * long - 2.4285922050 * medium + 0.4505937099 * short;
    const b = 0.0259040371 * long + 0.7827717662 * medium - 0.8086757660 * short;
    return ((Math.atan2(b, a) * 180) / Math.PI + 360) % 360;
  };
  const apart = (one: number, two: number) => {
    const gap = Math.abs(one - two) % 360;
    return gap > 180 ? 360 - gap : gap;
  };

  for (const name of ["blueprint", "hangar", "ultraviolet", "airglow", "phosphor"]) {
    // Hangar has no `[data-palette]` block: it is what bare `:root` already is, and the other
    // four override it. The attribute is still stamped for all five so the switch is uniform.
    const selector = name === "hangar" ? ":root" : `:root\\[data-palette="${name}"\\]`;
    const block = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styles);
    expect(block, `no ${selector} block`).not.toBeNull();
    const read = (token: string) => {
      const value = new RegExp(`--${token}:\\s*(#[0-9a-f]{6});`).exec(block![1])?.[1];
      expect(value, `${selector} has no --${token}`).toBeTruthy();
      return hue(value!);
    };
    const accent = read("accent");
    expect(apart(accent, read("warning")), `${name}: warning too close to accent`)
      .toBeGreaterThan(25);
    expect(apart(accent, read("success")), `${name}: success too close to accent`)
      .toBeGreaterThan(25);
  }
});

test("every palette override declares every non-inheriting root token", () => {
  const rootMatch = /:root\s*\{([^}]*)\}/s.exec(styles);
  expect(rootMatch).not.toBeNull();
  const rootTokens = Array.from(rootMatch![1].matchAll(/--([a-z0-9-]+):/g), (match) => match[1]);
  const inherited = new Set(["font-body", "font-data", "font-display", "text-support"]);
  const paletteTokens = rootTokens.filter((token) => !inherited.has(token));
  expect(paletteTokens.length).toBeGreaterThan(30);

  for (const name of ["blueprint", "ultraviolet", "airglow", "phosphor"]) {
    const selector = `:root\\[data-palette="${name}"\\]`;
    const block = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styles);
    expect(block, `missing palette block for ${name}`).not.toBeNull();
    for (const token of paletteTokens) {
      expect(new RegExp(`--${token}:`).test(block![1]), `${name} is missing --${token}`).toBe(true);
    }
  }
});
