import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
// Windows checkouts hand back CRLF, so the structure checks below read one line ending everywhere.
const workflow = readFileSync(resolve(repository, ".github/workflows/package-lifecycle.yml"), "utf8")
  .replaceAll("\r\n", "\n");
const rehearsalNotesPath = resolve(repository, "docs/releases/0.1.1-rehearsal.md");

test("the lifecycle rehearsal builds two versions and installs the earlier one first", () => {
  // Building once and installing it twice would pass every assertion while rehearsing nothing, so
  // the two builds and the version move between them are the shape worth pinning.
  const earlier = workflow.indexOf("Build the version a player already has");
  const move = workflow.indexOf("set-release-version.mjs 0.1.1-rehearsal");
  const later = workflow.indexOf("Build the version they are upgrading to");
  const exercise = workflow.indexOf("exercise-package-lifecycle.mjs");
  assert.ok(earlier > 0 && move > earlier && later > move && exercise > later,
    "the rehearsal must build, move the version, build again, then exercise");
  assert.match(workflow, /"\$RUNNER_TEMP\/lifecycle\/older" \\\n\s+"\$RUNNER_TEMP\/lifecycle\/newer"/);
});

test("the rehearsal stays dispatch-only and asks for no write authority", () => {
  const header = workflow.slice(0, workflow.indexOf("\njobs:\n"));
  assert.match(header, /^on:\n {2}workflow_dispatch:\n/m);
  assert.doesNotMatch(header, /\n {2}(push|pull_request|schedule):/);
  assert.match(header, /permissions:\n {2}contents: read/);
});

test("every published system is rehearsed", () => {
  for (const system of ["linux", "windows", "macos"]) {
    assert.match(workflow, new RegExp(`system: ${system}\\b`), `${system} is not rehearsed`);
  }
  for (const bundle of ["deb", "nsis", "dmg"]) {
    assert.match(workflow, new RegExp(`bundles: ${bundle}\\b`), `${bundle} is not rehearsed`);
  }
});

test("the rehearsal version's notes exist and can never become a public release", () => {
  // set-release-version refuses a version with no reviewed notes. The workflow depends on these
  // notes existing, and the draft marker is what keeps a v0.1.1-rehearsal tag from publishing them.
  const notes = readFileSync(rehearsalNotesPath, "utf8");
  assert.match(notes.slice(0, 1024), /\bdraft release notes\b/i);
  assert.match(notes, /not a release/i);
});
