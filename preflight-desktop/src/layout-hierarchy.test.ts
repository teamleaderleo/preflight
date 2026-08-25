import styles from "./styles.css?raw";
import readinessStyles from "./release-readiness.css?raw";
import gameSettingsLayoutStyles from "./game-settings-layout.css?raw";
import homePresentationStyles from "./homePresentation.css?raw";
import layoutStyles from "./layout-hierarchy.css?raw";

const cascade = [
  styles,
  readinessStyles,
  gameSettingsLayoutStyles,
  homePresentationStyles,
  layoutStyles,
].join("\n");

function installCascade() {
  const style = document.createElement("style");
  style.textContent = cascade;
  document.head.append(style);
  return style;
}

function homeState(state: "settled" | "preparation" | "recovery") {
  const console = document.createElement("section");
  console.className = `launch-console launch-console--ready launch-console--minimal launch-console--layout-${state}`;
  const primary = document.createElement("div");
  primary.className = "launch-console__primary";
  const instrument = document.createElement("div");
  instrument.className = "home-flight-instrument";
  const identity = document.createElement("div");
  identity.className = "home-launch-identity";
  const name = document.createElement("strong");
  name.textContent = "Main campaign";
  const path = document.createElement("span");
  path.className = "home-launch-path";
  path.tabIndex = 0;
  path.dataset.fullPath = "/Applications/Starsector";
  const pathShort = document.createElement("span");
  pathShort.className = "home-launch-path__short";
  pathShort.textContent = "~/Starsector";
  path.append(pathShort);
  identity.append(name, path);
  primary.append(instrument, identity);
  console.append(primary);
  return { console, primary, instrument, identity, path };
}

test("resolved Home cascade follows explicit state modifiers", () => {
  const style = installCascade();
  const viewport = document.createElement("main");
  viewport.className = "page-viewport page-viewport--home";
  const settled = homeState("settled");
  const preparation = homeState("preparation");
  const recovery = homeState("recovery");
  viewport.append(settled.console, preparation.console, recovery.console);
  document.body.append(viewport);

  // jsdom can report older declarations after parsing the modern production cascade. Pin those late
  // owners directly; real scrolling and pixel placement belong to the Chromium acceptance pass.
  expect(layoutStyles).toMatch(/\.page-viewport\.page-viewport--home\s*\{[^}]*position:\s*relative;[^}]*overflow-y:\s*auto;/s);
  expect(getComputedStyle(settled.instrument).inset).toBe("-34px 0 0");
  expect(getComputedStyle(settled.primary).display).toBe("block");
  expect(getComputedStyle(settled.instrument).position).toBe("absolute");
  expect(getComputedStyle(preparation.instrument).inset).toBe("6px 36px 104px");
  expect(getComputedStyle(recovery.console).minHeight).toBe("220px");
  expect(getComputedStyle(recovery.primary).minHeight).toBe("220px");
  expect(getComputedStyle(recovery.instrument).opacity).toBe("0.82");
  expect(layoutStyles).toMatch(/\.launch-console--layout-recovery \.home-launch-identity\s*\{[^}]*top:\s*16px;/s);

  viewport.remove();
  style.remove();
});

