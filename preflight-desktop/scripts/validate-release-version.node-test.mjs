import assert from "node:assert/strict";
import test from "node:test";
import { validateReleaseNotes, validateReleaseVersion } from "./validate-release-version.mjs";

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

test("requires reviewed release notes rather than a draft placeholder", () => {
  const source = "docs/releases/0.2.0.md";
  assert.doesNotThrow(() => validateReleaseNotes(
    "v0.2.0",
    "# Preflight 0.2.0\n\n## Summary\n\nReviewed release details.\n",
    source,
  ));
  assert.throws(
    () => validateReleaseNotes(
      "v0.2.0",
      "# Preflight 0.2.0\n\n> **Draft release notes.** Replace these before tagging.\n",
      source,
    ),
    /still has draft notes/,
  );
  assert.throws(
    () => validateReleaseNotes("v0.2.0", "  \n", source),
    /missing reviewed notes/,
  );
});

test("historical discussion of drafts outside the release preamble is allowed", () => {
  const notes = [
    "# Preflight 0.2.0",
    "",
    "## Summary",
    "",
    "x".repeat(1100),
    "",
    "Historical note: draft release notes used to live here during development.",
  ].join("\n");
  assert.doesNotThrow(() => validateReleaseNotes("v0.2.0", notes, "docs/releases/0.2.0.md"));
});
