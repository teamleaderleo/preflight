import { isDesktopHost } from "./bridge";

/*
 * The packaged window is a system webview, and each engine ships browser habits that have no
 * place in a launcher: a right-click menu offering Reload and web search, and on WebView2 the
 * browser accelerator keys (F5 reload, Ctrl+P print, Ctrl+F find bar, Ctrl+U view source). A
 * stray click or key in front of an audience would reload the renderer or open a print dialog.
 * Editable fields and selected text keep their menus so copy, paste, and look-up still work.
 */

const BROWSER_KEY_COMBINATIONS = new Set([
  "F5",
  "F3",
  "ctrl+r",
  "ctrl+shift+r",
  "ctrl+p",
  "ctrl+f",
  "ctrl+g",
  "ctrl+s",
  "ctrl+o",
  "ctrl+u",
]);

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  if (target instanceof HTMLTextAreaElement) return true;
  if (target instanceof HTMLInputElement) {
    return !["button", "checkbox", "radio", "range", "submit", "reset", "file", "color"].includes(target.type);
  }
  return target.closest("[contenteditable]:not([contenteditable='false'])") !== null;
}

function hasTextSelection(): boolean {
  const selection = window.getSelection();
  return selection !== null && !selection.isCollapsed && selection.toString().length > 0;
}

export function isBrowserContextMenu(event: MouseEvent): boolean {
  return !isEditable(event.target) && !hasTextSelection();
}

export function isBrowserAcceleratorKey(event: KeyboardEvent): boolean {
  if (event.altKey) return false;
  if (event.key === "F5" || event.key === "F3") return true;
  if (!(event.ctrlKey || event.metaKey)) return false;
  const combination = `ctrl+${event.shiftKey ? "shift+" : ""}${event.key.toLowerCase()}`;
  return BROWSER_KEY_COMBINATIONS.has(combination);
}

/**
 * Suppresses browser chrome behaviours inside the packaged window. The browser preview keeps the
 * engine's own menus and shortcuts, which developers use for inspection.
 */
export function installWebviewChrome(target: Document = document): () => void {
  if (!isDesktopHost()) return () => undefined;
  const onContextMenu = (event: MouseEvent) => {
    if (isBrowserContextMenu(event)) event.preventDefault();
  };
  const onKeyDown = (event: KeyboardEvent) => {
    if (isBrowserAcceleratorKey(event)) event.preventDefault();
  };
  target.addEventListener("contextmenu", onContextMenu);
  target.addEventListener("keydown", onKeyDown);
  return () => {
    target.removeEventListener("contextmenu", onContextMenu);
    target.removeEventListener("keydown", onKeyDown);
  };
}
