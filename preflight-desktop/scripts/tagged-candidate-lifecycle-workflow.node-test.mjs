import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(
  resolve(repository, ".github", "workflows", "tagged-candidate-lifecycle.yml"),
  "utf8",
).replaceAll("\r\n", "\n");

test("tagged lifecycle accepts only a successful tag-push Distribution artifact", () => {
  assert.match(workflow, /tag:/);
  assert.match(workflow, /distribution_run_id:/);
  assert.match(workflow, /\.github\/workflows\/distribution\.yml/);
  assert.match(workflow, /\.event.*push/s);
  assert.match(workflow, /refs\/tags\/\$\{\{ inputs\.tag \}\}/);
  assert.match(workflow, /tag_sha.*steps\.metadata\.outputs\.source_revision/s);
  assert.match(
    workflow,
    /preflight-complete-release-\$\{\{ inputs\.distribution_run_id \}\}/,
  );
  assert.match(workflow, /python3 scripts\/verify_complete_release\.py --release tagged-release/);
});

test("tagged lifecycle exercises the exact three published beta packages", () => {
  const names = [...workflow.matchAll(/^\s+- name: (Linux|Windows|macOS)$/gm)].map(
    (match) => match[1],
  );
  assert.deepEqual(names, ["Linux", "Windows", "macOS"]);
  assert.match(workflow, /Preflight-Linux-x86_64\.deb/);
  assert.match(workflow, /Preflight-Windows-x86_64\.exe/);
  assert.match(workflow, /Preflight-macOS-arm64\.dmg/);
  assert.match(
    workflow,
    /exercise-package-lifecycle\.mjs[\s\S]*candidate-newer[\s\S]*tagged-candidate-lifecycle-report\/lifecycle\.json/,
  );
});

test("tagged lifecycle receipt binds tag, Distribution run, source and package digest", () => {
  assert.match(workflow, /preflight-tagged-package-lifecycle-v1/);
  assert.match(workflow, /report\.releaseTag = process\.env\.RELEASE_TAG/);
  assert.match(workflow, /report\.distributionRunId = process\.env\.DISTRIBUTION_RUN_ID/);
  assert.match(workflow, /report\.sourceRevision = process\.env\.SOURCE_REVISION/);
  assert.match(workflow, /report\.exactCandidatePackage/);
  assert.match(workflow, /createHash\("sha256"\)/);
  assert.match(workflow, /checksumManifest/);
  assert.match(
    workflow,
    /tagged-candidate-package-lifecycle-\$\{\{ matrix\.name \}\}-\$\{\{ inputs\.distribution_run_id \}\}/,
  );
});
