import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const publication = readFileSync(
  resolve(repository, ".github/workflows/publish-release.yml"),
  "utf8",
).replaceAll("\r\n", "\n");

test("publication requires one reviewed successful tagged report-canary run", () => {
  assert.match(publication, /report_canary_run_id:/);
  assert.match(publication, /REPORT_CANARY_RUN_ID: \$\{\{ inputs\.report_canary_run_id \}\}/);
  assert.match(publication, /\[\[ "\$REPORT_CANARY_RUN_ID" =~ \^\[1-9\]\[0-9\]\*\$ \]\]/);
  assert.match(publication, /Tagged report-canary run ID must be a positive integer/);
  assert.match(publication, /actions\/runs\/\$REPORT_CANARY_RUN_ID/);
  assert.match(publication, /run\.get\("name"\) != "Tagged report-intake canary"/);
  assert.match(publication, /run\.get\("path"\) != "\.github\/workflows\/tagged-report-canary\.yml"/);
  assert.match(publication, /run\.get\("event"\) != "workflow_dispatch"/);
  assert.match(publication, /run\.get\("head_branch"\) != "main"/);
  assert.match(publication, /Tagged report canary workflow did not run from reviewed main history/);
  assert.match(
    publication,
    /GITHUB_REF_TYPE=tag node preflight-desktop\/scripts\/validate-release-version\.mjs "\$RELEASE_TAG"/,
    "publisher must validate its exact tag checkout in final release-note mode",
  );
});

test("publication binds the canary receipt to the exact selected release generation", () => {
  assert.match(publication, /tagged-report-canary-\$DISTRIBUTION_RUN_ID/);
  assert.match(publication, /preflight-tagged-report-canary-v1/);
  assert.match(publication, /receipt\.get\("releaseTag"\) != tag/);
  assert.match(publication, /str\(receipt\.get\("distributionRunId"\)\) != distribution_run_id/);
  assert.match(publication, /receipt\.get\("sourceRevision"\) != source_revision/);
  assert.match(publication, /Preflight-Linux-x86_64\.deb/);
  assert.match(publication, /CAPABILITIES-linux-x64\.json/);
  assert.match(publication, /report canary package byte length differs from tagged release/);
  assert.match(publication, /report canary package SHA-256 differs from tagged release/);
  assert.match(publication, /report canary capability digest differs from tagged release/);
  assert.match(publication, /report canary origin differs from tagged package capability/);
});

test("publication independently validates the bounded public canary result", () => {
  assert.match(publication, /allowed_canary_fields = \{/);
  for (const field of [
    "format",
    "origin",
    "caseId",
    "archiveBytes",
    "sha256",
    "receivedAt",
    "retentionDeadline",
    "deleted",
  ]) {
    assert.match(publication, new RegExp(`"${field}"`));
  }
  assert.match(publication, /set\(canary\) != allowed_canary_fields/);
  assert.match(publication, /preflight-report-intake-canary-v1/);
  assert.match(publication, /report canary case ID is invalid/);
  assert.match(publication, /report canary archive byte count is invalid/);
  assert.match(publication, /report canary archive SHA-256 is invalid/);
  assert.match(publication, /report canary receivedAt is invalid/);
  assert.match(publication, /report canary retentionDeadline is invalid/);
  assert.match(publication, /report canary retention deadline does not follow receipt time/);
  assert.match(publication, /canary\.get\("deleted"\) is not True/);
  assert.doesNotMatch(publication, /allowed_canary_fields[\s\S]{0,400}"(?:token|authorization|upload|finalize|deletion)"/i);
  assert.match(publication, /gh release edit "\$RELEASE_TAG".*--draft=false/);
});
