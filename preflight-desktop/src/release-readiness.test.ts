import readinessStyles from "./release-readiness.css?raw";

function installReadinessStyles(base = "") {
  const style = document.createElement("style");
  style.textContent = `${base}\n${readinessStyles}`;
  document.head.append(style);
  return style;
}

test("ordinary controls acknowledge a press before asynchronous state arrives", () => {
  expect(readinessStyles).toMatch(/button:not\(:disabled\):active,\s*summary:active\s*\{[^}]*transform:\s*translateY\(1px\);/s);
});

test("Home identity is profile-first with a focusable path disclosure", () => {
  expect(readinessStyles).toMatch(/\.home-launch-identity\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;/s);
  expect(readinessStyles).not.toContain("flex-direction: column-reverse");
  expect(readinessStyles).toMatch(/\.home-launch-path\s*\{[^}]*pointer-events:\s*auto;[^}]*cursor:\s*help;/s);
  expect(readinessStyles).toMatch(/\.home-launch-path:is\(:hover, :focus-visible\)::after\s*\{[^}]*opacity:\s*1;/s);
  expect(readinessStyles).toMatch(/\.home-launch-path\s*\{[^}]*font-size:\s*9px;/s);
});

test("explicit Home state modifiers own recovery and preparation composition", () => {
  expect(readinessStyles).not.toContain(".page-viewport--home:has(.run-recovery");
  expect(readinessStyles).not.toContain(".page-viewport--home:has(.notice--error)");

  const style = installReadinessStyles(".quick-settings { justify-content: center; }");

  const recovery = document.createElement("section");
  recovery.className = "launch-console launch-console--ready launch-console--layout-recovery";
  const status = document.createElement("div");
  status.className = "status-chip";
  const options = document.createElement("button");
  options.className = "home-options-toggle";
  const actions = document.createElement("div");
  actions.className = "launch-console__actions";
  const recoveryIdentity = document.createElement("div");
  recoveryIdentity.className = "home-launch-identity";
  recovery.append(status, options, actions, recoveryIdentity);
  document.body.append(recovery);

  expect(getComputedStyle(status).display).toBe("none");
  expect(getComputedStyle(options).display).toBe("none");
  expect(getComputedStyle(actions).display).toBe("none");
  expect(getComputedStyle(recoveryIdentity).top).toBe("64px");
  expect(getComputedStyle(recoveryIdentity).textAlign).toBe("left");

  const preparation = document.createElement("section");
  preparation.className = "launch-console launch-console--ready launch-console--layout-preparation launch-console--options-open";
  const playtime = document.createElement("div");
  playtime.className = "home-playtime";
  const health = document.createElement("div");
  health.className = "last-run-health";
  const preparationIdentity = document.createElement("div");
  preparationIdentity.className = "home-launch-identity";
  const quickSettings = document.createElement("div");
  quickSettings.className = "quick-settings";
  preparation.append(playtime, health, preparationIdentity, quickSettings);
  document.body.append(preparation);

  expect(getComputedStyle(playtime).display).toBe("none");
  expect(getComputedStyle(health).display).toBe("none");
  expect(getComputedStyle(preparationIdentity).top).toBe("64px");
  expect(getComputedStyle(quickSettings).top).toBe("108px");
  expect(getComputedStyle(quickSettings).justifyContent).toBe("safe center");

  const settled = document.createElement("section");
  settled.className = "launch-console launch-console--ready launch-console--layout-settled";
  const shipName = document.createElement("span");
  shipName.className = "home-ship-name";
  settled.append(shipName);
  document.body.append(settled);
  const shipStyle = getComputedStyle(shipName);
  expect(shipStyle.overflow).toBe("hidden");
  expect(shipStyle.textOverflow).toBe("ellipsis");
  expect(shipStyle.whiteSpace).toBe("nowrap");

  recovery.remove();
  preparation.remove();
  settled.remove();
  style.remove();
});

test("narrow exceptional states reserve Options space without inferring note layout", () => {
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
  expect(noteRule).toBeUndefined();
  expect(optionsRule?.style.bottom).toBe("200px");
  style.remove();
});

test("Hangar dial controls stay bounded at desktop and compact widths", () => {
  expect(readinessStyles).toMatch(/\.hangar-dock--catalog \.hangar-dials\s*\{[^}]*repeat\(auto-fit, minmax\(180px, 1fr\)\)/s);
  expect(readinessStyles).toMatch(/@container \(max-width: 760px\)[\s\S]*?\.hangar-dock--catalog\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto;/s);
});
