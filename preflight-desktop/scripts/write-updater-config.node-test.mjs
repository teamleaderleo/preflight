import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { updaterRehearsalConfig, updaterReleaseConfig } from "./write-updater-config.mjs";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");

test("ordinary packages keep the updater plugin inert and valid", () => {
  const configuration = JSON.parse(readFileSync(resolve(desktopDirectory, "src-tauri/tauri.conf.json"), "utf8"));
  assert.deepEqual(configuration.plugins?.updater, { pubkey: "" });
});

test("release configuration requires and embeds the updater public key", () => {
  assert.deepEqual(updaterReleaseConfig(" public-key\n"), {
    bundle: { createUpdaterArtifacts: true },
    plugins: { updater: { pubkey: "public-key" } },
  });
  assert.throws(() => updaterReleaseConfig(""), /missing or invalid/);
});

test("rehearsal configuration pins an isolated build version", () => {
  assert.deepEqual(updaterRehearsalConfig("public-key", "0.1.1"), {
    bundle: { createUpdaterArtifacts: true },
    plugins: { updater: { pubkey: "public-key" } },
    version: "0.1.1",
  });
  assert.throws(() => updaterRehearsalConfig("public-key", "latest"), /must be SemVer/);
});
