import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const workflow = readFileSync(
  resolve(repository, ".github/workflows/tagged-report-canary.yml"),
  "utf8",
).replaceAll("\r\n", "\n");
const canary = readFileSync(
  resolve(repository, "report-intake/scripts/canary.mjs"),
  "utf8",
).replaceAll("\r\n", "\n");

test("tagged report canary binds one successful exact-tag Distribution generation", () => {
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /tag:/);
  assert.match(workflow, /distribution_run_id:/);
  assert.match(workflow, /test "\$GITHUB_REF_NAME" = main/);
  assert.match(workflow, /\.name' <<<"\$run_json"\)" = "Distribution"/);
  assert.match(workflow, /\.path' <<<"\$run_json"\)" = "\.github\/workflows\/distribution\.yml"/);
  assert.match(workflow, /\.event' <<<"\$run_json"\)" = "push"/);
  assert.match(workflow, /\.head_branch' <<<"\$run_json"\)" = "\$\{\{ inputs\.tag \}\}"/);
  assert.match(workflow, /Tagged Distribution run ref differs from the selected release tag/);
  assert.match(workflow, /\.conclusion' <<<"\$run_json"\)" = "success"/);
  assert.match(workflow, /tag_sha=.*git rev-parse/);
  assert.match(workflow, /steps\.metadata\.outputs\.source_revision/);
  assert.match(workflow, /preflight-complete-release-\$\{\{ inputs\.distribution_run_id \}\}/);
  assert.match(workflow, /scripts\/verify_complete_release\.py --release tagged-release/);
});

test("canary endpoint comes from the exact tagged package capability statement", () => {
  assert.match(workflow, /Preflight-Linux-x86_64\.deb/);
  assert.match(workflow, /SHA256SUMS-linux-x64\.txt/);
  assert.match(workflow, /CAPABILITIES-linux-x64\.json/);
  assert.match(workflow, /capability\.get\("sourceRevision"\) != source_revision/);
  assert.match(workflow, /network\.get\("reportIntakeOrigin"\)/);
  assert.match(workflow, /origin == "disabled"/);
  assert.match(workflow, /Tagged candidate report-intake origin is not an exact HTTPS origin/);
  assert.match(workflow, /"exactCandidatePackage"/);
  assert.match(workflow, /sha256\(package_bytes\)\.hexdigest\(\)/);
  assert.match(workflow, /sha256\(capability_path\.read_bytes\(\)\)\.hexdigest\(\)/);
});

test("receipt requires successful production canary deletion and carries no bearer credentials", () => {
  assert.match(workflow, /node report-intake\/scripts\/canary\.mjs "\$REPORT_INTAKE_ORIGIN"/);
  assert.match(workflow, /canary\.get\("format"\) != "preflight-report-intake-canary-v1"/);
  assert.match(workflow, /canary\.get\("origin"\) != identity\["reportIntakeOrigin"\]/);
  assert.match(workflow, /canary\.get\("deleted"\) is not True/);
  assert.match(workflow, /"format": "preflight-tagged-report-canary-v1"/);
  assert.match(workflow, /name: tagged-report-canary-\$\{\{ inputs\.distribution_run_id \}\}/);

  const returned = canary.match(
    /return \{\n\s+format: "preflight-report-intake-canary-v1",[\s\S]*?\n\s+\};/,
  )?.[0];
  assert.ok(returned, "runCanary must retain one explicit bounded success receipt");
  assert.doesNotMatch(returned, /token|authorization|upload|finalize|deletion/i);
  assert.match(returned, /deleted: true/);
  assert.doesNotMatch(workflow, /"token"\s*:/);
});