test("failed-run recovery is an overlay on settled Home geometry", () => {
  expect(layoutStyles).toMatch(
    /\.page-viewport--home > \.run-recovery\[role="alert"\]\s*\{[^}]*position:\s*absolute;[^}]*z-index:\s*8;[^}]*top:\s*0;[^}]*margin-bottom:\s*0;/s,
  );
  expect(layoutStyles).toMatch(
    /\.run-recovery\[role="alert"\] \+ \.launch-console--layout-recovery\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*100%;/s,
  );
  expect(layoutStyles).toMatch(
    /\.run-recovery\[role="alert"\] \+ \.launch-console--layout-recovery \.home-flight-instrument\s*\{[^}]*inset:\s*-34px 30px 100px;[^}]*opacity:\s*1;/s,
  );
  expect(layoutStyles).toMatch(
    /\.run-recovery\[role="alert"\] \+ \.launch-console--layout-recovery \.home-launch-identity\s*\{[^}]*top:\s*auto;[^}]*bottom:\s*82px;[^}]*left:\s*50%;[^}]*text-align:\s*center;[^}]*transform:\s*translateX\(-50%\);/s,
  );
  expect(layoutStyles).toMatch(
    /@media \(max-width: 720px\)[\s\S]*?\.run-recovery\[role="alert"\] \+ \.launch-console--layout-recovery \.home-launch-identity\s*\{[^}]*bottom:\s*88px;[^}]*width:\s*calc\(100% - 32px\);/s,
  );
  expect(layoutStyles).toMatch(
    /\.run-recovery\[role="alert"\] \+ \.launch-console--layout-recovery \.home-launch-path::after\s*\{[^}]*top:\s*auto;[^}]*bottom:\s*calc\(100% \+ 7px\);[^}]*left:\s*50%;[^}]*transform:\s*translate\(-50%, -2px\);/s,
  );
  expect(layoutStyles).not.toContain("inset: 120px 30px 54px");
  expect(layoutStyles).not.toContain("inset: 105px 8px 24px");
});

test("settled Home anchors the launch action while secondary controls adapt around it", () => {
  expect(layoutStyles).toMatch(
    /\.launch-console--layout-settled \.launch-console__primary\s*\{[^}]*position:\s*relative;[^}]*display:\s*block;/s,
  );
  expect(layoutStyles).toMatch(
    /\.launch-console--layout-settled \.home-ship-picker\s*\{[^}]*position:\s*absolute !important;[^}]*bottom:\s*22px;[^}]*left:\s*20px;/s,
  );
  expect(layoutStyles).toMatch(
    /\.launch-console--layout-settled \.launch-console__actions\s*\{[^}]*position:\s*absolute;[^}]*bottom:\s*20px;[^}]*left:\s*50%;[^}]*display:\s*grid;[^}]*grid-template-columns:\s*94px minmax\(260px, 520px\) 94px;[^}]*transform:\s*translateX\(-50%\);/s,
  );
  expect(layoutStyles).toMatch(
    /@container \(max-width: 1000px\)[\s\S]*?\.launch-console--layout-settled \.home-ship-picker\s*\{[^}]*bottom:\s*88px;[^}]*left:\s*50%;[^}]*transform:\s*translateX\(-50%\);/s,
  );
  expect(layoutStyles).toMatch(
    /@container \(max-width: 640px\)[\s\S]*?\.launch-console--layout-settled \.launch-console__actions\s*\{[^}]*bottom:\s*14px;[^}]*grid-template-columns:\s*minmax\(0, 1fr\);/s,
  );
  expect(layoutStyles).toMatch(
    /@container \(max-width: 640px\)[\s\S]*?\.launch-console--layout-settled \.home-ship-picker\s*\{[^}]*left:\s*10px;[^}]*transform:\s*none;/s,
  );
  expect(layoutStyles).toMatch(
    /@container \(max-width: 640px\)[\s\S]*?\.launch-console--layout-settled \.launch-console__actions \.home-motion-controls\s*\{[^}]*position:\s*absolute;[^}]*right:\s*0;[^}]*bottom:\s*72px;/s,
  );
  expect(styles).toMatch(
    /\.home-motion-toggle\s*\{[^}]*flex:\s*0 0 44px;[^}]*width:\s*44px;/s,
  );
  expect(layoutStyles).toMatch(
    /@container \(max-width: 640px\)[\s\S]*?\.home-motion-controls \.home-motion-toggle\s*\{[^}]*width:\s*44px;[^}]*min-height:\s*44px;/s,
  );
});

test("short windows scroll Home instead of clipping its launch action", () => {
  expect(layoutStyles).toMatch(
    /@media \(max-height: 520px\)[\s\S]*?\.launch-console--ready,[\s\S]*?\.launch-console--ready \.launch-console__primary\s*\{[^}]*height:\s*320px;[^}]*min-height:\s*320px;/s,
  );
});

