import assert from "node:assert/strict";
import { mkdirSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import test from "node:test";
import {
  runtimeInventory,
  verifyEngineBoundary,
  verifyEngineIntegrity,
  verifyRuntimeInventory,
} from "./engine-boundary.mjs";

test("prepared desktop engine stays inside the reviewed resource boundary", () => {
  const report = verifyEngineBoundary();
  assert.ok(report.runtimeFiles > 0);
  assert.ok(report.runtimeBytes > 0);
});

test("prepared desktop engine also carries a self-contained integrity proof", () => {
  const report = verifyEngineIntegrity();
  assert.ok(report.runtimeFiles > 0);
  assert.ok(report.runtimeBytes > 0);
});

test("runtime inventory is stable across creation order", async () => {
  const first = await mkdtemp(join(tmpdir(), "preflight-runtime-first-"));
  const second = await mkdtemp(join(tmpdir(), "preflight-runtime-second-"));
  try {
    mkdirSync(join(first, "lib"));
    writeFileSync(join(first, "lib", "modules"), "module bytes");
    writeFileSync(join(first, "release"), "JAVA_VERSION=17");
    writeFileSync(join(second, "release"), "JAVA_VERSION=17");
    mkdirSync(join(second, "lib"));
    writeFileSync(join(second, "lib", "modules"), "module bytes");
    const inventory = runtimeInventory(first);
    assert.deepEqual(inventory, runtimeInventory(second));
    assert.deepEqual(inventory.entries.map((entry) => entry.path), ["lib/modules", "release"]);
    assert.match(inventory.entries[0].sha256, /^[a-f0-9]{64}$/);
  } finally {
    await rm(first, { recursive: true, force: true });
    await rm(second, { recursive: true, force: true });
  }
});

test("runtime inventory rejects symbolic links", { skip: process.platform === "win32" }, async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-runtime-link-"));
  try {
    writeFileSync(join(directory, "real"), "data");
    symlinkSync(join(directory, "real"), join(directory, "alias"));
    assert.throws(() => runtimeInventory(directory), /Symbolic link isn't allowed/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("runtime inventory rejects game-content path segments", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-runtime-game-"));
  try {
    mkdirSync(join(directory, "saves"));
    writeFileSync(join(directory, "saves", "campaign.xml"), "private");
    assert.throws(() => runtimeInventory(directory), /Forbidden desktop engine path segment: saves/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("runtime inventory allows only the exact declared packaging changes", async () => {
  const expectedDirectory = await mkdtemp(join(tmpdir(), "preflight-runtime-expected-"));
  const actualDirectory = await mkdtemp(join(tmpdir(), "preflight-runtime-actual-"));
  try {
    writeFileSync(join(expectedDirectory, "java"), "original");
    writeFileSync(join(actualDirectory, "java"), "packaged");
    const expected = runtimeInventory(expectedDirectory);
    const actual = runtimeInventory(actualDirectory);
    assert.deepEqual(verifyRuntimeInventory(expected, actual, ["java"]), ["java"]);
    assert.throws(
      () => verifyRuntimeInventory(expected, actual),
      /changed java/,
    );
    assert.throws(
      () => verifyRuntimeInventory(expected, actual, ["libjvm.so"]),
      /changed java; allowed changes libjvm.so/,
    );
  } finally {
    await rm(expectedDirectory, { recursive: true, force: true });
    await rm(actualDirectory, { recursive: true, force: true });
  }
});

test("runtime inventory rejects a legacy or malformed manifest", async () => {
  const directory = await mkdtemp(join(tmpdir(), "preflight-runtime-current-"));
  try {
    writeFileSync(join(directory, "java"), "runtime");
    const actual = runtimeInventory(directory);
    assert.throws(
      () => verifyRuntimeInventory({ files: 1, bytes: 7, sha256: "0".repeat(64) }, actual),
      /Invalid manifest inventory fields/,
    );
    const duplicate = { ...actual, files: 2, bytes: 14, entries: [...actual.entries, ...actual.entries] };
    assert.throws(
      () => verifyRuntimeInventory(duplicate, actual),
      /Invalid manifest inventory contents/,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
