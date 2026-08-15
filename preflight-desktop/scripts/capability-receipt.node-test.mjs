import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import {
  buildCapabilityReceipt,
  extractNativeCommands,
  extractProjectLinks,
  verifySourceLock,
} from "./capability-receipt.mjs";

const repositoryRoot = resolve(import.meta.dirname, "..", "..");
const engineJar = join(repositoryRoot, "preflight-cli", "target", "preflight.jar");
const nativeSource = readFileSync(join(repositoryRoot, "preflight-desktop", "src-tauri", "src", "lib.rs"), "utf8");
const sourceLock = JSON.parse(readFileSync(
  join(repositoryRoot, "preflight-desktop", "capabilities", "release-receipt-source-lock.json"),
  "utf8",
));

test("release capability receipt binds the renderer, filesystem, process, and network surfaces", () => {
  const receipt = buildCapabilityReceipt({
    engineJarPath: engineJar,
    productVersion: "0.1.0",
    sourceRevision: "a".repeat(40),
    sourceDirty: false,
    environment: {},
  });
  assert.equal(receipt.format, "preflight-release-capabilities-v1");
  assert.equal(receipt.sourceRevision, "a".repeat(40));
  assert.equal(receipt.sourceDirty, false);
  assert.match(receipt.boundarySourceSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.engineJarSha256, /^[a-f0-9]{64}$/);
  assert.ok(receipt.rendererBoundary.nativeCommands.includes("start_game"));
  assert.deepEqual(receipt.rendererBoundary.tauriPermissions, [
    "core:default",
    "dialog:allow-open",
    "dialog:allow-save",
  ]);
  assert.equal(receipt.network.reportIntakeOrigin, "disabled");
  assert.equal(receipt.network.updateConfigured, false);
  assert.equal(receipt.network.automaticTelemetry, false);
  assert.equal(receipt.arbitraryShellCommandsAccepted, false);
  assert.ok(receipt.filesystem.excluded.includes("save files"));
});

test("compiled report intake is exact and rejects broader URLs", () => {
  const base = {
    engineJarPath: engineJar,
    productVersion: "0.1.0",
    sourceRevision: "b".repeat(40),
  };
  assert.equal(buildCapabilityReceipt({
    ...base,
    environment: {
      PREFLIGHT_REPORT_INTAKE_ORIGIN: "https://reports.example.com/",
      PREFLIGHT_UPDATER_PUBLIC_KEY: "reviewed-key",
    },
  }).network.reportIntakeOrigin, "https://reports.example.com");
  assert.equal(buildCapabilityReceipt({
    ...base,
    environment: { PREFLIGHT_UPDATER_PUBLIC_KEY: "reviewed-key" },
  }).network.updateConfigured, true);
  assert.throws(() => buildCapabilityReceipt({
    ...base,
    environment: { PREFLIGHT_REPORT_INTAKE_ORIGIN: "https://reports.example.com/path" },
  }), /Invalid report intake origin/);
  assert.throws(() => buildCapabilityReceipt({
    ...base,
    environment: { PREFLIGHT_REPORT_INTAKE_ORIGIN: "https://reports.example.invalid" },
  }), /Invalid report intake origin/);
});

test("source lock makes a capability-changing boundary edit explicit", () => {
  assert.ok(verifySourceLock(sourceLock).files >= 10);
  const changed = structuredClone(sourceLock);
  const first = Object.keys(changed.sources)[0];
  changed.sources[first] = "0".repeat(64);
  assert.throws(() => verifySourceLock(changed), /Capability boundary changed without review/);
});

test("source lock treats Git LF and Windows CRLF checkouts identically", async () => {
  const root = await mkdtemp(join(tmpdir(), "preflight-capability-crlf-"));
  try {
    mkdirSync(join(root, "owned"));
    writeFileSync(join(root, "owned", "boundary.txt"), "first\r\nsecond\r\n");
    const expected = createHash("sha256").update("first\nsecond\n").digest("hex");
    assert.equal(verifySourceLock({
      format: "preflight-capability-source-lock-v1",
      sources: { "owned/boundary.txt": expected },
    }, root).files, 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("GitHub Actions uses its immutable checkout identity without container Git ownership", () => {
  const receipt = buildCapabilityReceipt({
    engineJarPath: engineJar,
    productVersion: "0.1.0",
    environment: {
      GITHUB_ACTIONS: "true",
      GITHUB_SHA: "d".repeat(40),
    },
  });
  assert.equal(receipt.sourceRevision, "d".repeat(40));
  assert.equal(receipt.sourceDirty, false);
});

test("native commands and fixed links are derived from host code", () => {
  const commands = extractNativeCommands(nativeSource);
  assert.equal(commands[0], [...commands].sort()[0]);
  assert.equal(new Set(commands).size, commands.length);
  assert.deepEqual(extractProjectLinks(nativeSource), {
    capabilities: "https://github.com/teamleaderleo/preflight/blob/main/docs/capability-receipt.md",
    "getting-started": "https://github.com/teamleaderleo/preflight/blob/main/docs/getting-started.md",
    privacy: "https://github.com/teamleaderleo/preflight/blob/main/docs/privacy.md",
    project: "https://github.com/teamleaderleo/preflight",
    "report-issue": "https://github.com/teamleaderleo/preflight/issues/new",
    "tip-coffee": "https://buymeacoffee.com/teamleaderleo",
    "tip-kofi": "https://ko-fi.com/teamleaderleo",
    "tip-patreon": "https://www.patreon.com/cw/teamleaderleo",
  });
});
