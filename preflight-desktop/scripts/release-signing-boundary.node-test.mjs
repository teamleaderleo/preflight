import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(resolve(repository, ".github/workflows/distribution.yml"), "utf8")
  .replaceAll("\r\n", "\n");

test("signed candidates are confined to main in the reviewed workflow", () => {
  assert.match(
    workflow,
    /distribution:\n    if: >-\n      github\.event_name != 'workflow_dispatch' \|\|\n      !inputs\.signed_candidate \|\|\n      github\.ref == 'refs\/heads\/main'/,
  );
});

test("every updater-signing job is gated by the release-signing environment", () => {
  const matches = workflow.match(/\n    environment:\n      name: release-signing/g) ?? [];
  assert.equal(matches.length, 3);
});

test("updater private keys come only from release-signing environment secret names", () => {
  assert.match(
    workflow,
    /TAURI_SIGNING_PRIVATE_KEY: \$\{\{ secrets\.RELEASE_TAURI_SIGNING_PRIVATE_KEY \}\}/,
  );
  assert.match(
    workflow,
    /TAURI_SIGNING_PRIVATE_KEY_PASSWORD: \$\{\{ secrets\.RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD \}\}/,
  );
  assert.doesNotMatch(workflow, /secrets\.TAURI_SIGNING_PRIVATE_KEY\b/);
  assert.doesNotMatch(workflow, /secrets\.TAURI_SIGNING_PRIVATE_KEY_PASSWORD\b/);
});
