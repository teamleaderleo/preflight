import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { rejectedSignature, stageUpdateRehearsal } from "./stage-update-rehearsal.mjs";

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "preflight-stage-rehearsal-"));
  const input = join(root, "artifacts");
  const output = join(root, "feed");
  mkdirSync(input);
  for (const filename of [
    "Preflight-Linux-x86_64.AppImage",
    "Preflight-macOS-arm64.app.tar.gz",
    "Preflight-Windows-x86_64.exe",
  ]) {
    writeFileSync(join(input, filename), `package-${filename}`);
    writeFileSync(join(input, `${filename}.sig`), `signed-${filename}\n`);
  }
  return { input, output };
}

test("stages only the local platform's signed update feed", () => {
  const { input, output } = fixture();
  const report = stageUpdateRehearsal(
    input,
    "v0.1.1",
    "https://rehearsal.example.invalid/run-123",
    output,
    "darwin",
  );
  assert.deepEqual(report.platforms, ["darwin-aarch64"]);
  assert.equal(report.signatureMode, "valid");
  assert.equal(JSON.parse(readFileSync(join(output, "latest.json"), "utf8")).version, "0.1.1");
  assert.ok(existsSync(join(output, "Preflight-macOS-arm64.app.tar.gz")));
  assert.ok(!existsSync(join(output, "Preflight-Linux-x86_64.AppImage")));
  assert.match(readFileSync(join(output, "latest.json.sha256"), "utf8"), /^[a-f0-9]{64}  latest\.json\n$/);
});

test("stages a parseable signature document that must fail cryptographic verification", () => {
  const { input, output } = fixture();
  const original = readFileSync(join(input, "Preflight-macOS-arm64.app.tar.gz.sig"), "utf8").trim();
  assert.throws(() => rejectedSignature(original), /valid base64|recognized Minisign/);

  const minisign = [
    "untrusted comment: signature from tauri secret key",
    "RURJbmNvcnJlY3RCdXRTdHJ1Y3R1cmFsbHlWYWxpZFNpZ25hdHVyZURhdGE=",
    "trusted comment: timestamp:1\\tfile:Preflight.app.tar.gz",
    "QlVTSWduYXR1cmVEYXRhVGhhdFJlbWFpbnNTdHJ1Y3R1cmFsbHlWYWxpZA==",
    "",
  ].join("\n");
  const signature = Buffer.from(minisign, "utf8").toString("base64");
  const rejected = rejectedSignature(signature);
  assert.notEqual(rejected, signature);
  const decoded = Buffer.from(rejected, "base64").toString("utf8");
  assert.match(decoded, /^untrusted comment:/);
  assert.match(decoded, /\ntrusted comment:/);

  writeFileSync(join(input, "Preflight-macOS-arm64.app.tar.gz.sig"), `${signature}\n`);
  const report = stageUpdateRehearsal(
    input,
    "v0.1.2",
    "https://rehearsal.example.invalid",
    output,
    "darwin",
    "rejected",
  );
  const manifest = JSON.parse(readFileSync(join(output, "latest.json"), "utf8"));
  assert.equal(report.signatureMode, "rejected");
  assert.equal(manifest.version, "0.1.2");
  assert.equal(manifest.platforms["darwin-aarch64"].signature, rejected);
});

test("refuses to reuse an existing rehearsal directory", () => {
  const { input, output } = fixture();
  stageUpdateRehearsal(input, "v0.1.1", "https://rehearsal.example.invalid", output, "darwin");
  assert.throws(
    () => stageUpdateRehearsal(input, "v0.1.1", "https://rehearsal.example.invalid", output, "darwin"),
    /EEXIST/,
  );
});

test("validates the feed before creating its staging directory", () => {
  const { input, output } = fixture();
  assert.throws(
    () => stageUpdateRehearsal(input, "v0.1.1", "http://rehearsal.example.invalid", output, "darwin"),
    /HTTPS/,
  );
  assert.equal(existsSync(output), false);
});
