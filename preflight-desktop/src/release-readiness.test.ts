import readinessStyles from "./release-readiness.css?raw";

function installReadinessStyles(base = "") {
  const style = document.createElement("style");
  style.textContent = `${base}\n${readinessStyles}`;
  document.head.append(style);
  return style;
}

test("ready Home keeps recovery content scrollable and launch controls clear of options", () => {
  expect(readinessStyles).toMatch(/\.page-viewport--home:has\(\.launch-console--ready\)\s*\{[^}]*overflow-y:\s*auto;/s);
  expect(readinessStyles).toMatch(/\.launch-console--options-open \.quick-settings\s*\{[^}]*bottom:\s*118px;[^}]*max-height:\s*none;/s);
  expect(readinessStyles).toMatch(/\.home-launch-identity\s*\{[^}]*bottom:\s*82px;[^}]*width:\s*min\(520px, calc\(100% - 48px\)\);/s);
  expect(readinessStyles).toMatch(/\.home-launch-identity span,\s*\.home-launch-identity strong\s*\{[^}]*text-overflow:\s*ellipsis;[^}]*white-space:\s*nowrap;/s);
  expect(readinessStyles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.launch-console--options-open \.quick-settings\s*\{[^}]*bottom:\s*124px;/s);
  expect(readinessStyles).toMatch(/@media \(max-width: 720px\)[\s\S]*?\.launch-console--ready \.last-run-health\s*\{[^}]*bottom:\s*auto;/s);
});

test("journey overrides keep first settings reachable and put actionable Home failures first", () => {
  const style = installReadinessStyles(".quick-settings { justify-content: center; }");
  const viewport = document.createElement("div");
  viewport.className = "page-viewport--home";
  const launch = document.createElement("section");
  launch.className = "launch-console launch-console--ready";
  const recovery = document.createElement("section");
  recovery.className = "run-recovery";
  const quickSettings = document.createElement("div");
  quickSettings.className = "quick-settings";
  launch.append(quickSettings);
  viewport.append(launch, recovery);
  document.body.append(viewport);

  expect(viewport.matches(".page-viewport--home:has(.run-recovery)")).toBe(true);
  expect(getComputedStyle(viewport).display).toBe("flex");
  expect(getComputedStyle(recovery).order).toBe("-1");
  expect(getComputedStyle(quickSettings).justifyContent).toBe("safe center");

  const errorViewport = document.createElement("div");
  errorViewport.className = "page-viewport--home";
  const errorLaunch = document.createElement("section");
  errorLaunch.className = "launch-console launch-console--ready";
  const error = document.createElement("div");
  error.className = "notice notice--error";
  errorViewport.append(errorLaunch, error);
  document.body.append(errorViewport);
  expect(errorViewport.matches(".page-viewport--home:has(.notice--error)")).toBe(true);
  expect(getComputedStyle(errorViewport).display).toBe("flex");
  expect(getComputedStyle(error).order).toBe("-1");

  const preparationLaunch = document.createElement("section");
  preparationLaunch.className = "launch-console launch-console--ready";
  const playtime = document.createElement("div");
  playtime.className = "home-playtime";
  const identity = document.createElement("div");
  identity.className = "home-launch-identity";
  const note = document.createElement("div");
  note.className = "launch-console__note";
  preparationLaunch.append(playtime, identity, note);
  document.body.append(preparationLaunch);
  expect(preparationLaunch.matches(".launch-console--ready:has(.launch-console__note)")).toBe(true);
  expect(getComputedStyle(playtime).display).toBe("none");
  expect(getComputedStyle(identity).top).toBe("64px");
  expect(getComputedStyle(identity).textAlign).toBe("left");

  viewport.remove();
  errorViewport.remove();
  preparationLaunch.remove();
  style.remove();
});

test("narrow exceptional states reserve the recovery rows before the options disclosure", () => {
  const style = installReadinessStyles();
  const media = Array.from(style.sheet?.cssRules ?? []).find((rule) =>
    rule instanceof CSSMediaRule && rule.conditionText === "(max-width: 720px)",
  ) as CSSMediaRule | undefined;
  expect(media).toBeDefined();
  const rules = Array.from(media?.cssRules ?? []).filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule);
  const noteRule = rules.find((rule) =>
    rule.selectorText === ".launch-console--ready:has(.launch-console__actions .launch-console__stop) .launch-console__note",
  );
  const optionsRule = rules.find((rule) =>
    rule.selectorText === ".launch-console--options-open:has(.launch-console__actions .launch-console__stop) .quick-settings",
  );
  expect(noteRule?.style.bottom).toBe("132px");
  expect(optionsRule?.style.bottom).toBe("200px");
  style.remove();
});

test("restored installed-hull controls stay bounded at desktop and compact widths", () => {
  expect(readinessStyles).toMatch(/\.hull-picker__list\s*\{[^}]*max-height:\s*152px;[^}]*overflow-y:\s*auto;/s);
  expect(readinessStyles).toMatch(/\.hangar-dock--catalog \.hangar-dials\s*\{[^}]*repeat\(auto-fit, minmax\(108px, 1fr\)\)/s);
  expect(readinessStyles).toMatch(/@container \(max-width: 760px\)[\s\S]*?\.hangar-dock--catalog\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto;/s);
});
