import assert from "node:assert/strict";
import test from "node:test";
import { validateReleaseVersion } from "./validate-release-version.mjs";

test("requires the tag and all shipped version sources to agree", () => {
  const sources = {
    "package.json": "0.2.0",
    "package-lock.json": "0.2.0",
    "tauri.conf.json": "0.2.0",
    "Cargo.toml": "0.2.0",
    "Cargo.lock": "0.2.0",
    "pom.xml": "0.2.0",
    "preflight-core/pom.xml": "0.2.0",
  };
  assert.equal(validateReleaseVersion("v0.2.0", sources), "0.2.0");
  assert.throws(
    () => validateReleaseVersion("v0.2.0", { ...sources, "pom.xml": "0.2.0-SNAPSHOT" }),
    /pom\.xml=0\.2\.0-SNAPSHOT/,
  );
  assert.throws(
    () => validateReleaseVersion("release-0.2.0", sources),
    /v-prefixed SemVer/,
  );
});
