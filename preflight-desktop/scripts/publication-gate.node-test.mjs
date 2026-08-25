import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(resolve(repository, ".github", "workflows", "publish-release.yml"), "utf8")
  .replaceAll("\r\n", "\n");

function workflowStep(name) {
  const marker = `      - name: ${name}\n`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `missing workflow step: ${name}`);
  const remainder = workflow.slice(start + marker.length);
  const end = remainder.indexOf("\n      - name: ");
  return end === -1 ? remainder : remainder.slice(0, end);
}

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

test("publication binds the selected Distribution run to the exact release tag", () => {
  const admission = workflowStep("Validate tag and successful Distribution run");
  assert.match(admission, /run\.get\("name"\) != "Distribution"/);
  assert.match(admission, /run\.get\("path"\) != "\.github\/workflows\/distribution\.yml"/);
  assert.match(admission, /run\.get\("event"\) != "push"/);
  assert.match(admission, /run\.get\("head_branch"\) != tag/);
  assert.match(admission, /run\.get\("head_sha"\) != tag_sha/);
});

test("publication requires tagged lifecycle evidence from reviewed main", () => {
  assert.match(workflow, /lifecycle_run_id:/);
  assert.match(workflow, /Tagged candidate package lifecycle/);
  assert.match(workflow, /\.github\/workflows\/tagged-candidate-lifecycle\.yml/);
  assert.match(workflow, /run\.get\("event"\) != "workflow_dispatch"/);
  assert.match(workflow, /run\.get\("head_branch"\) != "main"/);
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

test("publication requires a reviewed report canary over the exact tagged Linux bytes", () => {
  const admission = workflowStep("Require production report canary evidence from these exact tagged bytes");
  assert.match(admission, /run\.get\("name"\) != "Tagged report-intake canary"/);
  assert.match(admission, /run\.get\("path"\) != "\.github\/workflows\/tagged-report-canary\.yml"/);
  assert.match(admission, /run\.get\("event"\) != "workflow_dispatch"/);
  assert.match(admission, /run\.get\("head_branch"\) != "main"/);
  assert.match(admission, /git merge-base --is-ancestor "\$canary_head" origin\/main/);
  assert.match(admission, /tagged-report-canary-\$DISTRIBUTION_RUN_ID/);
  assert.match(admission, /preflight-tagged-report-canary-v1/);
  assert.match(admission, /receipt\.get\("releaseTag"\) != tag/);
  assert.match(admission, /receipt\.get\("distributionRunId"\)/);
  assert.match(admission, /receipt\.get\("sourceRevision"\) != source_revision/);
  assert.match(admission, /package\.get\("name"\) != package_name/);
  assert.match(admission, /package\.get\("bytes"\) != len\(data\)/);
  assert.match(admission, /package\.get\("sha256"\) != sha256\(data\)\.hexdigest\(\)/);
  assert.match(admission, /capability\.get\("sha256"\) != sha256\(capability_bytes\)\.hexdigest\(\)/);
  assert.match(admission, /origin != expected_origin/);
  assert.match(admission, /canary\.get\("deleted"\) is not True/);
});
