import assert from "node:assert/strict";
import test from "node:test";
import { validateReleaseVersion } from "./validate-release-version.mjs";

test("requires the tag and all desktop version sources to agree", () => {
  assert.equal(validateReleaseVersion("v0.2.0", "0.2.0", "0.2.0", "0.2.0"), "0.2.0");
  assert.throws(
    () => validateReleaseVersion("v0.2.0", "0.1.0", "0.2.0", "0.2.0"),
    /doesn't match/,
  );
  assert.throws(
    () => validateReleaseVersion("release-0.2.0", "0.2.0", "0.2.0", "0.2.0"),
    /v-prefixed SemVer/,
  );
});
