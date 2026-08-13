import assert from "node:assert/strict";
import { mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import test from "node:test";
import {
  collectPackages,
  expectedPackageExtensions,
  publicPackageName,
} from "./collect-bundles.mjs";

test("requires update signatures only for an update-enabled release", () => {
  assert.deepEqual(expectedPackageExtensions("darwin", false), [".dmg"]);
  assert.deepEqual(expectedPackageExtensions("linux", false), [".appimage", ".deb"]);
  assert.deepEqual(expectedPackageExtensions("win32", false), [".exe"]);
  assert.deepEqual(expectedPackageExtensions("darwin", true), [".app.tar.gz", ".app.tar.gz.sig", ".dmg"]);
  assert.deepEqual(expectedPackageExtensions("linux", true), [".appimage", ".appimage.sig", ".deb"]);
  assert.deepEqual(expectedPackageExtensions("win32", true), [".exe", ".exe.sig"]);
});

test("collects one package per expected extension", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-native-packages-"));
  try {
    mkdirSync(join(directory, "deb"));
    mkdirSync(join(directory, "appimage"));
    writeFileSync(join(directory, "deb", "preflight.deb"), "deb");
    writeFileSync(join(directory, "appimage", "preflight.AppImage"), "appimage");
    assert.equal(collectPackages(directory, [".deb", ".appimage"]).length, 2);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects an unexpected updater artifact", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-native-extra-"));
  try {
    writeFileSync(join(directory, "preflight.dmg"), "dmg");
    writeFileSync(join(directory, "preflight.app.tar.gz"), "archive");
    assert.throws(() => collectPackages(directory, [".dmg"]), /Native package set differs/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects duplicate package extensions", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-native-duplicate-"));
  try {
    writeFileSync(join(directory, "first.exe"), "one");
    writeFileSync(join(directory, "second.exe"), "two");
    assert.throws(() => collectPackages(directory, [".exe"]), /Two native packages have extension/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("publishes stable platform and architecture names", () => {
  assert.equal(publicPackageName("preflight.dmg", "darwin", "arm64"), "Preflight-macOS-arm64.dmg");
  assert.equal(publicPackageName("preflight.AppImage.sig", "linux", "x64"), "Preflight-Linux-x86_64.AppImage.sig");
  assert.equal(publicPackageName("preflight.exe", "win32", "x64"), "Preflight-Windows-x86_64.exe");
});
