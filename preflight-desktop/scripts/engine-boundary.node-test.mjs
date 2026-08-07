import assert from "node:assert/strict";
import { mkdirSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import test from "node:test";
import { runtimeInventory, verifyEngineBoundary } from "./engine-boundary.mjs";

test("prepared desktop engine stays inside the reviewed resource boundary", () => {
  const report = verifyEngineBoundary();
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
