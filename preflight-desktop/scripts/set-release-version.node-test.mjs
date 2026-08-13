import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { setReleaseVersion } from "./set-release-version.mjs";

function fixture(withNotes = true) {
  const repository = mkdtempSync(join(tmpdir(), "preflight-release-version-"));
  const desktop = join(repository, "preflight-desktop");
  mkdirSync(join(desktop, "src-tauri"), { recursive: true });
  mkdirSync(join(repository, "preflight-core"), { recursive: true });
  mkdirSync(join(repository, "docs", "releases"), { recursive: true });
  writeFileSync(join(repository, "pom.xml"), "<version>0.1.0</version>\n<modules><module>preflight-core</module></modules>\n");
  writeFileSync(join(repository, "preflight-core", "pom.xml"), "<parent><version>0.1.0</version></parent>\n");
  writeFileSync(join(desktop, "package.json"), '{"name":"desktop","version":"0.1.0"}\n');
  writeFileSync(join(desktop, "package-lock.json"), '{"version":"0.1.0","packages":{"":{"version":"0.1.0"}}}\n');
  writeFileSync(join(desktop, "src-tauri", "tauri.conf.json"), '{"version":"0.1.0"}\n');
  writeFileSync(join(desktop, "src-tauri", "Cargo.toml"), '[package]\nversion = "0.1.0"\n');
  writeFileSync(join(desktop, "src-tauri", "Cargo.lock"), '[[package]]\nname = "starsector-preflight-desktop"\nversion = "0.1.0"\n');
  if (withNotes) writeFileSync(join(repository, "docs", "releases", "0.2.0.md"), "Reviewed notes.\n");
  return repository;
}

test("updates every shipped version source after release notes exist", () => {
  const repository = fixture();
  assert.equal(7, setReleaseVersion(repository, "0.2.0").length);
  for (const path of [
    "pom.xml",
    "preflight-core/pom.xml",
    "preflight-desktop/package.json",
    "preflight-desktop/package-lock.json",
    "preflight-desktop/src-tauri/tauri.conf.json",
    "preflight-desktop/src-tauri/Cargo.toml",
    "preflight-desktop/src-tauri/Cargo.lock",
  ]) {
    assert.match(readFileSync(join(repository, path), "utf8"), /0\.2\.0/);
    assert.doesNotMatch(readFileSync(join(repository, path), "utf8"), /0\.1\.0/);
  }
});

test("refuses to mutate versions before reviewed notes exist", () => {
  const repository = fixture(false);
  assert.throws(() => setReleaseVersion(repository, "0.2.0"), /Write reviewed release notes first/);
  assert.match(readFileSync(join(repository, "pom.xml"), "utf8"), /0\.1\.0/);
  assert.throws(() => setReleaseVersion(repository, "v0.2.0"), /without a v prefix/);
});
