import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  ENGINE_INPUT_IDENTITY_FORMAT,
  engineInputIdentity,
} from "./engine-input-identity.mjs";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");

test("only the interactive desktop loop opts into retained engine reuse", () => {
  const scripts = JSON.parse(readFileSync(join(desktopDirectory, "package.json"), "utf8")).scripts;
  assert.equal(scripts["engine:prepare"], "node scripts/prepare-engine.mjs");
  assert.equal(scripts["engine:prepare:hot"], "node scripts/prepare-engine.mjs --reuse-if-current");
  assert.equal(scripts["desktop:dev"], "npm run engine:prepare:hot && tauri dev");
  assert.equal(scripts["desktop:build"], "npm run engine:prepare && npm run desktop:clean && tauri build");
});

test("engine input identity is stable and content addressed", () => {
  const root = mkdtempSync(join(tmpdir(), "preflight-engine-input-"));
  try {
    mkdirSync(join(root, "source"));
    writeFileSync(join(root, "source", "A.java"), "class A {}\n");
    writeFileSync(join(root, "pom.xml"), "<project/>\n");
    const first = engineInputIdentity(root, ["source", "pom.xml"], { jdk: "17.0.20" });
    const reordered = engineInputIdentity(root, ["pom.xml", "source"], { jdk: "17.0.20" });
    assert.deepEqual(first, reordered);
    assert.equal(first.format, ENGINE_INPUT_IDENTITY_FORMAT);
    assert.match(first.sha256, /^[a-f0-9]{64}$/);

    writeFileSync(join(root, "source", "A.java"), "class A { int changed; }\n");
    assert.notEqual(
      engineInputIdentity(root, ["source", "pom.xml"], { jdk: "17.0.20" }).sha256,
      first.sha256,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("engine input identity includes file names and external values", () => {
  const root = mkdtempSync(join(tmpdir(), "preflight-engine-input-"));
  try {
    writeFileSync(join(root, "one"), "same");
    writeFileSync(join(root, "two"), "same");
    const first = engineInputIdentity(root, ["one"], { environment: "disabled" });
    const renamed = engineInputIdentity(root, ["two"], { environment: "disabled" });
    const changedValue = engineInputIdentity(root, ["one"], { environment: "configured" });
    assert.notEqual(first.sha256, renamed.sha256);
    assert.notEqual(first.sha256, changedValue.sha256);
    assert.deepEqual(Object.keys(first).sort(), ["format", "sha256"]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("symbolic-link engine inputs fail closed", () => {
  const root = mkdtempSync(join(tmpdir(), "preflight-engine-input-"));
  try {
    writeFileSync(join(root, "source"), "content");
    symlinkSync("source", join(root, "link"));
    assert.throws(() => engineInputIdentity(root, ["link"]), /cannot be a symbolic link/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("missing engine inputs fail closed", () => {
  const root = mkdtempSync(join(tmpdir(), "preflight-engine-input-"));
  try {
    assert.throws(() => engineInputIdentity(root, ["missing"]), /input is missing/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
