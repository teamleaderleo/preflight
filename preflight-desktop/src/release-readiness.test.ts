import readinessStyles from "./release-readiness.css?raw";

test("ready Home keeps recovery content scrollable and launch controls clear of options", () => {
  expect(readinessStyles).toMatch(/\.page-viewport--home:has\(\.launch-console--ready\)\s*\{[^}]*overflow-y:\s*auto;/s);
  expect(readinessStyles).toMatch(/\.launch-console--options-open \.quick-settings\s*\{[^}]*bottom:\s*118px;[^}]*max-height:\s*none;/s);
  expect(readinessStyles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.launch-console--options-open \.quick-settings\s*\{[^}]*bottom:\s*124px;/s);
  expect(readinessStyles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.launch-console--ready \.last-run-health\s*\{[^}]*bottom:\s*auto;/s);
});

test("restored installed-hull controls stay bounded at desktop and compact widths", () => {
  expect(readinessStyles).toMatch(/\.hull-picker__list\s*\{[^}]*max-height:\s*152px;[^}]*overflow-y:\s*auto;/s);
  expect(readinessStyles).toMatch(/\.hangar-dock--catalog \.hangar-dials\s*\{[^}]*repeat\(auto-fit, minmax\(108px, 1fr\)\)/s);
  expect(readinessStyles).toMatch(/@container \(max-width: 760px\)[\s\S]*?\.hangar-dock--catalog\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto;/s);
});
