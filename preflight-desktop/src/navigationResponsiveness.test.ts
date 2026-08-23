import appSource from "./App.tsx?raw";
import shellSource from "./components/DesktopShell.tsx?raw";

test("the selected tab and its workspace share one immediate page identity", () => {
  expect(appSource).not.toContain("useDeferredValue");
  expect(appSource).not.toContain("workspacePage");
  expect(shellSource).not.toContain("workspacePage");
  expect(appSource).toContain('page === "speed"');
  expect(shellSource).toContain("page-viewport--${page}");
});
