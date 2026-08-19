import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(resolve(repository, ".github", "workflows", "publish-release.yml"), "utf8")
  .replaceAll("\r\n", "\n");

test("public release admission uses release-signing approval", () => {
  assert.match(
    workflow,
    /publish:\n    name: Reverify and publish existing draft\n    runs-on: ubuntu-latest\n    environment:\n      name: release-signing/,
  );
});

test("public release tag must descend from reviewed main", () => {
  assert.match(
    workflow,
    /git fetch --no-tags origin \+refs\/heads\/main:refs\/remotes\/origin\/main/,
  );
  assert.match(workflow, /git merge-base --is-ancestor "\$tag_sha" origin\/main/);
  assert.match(workflow, /Release tag commit is not reachable from main/);
});

test("publication requires tagged lifecycle evidence from reviewed main", () => {
  assert.match(workflow, /lifecycle_run_id:/);
  assert.match(workflow, /Tagged candidate package lifecycle/);
  assert.match(workflow, /\.github\/workflows\/tagged-candidate-lifecycle\.yml/);
  assert.match(workflow, /\.event.*workflow_dispatch/s);
  assert.match(workflow, /\.head_branch.*main/s);
  assert.match(workflow, /git merge-base --is-ancestor "\$lifecycle_head" origin\/main/);
});

test("publication binds all platform lifecycle receipts to the exact tag Distribution bytes", () => {
  assert.match(
    workflow,
    /tagged-candidate-package-lifecycle-\$platform-\$DISTRIBUTION_RUN_ID/,
  );
  assert.match(workflow, /preflight-tagged-package-lifecycle-v1/);
  assert.match(workflow, /Preflight-Linux-x86_64\.deb/);
  assert.match(workflow, /Preflight-Windows-x86_64\.exe/);
  assert.match(workflow, /Preflight-macOS-arm64\.dmg/);
  assert.match(workflow, /receipt\.get\("releaseTag"\) != tag/);
  assert.match(workflow, /receipt\.get\("sourceRevision"\) != source_revision/);
  assert.match(workflow, /package\.get\("sha256"\) != actual_sha/);
  assert.match(workflow, /package\.get\("bytes"\) != len\(data\)/);
});
