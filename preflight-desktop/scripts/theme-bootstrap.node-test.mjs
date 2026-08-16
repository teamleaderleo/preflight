import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const indexUrl = new URL("../index.html", import.meta.url);
const bootstrapUrl = new URL("../public/theme-init.js", import.meta.url);
const tauriConfigUrl = new URL("../src-tauri/tauri.conf.json", import.meta.url);

test("pre-paint theme bootstrap stays compatible with the packaged CSP", async () => {
  const [index, bootstrap, tauriConfigText] = await Promise.all([
    readFile(indexUrl, "utf8"),
    readFile(bootstrapUrl, "utf8"),
    readFile(tauriConfigUrl, "utf8"),
  ]);
  const tauriConfig = JSON.parse(tauriConfigText);
  const csp = tauriConfig?.app?.security?.csp;

  assert.equal(typeof csp, "string", "Tauri CSP must remain explicit");
  assert.match(csp, /(?:^|;)\s*default-src\s+'self'(?:\s*;|$)/);
  assert.doesNotMatch(
    csp,
    /(?:^|;)\s*script-src[^;]*'unsafe-inline'/,
    "theme startup must not require weakening script-src",
  );

  assert.match(
    index,
    /<script\s+src=["']\/theme-init\.js["']\s*><\/script>/,
    "theme bootstrap must remain a blocking classic script in the head",
  );

  const scripts = [...index.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
  const executableInlineScripts = scripts.filter((match) => {
    const attributes = match[1];
    const body = match[2].trim();
    return !/\bsrc\s*=/.test(attributes) && body.length > 0;
  });
  assert.deepEqual(
    executableInlineScripts.map((match) => match[0]),
    [],
    "packaged index.html must not contain executable inline scripts",
  );

  assert.match(bootstrap, /localStorage\.getItem\(["']preflight\.theme["']\)/);
  assert.match(bootstrap, /localStorage\.getItem\(["']preflight\.palette["']\)/);
  assert.match(bootstrap, /document\.documentElement\.dataset\.theme/);
  assert.match(bootstrap, /document\.documentElement\.dataset\.palette/);
});
