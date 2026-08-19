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
