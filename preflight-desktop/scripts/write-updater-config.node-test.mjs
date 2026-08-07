import assert from "node:assert/strict";
import test from "node:test";
import { updaterRehearsalConfig, updaterReleaseConfig } from "./write-updater-config.mjs";

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
