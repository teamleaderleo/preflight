import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  draftNotesAllowedForEnvironment,
  validateReleaseNotes,
  validateReleaseVersion,
} from "./validate-release-version.mjs";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

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

test("requires reviewed release notes rather than a draft marker or release placeholder", () => {
  const source = "docs/releases/0.2.0.md";
  const finalNotes = [
    "# Preflight 0.2.0",
    "",
    "Reviewed release details.",
    "",
    "See [Release readiness](../release-readiness.md) for post-tag evidence.",
  ].join("\n");
  const draft = "# Preflight 0.2.0\n\n> **Draft release notes.** Replace these before tagging.\n";

  assert.doesNotThrow(() => validateReleaseNotes("v0.2.0", finalNotes, source));
  assert.throws(
    () => validateReleaseNotes("v0.2.0", draft, source),
    /still has draft notes/,
  );

  for (const placeholder of [
    "[CANDIDATE BENCHMARK RESULT / HARDWARE / GAME / RUNTIME]",
    "[FINAL PACKAGE MATRIX]",
    "[FINAL PACKAGE / LIFECYCLE / REPORT / BENCHMARK RECEIPTS]",
    "[GITHUB SPONSORS URL]",
    "[FINAL APPROVED DISCLAIMER]",
  ]) {
    assert.throws(
      () => validateReleaseNotes("v0.2.0", `${finalNotes}\n${placeholder}\n`, source),
      /still has a release placeholder/,
      `public tag must reject ${placeholder}`,
    );
  }

  assert.doesNotThrow(
    () => validateReleaseNotes(
      "v0.2.0",
      `${draft}\n[CANDIDATE BENCHMARK RESULT / HARDWARE / GAME / RUNTIME]\n`,
      source,
      { allowDraft: true },
    ),
    "private candidate rehearsal may intentionally use draft release notes and placeholders",
  );
  assert.throws(
    () => validateReleaseNotes("v0.2.0", "  \n", source, { allowDraft: true }),
    /missing reviewed notes/,
  );
});

test("current 0.1.0 tag-owned notes are final and placeholder free", () => {
  const source = "docs/releases/0.1.0.md";
  const notes = readFileSync(resolve(repository, source), "utf8");
  assert.doesNotThrow(() => validateReleaseNotes("v0.1.0", notes, source));
});

test("allows draft notes only for a branch-dispatched private rehearsal", () => {
  assert.equal(draftNotesAllowedForEnvironment({
    GITHUB_EVENT_NAME: "workflow_dispatch",
    GITHUB_REF_TYPE: "branch",
  }), true);
  assert.equal(draftNotesAllowedForEnvironment({
    GITHUB_EVENT_NAME: "workflow_dispatch",
    GITHUB_REF_TYPE: "tag",
  }), false);
  assert.equal(draftNotesAllowedForEnvironment({
    GITHUB_EVENT_NAME: "push",
    GITHUB_REF_TYPE: "tag",
  }), false);
  assert.equal(draftNotesAllowedForEnvironment({}), false);
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
