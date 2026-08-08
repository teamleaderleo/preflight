import styles from "./styles.css?raw";

test("responsive layouts keep document scrolling locked to bounded workspaces", () => {
  expect(styles).toMatch(/body\s*\{[^}]*overflow:\s*hidden;/s);
  expect(styles).toMatch(/@media \(max-width: 900px\)[\s\S]*?\.app-shell\s*\{[^}]*height:\s*100dvh;[^}]*overflow:\s*hidden;/);
  expect(styles).toMatch(/@media \(max-width: 900px\)[\s\S]*?\.page-viewport,[\s\S]*?overflow-y:\s*auto;/);
});

test("system theme and motion preferences remain first-class", () => {
  expect(styles).toContain("@media (prefers-color-scheme: dark)");
  expect(styles).toContain("@media (prefers-reduced-motion: reduce)");
  expect(styles).toMatch(/prefers-reduced-motion:[\s\S]*?transition-duration:\s*0\.01ms !important;/);
});

test("wide, narrow, and short windows keep content inside the desktop shell", () => {
  expect(styles).toMatch(/\.main\s*\{[^}]*min-width:\s*0;/s);
  expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.launch-console\s*\{[^}]*grid-template-columns:\s*1fr;/);
  expect(styles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.home-overview\s*\{[^}]*grid-template-columns:\s*1fr;/);
  expect(styles).toMatch(/@media \(max-width: 780px\)[\s\S]*?\.brand,[\s\S]*?\.nav,[\s\S]*?\.sidebar__footer\s*\{[^}]*flex-shrink:\s*0;/);
  expect(styles).toMatch(/@media \(max-height: 720px\) and \(min-width: 901px\)[\s\S]*?\.page-viewport--home\s*\{[^}]*overflow-y:\s*auto;/);
});

test("supporting copy stays legible while dense evidence remains compact", () => {
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
});
