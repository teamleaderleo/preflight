import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const intake = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const wrangler = JSON.parse(readFileSync(resolve(intake, "wrangler.jsonc"), "utf8"));
const lifecycle = JSON.parse(readFileSync(resolve(intake, "r2-lifecycle.json"), "utf8"));

function reportBucket(config) {
  const matches = (config.r2_buckets ?? []).filter((binding) => binding.binding === "REPORTS");
  assert.equal(matches.length, 1, "wrangler config must declare exactly one REPORTS bucket");
  return matches[0].bucket_name;
}

test("R2 lifecycle policy matches the Worker retention contract", () => {
  assert.equal(lifecycle.format, "preflight-r2-lifecycle-v1");
  assert.equal(lifecycle.bucket, reportBucket(wrangler));
  assert.equal(lifecycle.rule.id, "delete-accepted-reports");
  assert.equal(lifecycle.rule.prefix, "accepted/");

  const retentionDays = Number(wrangler.vars?.RETENTION_DAYS);
  assert.ok(Number.isInteger(retentionDays) && retentionDays > 0,
    "RETENTION_DAYS must be a positive integer");
  assert.equal(lifecycle.rule.expireDays, retentionDays,
    "R2 expiration days must move with the receipt retention contract");
});
