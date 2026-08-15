import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
// Windows checkouts hand back CRLF, so the checks below read one line ending everywhere.
const workflow = readFileSync(resolve(repository, ".github/workflows/package-lifecycle.yml"), "utf8")
  .replaceAll("\r\n", "\n");
const rehearsalNotesPath = resolve(repository, "docs/releases/0.1.1-rehearsal.md");

function job(name, nextName) {
  const start = workflow.indexOf(`\n  ${name}:\n`);
  assert.notEqual(start, -1, `missing ${name} job`);
  const end = nextName ? workflow.indexOf(`\n  ${nextName}:\n`, start + 1) : workflow.length;
  assert.notEqual(end, -1, `missing ${nextName} job after ${name}`);
  return workflow.slice(start, end);
}

test("the lifecycle rehearsal builds each engine once before the platform matrix", () => {
  const engines = job("engines", "frontend");
  const lifecycle = job("lifecycle");

  const current = engines.indexOf("Build the installed-version engine once");
  const move = engines.indexOf("set-release-version.mjs 0.1.1-rehearsal");
  const rehearsal = engines.indexOf("Build the rehearsal-version engine once");
  assert.ok(current > 0 && move > current && rehearsal > move,
    "the engine job must build current, move the version, then build rehearsal");
  assert.equal((engines.match(/mvn --batch-mode/g) ?? []).length, 2,
    "only the two reviewed engine versions should compile");
  assert.match(engines, /preflight-lifecycle-engines-\$\{\{ github\.run_id \}\}/);
  assert.match(lifecycle, /needs: \[engines, frontend\]/);
  assert.match(lifecycle, /preflight-lifecycle-engines-\$\{\{ github\.run_id \}\}/);
  assert.doesNotMatch(lifecycle, /mvn --batch-mode/);
});

test("the lifecycle frontend is built once and reused by every package", () => {
  const frontend = job("frontend", "lifecycle");
  const lifecycle = job("lifecycle");

  assert.match(frontend, /needs: engines/);
  assert.equal((frontend.match(/npm run build/g) ?? []).length, 1);
  assert.match(frontend, /frontend-dist-manifest\.mjs write dist frontend-dist\.json/);
  assert.match(frontend, /preflight-lifecycle-frontend-\$\{\{ github\.run_id \}\}/);

  assert.match(lifecycle, /preflight-lifecycle-frontend-\$\{\{ github\.run_id \}\}/);
  assert.match(lifecycle, /frontend-dist-manifest\.mjs[\s\\]+verify preflight-desktop\/dist preflight-desktop\/frontend-dist\.json/);
  assert.match(lifecycle, /--config src-tauri\/tauri\.ci\.conf\.json/);
  assert.doesNotMatch(lifecycle, /npm run build/);
});

test("the lifecycle matrix packages the earlier version before the rehearsal version", () => {
  const lifecycle = job("lifecycle");
  const earlier = lifecycle.indexOf("Build the version a player already has");
  const move = lifecycle.indexOf("set-release-version.mjs 0.1.1-rehearsal");
  const later = lifecycle.indexOf("Build the version they are upgrading to");
  const exercise = lifecycle.indexOf("exercise-package-lifecycle.mjs");
  assert.ok(earlier > 0 && move > earlier && later > move && exercise > later,
    "the rehearsal must package current, move the version, package rehearsal, then exercise");
  assert.match(lifecycle, /join\(lifecycle, "older"\),\n\s+join\(lifecycle, "newer"\),/);
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
  const notes = readFileSync(rehearsalNotesPath, "utf8");
  assert.match(notes.slice(0, 1024), /\bdraft release notes\b/i);
  assert.match(notes, /not a release/i);
});
