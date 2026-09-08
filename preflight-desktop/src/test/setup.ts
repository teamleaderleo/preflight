import "@testing-library/jest-dom/vitest";
import { configure } from "@testing-library/react";
import { afterAll } from "vitest";

// App integration tests cross the same mocked native-read chain as the renderer. A one-second
// Testing Library polling ceiling makes worker scheduling look like a product failure, while the
// individual Vitest timeout remains the actual hang guard.
configure({ asyncUtilTimeout: 3_000 });

installWebStorageFromJsdom();

/**
 * Node 25+ defines placeholder `localStorage`/`sessionStorage` getters on its own global (they
 * stay `undefined` until `--localstorage-file` is passed). Vitest's jsdom environment only copies
 * window properties that are absent from the Node global, so on those runtimes every
 * `window.localStorage.getItem(...)` in the renderer and its tests throws instead of reaching
 * jsdom. The supported toolchain is the pinned `.node-version`, but a newer default Node shouldn't
 * turn 180+ green tests red for a reason unrelated to the product.
 *
 * The environment exposes its JSDOM instance as `globalThis.jsdom`, so this borrows Storage from
 * the same window the rest of the DOM globals come from. Tests that spy on `Storage.prototype`
 * keep working because it is the identical jsdom object, not a polyfill.
 */
function installWebStorageFromJsdom(): void {
  const dom = (globalThis as { jsdom?: { window: Window } }).jsdom;
  if (!dom) return;
  const restores: Array<() => void> = [];
  for (const key of ["localStorage", "sessionStorage"] as const) {
    const original = Object.getOwnPropertyDescriptor(globalThis, key);
    Object.defineProperty(globalThis, key, {
      value: dom.window[key],
      writable: true,
      configurable: true,
    });
    restores.push(() => {
      if (original) Object.defineProperty(globalThis, key, original);
      else delete (globalThis as Record<string, unknown>)[key];
    });
  }
  afterAll(() => {
    for (const restore of restores) restore();
  });
}
