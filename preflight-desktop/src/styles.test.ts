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

test("focus and pointer targets cover every native desktop control", () => {
  expect(styles).toContain(":where(button, input, select, summary, a):focus-visible");
  expect(styles).toMatch(/button\s*\{[^}]*min-height:\s*44px;/s);
  expect(styles).toMatch(/\.icon-button\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s);
  expect(styles).toMatch(/\.text-button\s*\{[^}]*min-height:\s*44px;/s);
});
