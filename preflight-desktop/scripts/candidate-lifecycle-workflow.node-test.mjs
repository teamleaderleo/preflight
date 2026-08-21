import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(resolve(repository, ".github", "workflows", "candidate-lifecycle.yml"), "utf8");

test("candidate lifecycle consumes one explicit private signed Distribution run", () => {
  assert.match(workflow, /distribution_run_id:/);
  assert.match(workflow, /preflight-private-signed-candidate-\$\{\{ inputs\.distribution_run_id \}\}/);
  assert.match(workflow, /github-token: \$\{\{ github\.token \}\}/);
  assert.match(workflow, /run-id: \$\{\{ inputs\.distribution_run_id \}\}/);
  assert.match(workflow, /python3 scripts\/verify_complete_release\.py --release candidate-input/);
  assert.match(workflow, /\.github\/workflows\/distribution\.yml/);
  assert.match(workflow, /\.head_branch.*main/s);
  assert.match(workflow, /\.head_sha.*source_revision/s);
});

test("candidate lifecycle covers exactly the three packaged beta platforms", () => {
  const names = [...workflow.matchAll(/^\s+- name: (Linux|Windows|macOS)$/gm)].map((match) => match[1]);
  assert.deepEqual(names, ["Linux", "Windows", "macOS"]);
  assert.match(workflow, /Preflight-Linux-x86_64\.deb/);
  assert.match(workflow, /Preflight-Windows-x86_64\.exe/);
  assert.match(workflow, /Preflight-macOS-arm64\.dmg/);
});

test("candidate package is retained as the newer package and receipt binds its digest", () => {
  assert.match(workflow, /candidate-newer/);
  assert.match(workflow, /exercise-package-lifecycle\.mjs[\s\S]*target\/release\/bundle[\s\S]*candidate-newer/);
  assert.match(workflow, /preflight-candidate-package-lifecycle-v1/);
  assert.match(workflow, /sourceRevision/);
  assert.match(workflow, /checksumManifest/);
  assert.match(workflow, /createHash\("sha256"\)/);
});

test("the runner-local earlier package includes the reviewed frontend", () => {
  assert.match(
    workflow,
    /Build an earlier rehearsal package[\s\S]*npm run build[\s\S]*npx tauri build/,
  );
});

test("candidate lifecycle secret access stays behind the release-signing Environment", () => {
  assert.equal((workflow.match(/name: release-signing/g) ?? []).length, 2);
  assert.match(workflow, /secrets\.PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD/);
});
