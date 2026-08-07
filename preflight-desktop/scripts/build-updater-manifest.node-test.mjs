import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { buildUpdaterManifest, writeUpdaterManifest } from "./build-updater-manifest.mjs";

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "preflight-updater-"));
  for (const filename of [
    "Preflight-Linux-x86_64.AppImage",
    "Preflight-macOS-arm64.app.tar.gz",
    "Preflight-Windows-x86_64.exe",
  ]) {
    writeFileSync(join(directory, filename), "package");
    writeFileSync(join(directory, `${filename}.sig`), `signed-${filename}\n`);
  }
  return directory;
}

test("builds the static Tauri feed from all three update-signed artifacts", () => {
  const manifest = buildUpdaterManifest(fixture(), "v0.2.0");
  assert.equal(manifest.version, "0.2.0");
  assert.deepEqual(Object.keys(manifest.platforms), [
    "darwin-aarch64",
    "linux-x86_64",
    "windows-x86_64",
  ]);
  assert.equal(
    manifest.platforms["darwin-aarch64"].url,
    "https://github.com/teamleaderleo/preflight/releases/download/v0.2.0/Preflight-macOS-arm64.app.tar.gz",
  );
});

test("refuses a feed when any updater signature is missing", () => {
  const directory = fixture();
  writeFileSync(join(directory, "Preflight-Windows-x86_64.exe.sig"), "");
  assert.throws(() => buildUpdaterManifest(directory, "v0.2.0"), /Invalid updater signature/);
});

test("can assemble a private candidate with inert non-release asset URLs", () => {
  const manifest = buildUpdaterManifest(
    fixture(),
    "v0.2.0",
    "https://private-candidate.invalid/run-123",
  );
  assert.equal(
    manifest.platforms["linux-x86_64"].url,
    "https://private-candidate.invalid/run-123/Preflight-Linux-x86_64.AppImage",
  );
});

test("refuses an unsafe updater asset base", () => {
  assert.throws(
    () => buildUpdaterManifest(fixture(), "v0.2.0", "http://private-candidate.invalid/run-123"),
    /absolute HTTPS URL/,
  );
  assert.throws(
    () => buildUpdaterManifest(fixture(), "v0.2.0", "https://user@example.com/run-123"),
    /without credentials/,
  );
});

test("writes a deterministic manifest checksum", () => {
  const directory = fixture();
  const output = join(directory, "latest.json");
  writeUpdaterManifest(directory, "v0.2.0", output);
  assert.match(readFileSync(output, "utf8"), /"version": "0\.2\.0"/);
  assert.match(readFileSync(`${output}.sha256`, "utf8"), /^[a-f0-9]{64}  latest\.json\n$/);
});
