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
});

test("navigation motion stays brief and the home illustration is structural", () => {
  expect(styles).toMatch(/\.page-viewport\s*\{[^}]*animation:\s*workspace-enter 180ms/s);
  expect(styles).toContain("@keyframes workspace-enter");
  expect(styles).toContain("@keyframes flight-plot-in");
  expect(styles).toMatch(/\.flight-plot\s*\{[^}]*pointer-events:\s*none;[^}]*animation:\s*flight-plot-in 520ms/s);
});

test("the primary palette stays blue rather than blue-green", () => {
  expect(styles).toContain("--accent: #6079ad");
  expect(styles).toContain("--accent-strong: #425f98");
  expect(styles).toMatch(/:root\[data-theme="dark"\]\s*\{[^}]*--accent-strong:\s*#8fa8dd;/s);
  expect(styles).not.toContain("#3b8493");
  expect(styles).not.toContain("#246d7a");
});

test("active controls look active without relying on gradients", () => {
  expect(styles).toContain("--action: #4f69c5");
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
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 1001px\)[\s\S]*?\.page-viewport--home\s*\{[^}]*overflow-y:\s*auto;/);
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
