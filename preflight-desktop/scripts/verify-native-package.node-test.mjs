import assert from "node:assert/strict";
import { mkdirSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import test from "node:test";
import { verifyInstalledEngine } from "./verify-installed-engine.mjs";
import { assertTreesEqual, classifyMacSignature } from "./verify-native-package.mjs";

test("macOS package verification distinguishes Developer ID from ad-hoc signing", () => {
  assert.equal(classifyMacSignature(0, "Authority=Developer ID Application: Example (ABCDE12345)\n"), "developer-id");
  assert.equal(classifyMacSignature(0, "Signature=adhoc\n"), "ad-hoc");
  assert.equal(classifyMacSignature(1, "code object is not signed at all\n"), "unsigned-or-invalid");
  assert.equal(classifyMacSignature(0, "Authority=Apple Development: Example\n"), "unidentified");
});

test("native package tree comparison accepts identical content", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-package-tree-"));
  try {
    for (const name of ["left", "right"]) {
      mkdirSync(join(directory, name, "nested"), { recursive: true });
      writeFileSync(join(directory, name, "nested", "file"), "same bytes");
    }
    assert.doesNotThrow(() => assertTreesEqual(join(directory, "left"), join(directory, "right")));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("native package tree comparison rejects changed bytes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-package-changed-"));
  try {
    writeFileSync(join(directory, "left"), "expected");
    writeFileSync(join(directory, "right"), "changed");
    assert.throws(() => assertTreesEqual(join(directory, "left"), join(directory, "right")), /files differ/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("native package tree comparison checks link destinations", { skip: process.platform === "win32" }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-package-links-"));
  try {
    symlinkSync("first", join(directory, "left"));
    symlinkSync("second", join(directory, "right"));
    assert.throws(() => assertTreesEqual(join(directory, "left"), join(directory, "right")), /links differ/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("installed engine verification rejects an empty package root", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-installed-empty-"));
  try {
    assert.throws(() => verifyInstalledEngine(directory), /exactly one bounded engine; found 0/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
