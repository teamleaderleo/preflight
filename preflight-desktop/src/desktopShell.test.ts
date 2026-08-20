import shellStyles from "./desktopShell.css?raw";
import desktopShellSource from "./components/DesktopShell.tsx?raw";

/*
 * jsdom does not evaluate media queries or perform layout. This test pins the authored shell
 * contract; the 720×560 workspace gain is verified separately in Chromium against built bytes.
 */
test("very short narrow windows compact shell chrome before page content", () => {
  expect(desktopShellSource).toContain('import "../desktopShell.css";');
  expect(shellStyles).toMatch(
    /@media \(max-height: 620px\) and \(max-width: 1000px\)[\s\S]*?\.app-shell \.sidebar\s*\{[^}]*min-height:\s*56px;[^}]*padding-top:\s*6px;[^}]*padding-bottom:\s*6px;/,
  );
  expect(shellStyles).toMatch(
    /@media \(max-height: 620px\) and \(max-width: 1000px\)[\s\S]*?\.app-shell \.main\s*\{[^}]*padding-top:\s*10px;[^}]*padding-bottom:\s*0;/,
  );
  expect(shellStyles).toMatch(
    /@media \(max-height: 620px\) and \(max-width: 1000px\)[\s\S]*?\.app-shell \.topbar\s*\{[^}]*margin-bottom:\s*6px;/,
  );
  expect(shellStyles).toMatch(
    /@media \(max-height: 620px\) and \(max-width: 1000px\)[\s\S]*?\.app-shell footer\s*\{[^}]*display:\s*none;/,
  );
});
