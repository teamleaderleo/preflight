import { installWebviewChrome, isBrowserAcceleratorKey, isBrowserContextMenu } from "./webviewChrome";

function contextMenuOn(target: Element): MouseEvent {
  const event = new MouseEvent("contextmenu", { bubbles: true, cancelable: true });
  Object.defineProperty(event, "target", { value: target });
  return event;
}

function key(init: KeyboardEventInit): KeyboardEvent {
  return new KeyboardEvent("keydown", { bubbles: true, cancelable: true, ...init });
}

afterEach(() => {
  document.body.innerHTML = "";
  window.getSelection()?.removeAllRanges();
  delete (window as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__;
});

test("page chrome loses the browser context menu while fields and selections keep theirs", () => {
  document.body.innerHTML = `
    <main><h1>Preflight</h1><input id="path" type="text" /><input id="toggle" type="checkbox" />
    <textarea id="notes"></textarea><div id="editor" contenteditable="true"><span id="inner">x</span></div></main>
  `;
  expect(isBrowserContextMenu(contextMenuOn(document.querySelector("h1")!))).toBe(true);
  expect(isBrowserContextMenu(contextMenuOn(document.getElementById("toggle")!))).toBe(true);
  expect(isBrowserContextMenu(contextMenuOn(document.getElementById("path")!))).toBe(false);
  expect(isBrowserContextMenu(contextMenuOn(document.getElementById("notes")!))).toBe(false);
  expect(isBrowserContextMenu(contextMenuOn(document.getElementById("inner")!))).toBe(false);

  const range = document.createRange();
  range.selectNodeContents(document.querySelector("h1")!);
  window.getSelection()!.addRange(range);
  expect(isBrowserContextMenu(contextMenuOn(document.querySelector("h1")!))).toBe(false);
});

test("browser accelerator keys are recognised without catching the app's own keys", () => {
  expect(isBrowserAcceleratorKey(key({ key: "F5" }))).toBe(true);
  expect(isBrowserAcceleratorKey(key({ key: "r", ctrlKey: true }))).toBe(true);
  expect(isBrowserAcceleratorKey(key({ key: "R", ctrlKey: true, shiftKey: true }))).toBe(true);
  expect(isBrowserAcceleratorKey(key({ key: "p", metaKey: true }))).toBe(true);
  expect(isBrowserAcceleratorKey(key({ key: "f", ctrlKey: true }))).toBe(true);
  expect(isBrowserAcceleratorKey(key({ key: "u", ctrlKey: true }))).toBe(true);

  expect(isBrowserAcceleratorKey(key({ key: "r" }))).toBe(false);
  expect(isBrowserAcceleratorKey(key({ key: "c", ctrlKey: true }))).toBe(false);
  expect(isBrowserAcceleratorKey(key({ key: "a", metaKey: true }))).toBe(false);
  expect(isBrowserAcceleratorKey(key({ key: "ArrowLeft" }))).toBe(false);
  expect(isBrowserAcceleratorKey(key({ key: "Tab" }))).toBe(false);
  expect(isBrowserAcceleratorKey(key({ key: "r", ctrlKey: true, altKey: true }))).toBe(false);
});

test("the packaged window cancels browser chrome events and the browser preview leaves them alone", () => {
  document.body.innerHTML = "<main><h1>Preflight</h1></main>";
  const heading = document.querySelector("h1")!;

  const previewStop = installWebviewChrome();
  const previewMenu = contextMenuOn(heading);
  heading.dispatchEvent(previewMenu);
  expect(previewMenu.defaultPrevented).toBe(false);
  previewStop();

  (window as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__ = {};
  const stop = installWebviewChrome();
  const menu = contextMenuOn(heading);
  heading.dispatchEvent(menu);
  expect(menu.defaultPrevented).toBe(true);

  const reload = key({ key: "F5" });
  heading.dispatchEvent(reload);
  expect(reload.defaultPrevented).toBe(true);

  const copy = key({ key: "c", ctrlKey: true });
  heading.dispatchEvent(copy);
  expect(copy.defaultPrevented).toBe(false);

  stop();
  const afterStop = contextMenuOn(heading);
  heading.dispatchEvent(afterStop);
  expect(afterStop.defaultPrevented).toBe(false);
});
