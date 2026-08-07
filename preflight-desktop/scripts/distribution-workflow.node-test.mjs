import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(resolve(repository, ".github/workflows/distribution.yml"), "utf8");

function job(name, nextName) {
  const start = workflow.indexOf(`\n  ${name}:\n`);
  assert.notEqual(start, -1, `missing ${name} job`);
  const end = nextName ? workflow.indexOf(`\n  ${nextName}:\n`, start + 1) : workflow.length;
  assert.notEqual(end, -1, `missing ${nextName} job after ${name}`);
  return workflow.slice(start, end);
}

test("private signed candidates have no publication authority or release command", () => {
  const header = workflow.slice(0, workflow.indexOf("\njobs:\n"));
  const candidate = job("candidate", "publish");
  const publish = job("publish");

  assert.match(header, /signed_candidate:/);
  assert.match(header, /permissions:\n  contents: read/);
  assert.match(candidate, /github\.event_name == 'workflow_dispatch' && inputs\.signed_candidate/);
  assert.match(candidate, /permissions:\n      contents: read/);
  assert.match(candidate, /private-candidate\.invalid/);
  assert.doesNotMatch(candidate, /gh release|contents: write/);

  assert.match(publish, /if: startsWith\(github\.ref, 'refs\/tags\/v'\)/);
  assert.match(publish, /permissions:\n      contents: write/);
  assert.match(publish, /gh release create/);
});

test("signed candidates require updater credentials and compile the reviewed intake origin", () => {
  const distribution = job("distribution", "desktop");
  const desktop = job("desktop", "candidate");

  assert.match(distribution, /if: startsWith\(github\.ref, 'refs\/tags\/v'\) \|\| inputs\.signed_candidate/);
  assert.match(distribution, /TAURI_SIGNING_PRIVATE_KEY_PASSWORD/);
  assert.match(distribution, /PREFLIGHT_REPORT_INTAKE_ORIGIN is required for a private signed candidate/);
  assert.match(desktop, /PREFLIGHT_UPDATE_RELEASE:.*inputs\.signed_candidate/);
  assert.match(desktop, /PREFLIGHT_REPORT_INTAKE_ORIGIN:.*inputs\.signed_candidate/);
});
