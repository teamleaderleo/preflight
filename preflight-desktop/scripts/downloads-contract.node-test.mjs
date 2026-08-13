import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { publicPackageName } from "./collect-bundles.mjs";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const guide = readFileSync(resolve(repository, "docs", "downloads.md"), "utf8");

test("installation guide names every native release package and checksum manifest", () => {
  const packages = [
    publicPackageName("preflight.dmg", "darwin", "arm64"),
    publicPackageName("preflight.exe", "win32", "x64"),
    publicPackageName("preflight.deb", "linux", "x64"),
    publicPackageName("preflight.AppImage", "linux", "x64"),
  ];
  for (const name of packages) assert.match(guide, new RegExp(name.replaceAll(".", "\\.")));
  for (const name of [
    "SHA256SUMS-darwin-arm64.txt",
    "SHA256SUMS-win32-x64.txt",
    "SHA256SUMS-linux-x64.txt",
  ]) assert.match(guide, new RegExp(name.replaceAll(".", "\\.")));
});

test("unsigned-package guidance keeps security overrides narrow", () => {
  assert.match(guide, /Preflight-macOS-arm64\.dmg\$.*shasum -a 256 -c -/);
  assert.match(guide, /\$actual -eq \$expected/);
  assert.match(guide, /Preflight-Linux-x86_64\.deb\$.*sha256sum -c -/);
  assert.match(guide, /Preflight-Linux-x86_64\.AppImage\$.*sha256sum -c -/);
  assert.match(guide, /System Settings → Privacy & Security/);
  assert.match(guide, /More info → Run anyway/);
  assert.match(guide, /sudo apt install \.\/Preflight-Linux-x86_64\.deb/);
  assert.match(guide, /chmod \+x Preflight-Linux-x86_64\.AppImage/);
  assert.doesNotMatch(guide, /spctl\s+--master-disable|xattr\s+-[dr]+|disable SmartScreen|turn off (?:Gatekeeper|antivirus)/i);
});