test("launch identity keeps the setup first while the path remains an interactive disclosure", () => {
  const style = installCascade();
  const settled = homeState("settled");
  document.body.append(settled.console);

  expect(settled.identity.children[0].tagName).toBe("STRONG");
  expect(settled.identity.children[1]).toBe(settled.path);
  expect(getComputedStyle(settled.identity).flexDirection).toBe("column");
  expect(getComputedStyle(settled.path).pointerEvents).toBe("auto");
  expect(getComputedStyle(settled.path).fontSize).toBe("9px");
  expect(layoutStyles).toMatch(
    /\.launch-console--layout-settled \.home-launch-path::after\s*\{[^}]*top:\s*auto;[^}]*bottom:\s*calc\(100% \+ 7px\);/s,
  );
  expect(layoutStyles).not.toContain("column-reverse");
  expect(layoutStyles).not.toContain(":has(");

  settled.console.remove();
  style.remove();
});

test("prepared-data attention uses preparation composition while review actions stay available", () => {
  const style = installCascade();
  const cacheAttention = homeState("preparation");
  const options = document.createElement("button");
  options.className = "home-options-toggle";
  const actions = document.createElement("div");
  actions.className = "launch-console__actions";
  cacheAttention.primary.append(options, actions);
  document.body.append(cacheAttention.console);

  expect(cacheAttention.console).toHaveClass("launch-console--layout-preparation");
  expect(cacheAttention.console).not.toHaveClass("launch-console--layout-recovery");
  expect(getComputedStyle(options).display).not.toBe("none");
  expect(getComputedStyle(actions).display).not.toBe("none");

  cacheAttention.console.remove();
  style.remove();
});

test("Hide ship removes only ship controls without creating another Home composition", () => {
  expect(homePresentationStyles).toMatch(
    /:root\[data-home-mode="compact"\] \.launch-console--layout-settled \.home-flight-instrument/,
  );
  expect(homePresentationStyles).not.toMatch(/data-home-mode="compact"[^}]*\.home-playtime/);
  expect(layoutStyles).not.toMatch(/data-home-mode="compact"[^}]*height:/);
  expect(layoutStyles).not.toMatch(/data-home-mode="compact"[\s\S]*?grid-template/);
  expect(homePresentationStyles).not.toMatch(
    /:root\[data-home-mode="compact"\] \.launch-console--ready \.home-flight-instrument/,
  );
  expect(homePresentationStyles).not.toContain(":has(");
  expect(layoutStyles).toMatch(
    /:root\[data-home-mode="compact"\] \.launch-console--layout-settled \.home-launch-identity\s*\{[^}]*bottom:\s*92px;/s,
  );
  expect(layoutStyles).toMatch(
    /:root\[data-home-hud="idle"\] \.topbar--home \.topbar__actions\s*\{[^}]*opacity:\s*0;/s,
  );
});

test("compact rules exist for the required 720px review width", () => {
  const style = document.createElement("style");
  style.textContent = layoutStyles;
  document.head.append(style);
  const media = Array.from(style.sheet?.cssRules ?? []).find((rule) =>
    rule instanceof CSSMediaRule && rule.conditionText === "(max-width: 720px)",
  ) as CSSMediaRule | undefined;
  expect(media).toBeDefined();
  const selectors = Array.from(media?.cssRules ?? [])
    .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
    .map((rule) => rule.selectorText);
  expect(selectors).toContain(".launch-console--layout-settled.launch-console--minimal .home-flight-instrument");
  expect(selectors).toContain(".launch-console--layout-preparation .home-flight-instrument");
  expect(selectors).toContain(".launch-console--layout-recovery");
  expect(selectors).toContain('.page-viewport--home > .run-recovery[role="alert"] + .launch-console--layout-recovery');
  style.remove();
});
