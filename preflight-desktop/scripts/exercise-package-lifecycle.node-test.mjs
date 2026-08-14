import assert from "node:assert/strict";
import test from "node:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import {
  assertRemoved,
  assertRolledBack,
  assertVersionMoved,
  exercisePackageLifecycle,
  installedVersion,
  treeDigest,
} from "./exercise-package-lifecycle.mjs";

function scratch(files) {
  const root = mkdtempSync(join(tmpdir(), "preflight-lifecycle-test-"));
  for (const [relativePath, contents] of Object.entries(files)) {
    const path = join(root, relativePath);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, contents);
  }
  return root;
}

test("an upgrade that installed the same version proves nothing and says so", () => {
  assert.doesNotThrow(() => assertVersionMoved("0.1.0", "0.1.1-rehearsal"));
  assert.throws(() => assertVersionMoved("0.1.0", "0.1.0"), /nothing was upgraded/);
});

test("a rollback has to restore the earlier version's exact bytes", () => {
  assert.doesNotThrow(() => assertRolledBack("0.1.0", "0.1.0", "abc", "abc"));
  assert.throws(() => assertRolledBack("0.1.0", "0.1.1", "abc", "abc"), /Rollback left version/);
  assert.throws(() => assertRolledBack("0.1.0", "0.1.0", "abc", "def"), /different contents/);
});

test("a digest separates a restored tree from one with the same shape", () => {
  const first = scratch({ "engine/bundle.json": "{}\n", "resources/app.txt": "one\n" });
  const identical = scratch({ "engine/bundle.json": "{}\n", "resources/app.txt": "one\n" });
  const sameShape = scratch({ "engine/bundle.json": "{}\n", "resources/app.txt": "two\n" });
  const renamed = scratch({ "engine/bundle.json": "{}\n", "resources/other.txt": "one\n" });
  try {
    assert.equal(treeDigest(first), treeDigest(identical));
    assert.notEqual(treeDigest(first), treeDigest(sameShape));
    assert.notEqual(treeDigest(first), treeDigest(renamed));
  } finally {
    for (const root of [first, identical, sameShape, renamed]) rmSync(root, { recursive: true, force: true });
  }
});

test("the installed version comes from the one engine manifest", () => {
  const single = scratch({ "engine/bundle.json": JSON.stringify({ sourceVersion: "0.1.1-rehearsal" }) });
  const none = scratch({ "resources/app.txt": "no engine\n" });
  const versionless = scratch({ "engine/bundle.json": JSON.stringify({ modules: [] }) });
  // A bundle.json somewhere other than an engine directory is a payload file, not the manifest.
  const decoy = scratch({
    "engine/bundle.json": JSON.stringify({ sourceVersion: "0.1.0" }),
    "resources/bundle.json": JSON.stringify({ sourceVersion: "9.9.9" }),
  });
  try {
    assert.equal(installedVersion(single), "0.1.1-rehearsal");
    assert.equal(installedVersion(decoy), "0.1.0");
    assert.throws(() => installedVersion(none), /found 0/);
    assert.throws(() => installedVersion(versionless), /no source version/);
  } finally {
    for (const root of [single, none, versionless, decoy]) rmSync(root, { recursive: true, force: true });
  }
});

test("removal is only complete when every owned file is gone", () => {
  const root = scratch({ "resources/app.txt": "installed\n" });
  const owned = join(root, "resources", "app.txt");
  try {
    assert.throws(() => assertRemoved([owned]), /Removal left 1 owned file/);
    rmSync(owned);
    assert.doesNotThrow(() => assertRemoved([owned]));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("the lifecycle refuses to pretend it ran another platform's packages", () => {
  const other = process.platform === "linux" ? "win32" : "linux";
  assert.throws(
    () => exercisePackageLifecycle(tmpdir(), tmpdir(), other),
    new RegExp(`for ${other} can't run on ${process.platform}`),
  );
  assert.throws(
    () => exercisePackageLifecycle(tmpdir(), tmpdir(), "sunos"),
    /isn't implemented for sunos/,
  );
});
