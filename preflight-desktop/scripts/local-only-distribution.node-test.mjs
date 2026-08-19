import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(resolve(repository, ".github/workflows/distribution.yml"), "utf8")
  .replaceAll("\r\n", "\n");

// First-beta packages are deliberately local-only for diagnostics. Keep updater signing and
// encrypted private-candidate transport, but never compile a report-intake origin into a beta.
test("Distribution cannot compile remote report intake into the local-only beta", () => {
  assert.doesNotMatch(workflow, /PREFLIGHT_REPORT_INTAKE_ORIGIN/);
  assert.doesNotMatch(workflow, /REPORT_INTAKE_ORIGIN/);

  assert.match(workflow, /TAURI_SIGNING_PRIVATE_KEY/);
  assert.match(workflow, /PREFLIGHT_UPDATER_PUBLIC_KEY/);
  assert.match(workflow, /PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD/);
  assert.match(workflow, /candidate-crypt\.mjs[\s\\]+encrypt/);
  assert.match(workflow, /candidate-crypt\.mjs[\s\\]+decrypt/);
});
