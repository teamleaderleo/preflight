import assert from "node:assert/strict";
import test from "node:test";
import { updaterReleaseConfig } from "./write-updater-config.mjs";

test("release configuration requires and embeds the updater public key", () => {
  assert.deepEqual(updaterReleaseConfig(" public-key\n"), {
    bundle: { createUpdaterArtifacts: true },
    plugins: { updater: { pubkey: "public-key" } },
  });
  assert.throws(() => updaterReleaseConfig(""), /missing or invalid/);
});
