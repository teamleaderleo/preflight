import assert from "node:assert/strict";
import { mkdirSync, writeFileSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { verifyRendererStorageOwnership } from "./verify-renderer-storage-ownership.mjs";

function write(root, path, contents) {
  const target = join(root, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, contents);
}

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "preflight-renderer-storage-"));
  write(root, "src/desktopStorage.ts", `
    export const THEME_STORAGE_KEY = "preflight.theme";
    export const PALETTE_STORAGE_KEY = "preflight.palette";
    export const SPEED_RECORD_STORAGE_KEY = "preflight.speedRecord";
    export const PREFLIGHT_LOCAL_STORAGE_KEYS = Object.freeze([
      THEME_STORAGE_KEY,
      PALETTE_STORAGE_KEY,
      SPEED_RECORD_STORAGE_KEY,
    ] as const);
  `);
  write(root, "src/useTheme.ts", `
    import { THEME_STORAGE_KEY } from "./desktopStorage";
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
  `);
  write(root, "public/theme-init.js", `
    window.localStorage.getItem("preflight.theme");
    window.localStorage.getItem("preflight.palette");
  `);
  return root;
}

test("the checked-in renderer has one owner for every persistent key", () => {
  const report = verifyRendererStorageOwnership();
  assert.ok(report.files > 0);
  assert.ok(report.keys > 0);
  assert.equal(report.themeKey, "preflight.theme");
  assert.equal(report.paletteKey, "preflight.palette");
});

test("a private preflight key fails with its file and literal", async () => {
  const root = await fixture();
  try {
    write(root, "src/usePrivate.ts", `
      const PRIVATE_KEY = "preflight.privateSetting";
      window.localStorage.setItem(PRIVATE_KEY, "yes");
    `);
    assert.throws(
      () => verifyRendererStorageOwnership(root),
      /src\/usePrivate\.ts: renderer storage literal "preflight\.privateSetting" bypasses src\/desktopStorage\.ts/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("a raw localStorage argument fails even outside the preflight namespace", async () => {
  const root = await fixture();
  try {
    write(root, "src/usePrivate.ts", `window.localStorage.getItem("private-theme");`);
    assert.throws(
      () => verifyRendererStorageOwnership(root),
      /src\/usePrivate\.ts: literal localStorage key "private-theme" bypasses src\/desktopStorage\.ts/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("the pre-paint bootstrap must match the shared appearance keys exactly", async () => {
  const root = await fixture();
  try {
    write(root, "public/theme-init.js", `
      window.localStorage.getItem("preflight.theme-v2");
      window.localStorage.getItem("preflight.palette");
    `);
    assert.throws(
      () => verifyRendererStorageOwnership(root),
      /public\/theme-init\.js: pre-paint storage access must use exactly the reviewed appearance keys/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("declaring a key without adding it to removal fails closed", async () => {
  const root = await fixture();
  try {
    write(root, "src/desktopStorage.ts", `
      export const THEME_STORAGE_KEY = "preflight.theme";
      export const PALETTE_STORAGE_KEY = "preflight.palette";
      export const SPEED_RECORD_STORAGE_KEY = "preflight.speedRecord";
      export const PRIVATE_STORAGE_KEY = "preflight.privateSetting";
      export const PREFLIGHT_LOCAL_STORAGE_KEYS = Object.freeze([
        THEME_STORAGE_KEY,
        PALETTE_STORAGE_KEY,
        SPEED_RECORD_STORAGE_KEY,
      ] as const);
    `);
    assert.throws(
      () => verifyRendererStorageOwnership(root),
      /PRIVATE_STORAGE_KEY is missing from PREFLIGHT_LOCAL_STORAGE_KEYS/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
