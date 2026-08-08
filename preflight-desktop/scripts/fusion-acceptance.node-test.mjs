import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  acceptanceMode,
  candidateAssetIdentities,
  claimsForMode,
  detectedHostArchitecture,
  runPortableAcceptance,
  validateAcceptanceEvidence,
} from "./fusion-acceptance.mjs";
import {
  assertSyntheticDiscovery,
  assertSyntheticPreparation,
} from "./synthetic-package-contract.mjs";

test("Fusion modes don't mistake an ARM Ubuntu guest for x86-64 package evidence", () => {
  assert.equal(acceptanceMode("win32", "arm64"), "windows-x64-package-under-arm64-emulation");
  assert.equal(acceptanceMode("linux", "arm64", true), "linux-arm64-portable-source-contracts");
  assert.throws(() => acceptanceMode("linux", "arm64"), /can't validate the published x86-64 Linux package/);
  assert.equal(acceptanceMode("linux", "x64"), "linux-x64-package-native-architecture");
});

test("Windows acceptance identifies the host architecture even under an x64 Node process", () => {
  assert.equal(detectedHostArchitecture("win32", "x64", {}, () => "Arm64"), "arm64");
  assert.equal(
    detectedHostArchitecture("win32", "x64", { PROCESSOR_ARCHITEW6432: "ARM64" }, () => null),
    "arm64",
  );
  assert.equal(detectedHostArchitecture("linux", "arm64", {}, () => "x64"), "arm64");
});

test("Fusion claims never imply licensed-game, GPU, or performance evidence", () => {
  const claims = claimsForMode("windows-x64-package-under-arm64-emulation");
  assert.deepEqual(claims, {
    packageLifecycle: true,
    portableJvmAndUiContracts: true,
    x64BehaviorUnderWindowsArmEmulation: true,
    nativeX64LinuxPackage: false,
    licensedGameIntegration: false,
    nativePerformance: false,
    gpuAudioOrDriverBehavior: false,
  });
  for (const mode of [
    "windows-x64-package-under-arm64-emulation",
    "windows-x64-package-native-architecture",
    "linux-arm64-portable-source-contracts",
    "linux-x64-portable-source-contracts",
    "linux-x64-package-native-architecture",
  ]) {
    for (const [name, value] of Object.entries(claimsForMode(mode))) {
      assert.equal(typeof value, "boolean", `${mode} ${name}`);
    }
    assert.equal(claimsForMode(mode).licensedGameIntegration, false);
    assert.equal(claimsForMode(mode).nativePerformance, false);
    assert.equal(claimsForMode(mode).gpuAudioOrDriverBehavior, false);
  }
  assert.throws(() => claimsForMode("invented-mode"), /Unknown Fusion acceptance mode/);
});

test("Windows candidate identity accepts the stable public installer name", () => {
  const directory = mkdtempSync(join(tmpdir(), "preflight-fusion-windows-assets-"));
  try {
    writeFileSync(join(directory, "Preflight-Windows-x86_64.exe"), "windows");
    const identities = candidateAssetIdentities(directory, "win32");
    assert.equal(identities.length, 1);
    assert.equal(identities[0].filename, "Preflight-Windows-x86_64.exe");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("candidate package identities retain independently calculated digests", () => {
  const directory = mkdtempSync(join(tmpdir(), "preflight-fusion-assets-"));
  try {
    writeFileSync(join(directory, "Preflight_0.1.0_amd64.deb"), "debian");
    writeFileSync(join(directory, "Preflight_0.1.0_amd64.AppImage"), "appimage");
    const identities = candidateAssetIdentities(directory, "linux");
    assert.deepEqual(identities.map((asset) => asset.filename), [
      "Preflight_0.1.0_amd64.AppImage",
      "Preflight_0.1.0_amd64.deb",
    ]);
    assert.ok(identities.every((asset) => /^[a-f0-9]{64}$/.test(asset.sha256) && asset.bytes > 0));
    writeFileSync(join(directory, "duplicate.deb"), "duplicate");
    assert.throws(() => candidateAssetIdentities(directory, "linux"), /exact linux package set/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("portable acceptance records successful bounded command identities", () => {
  const commands = [];
  const result = runPortableAcceptance((command, args, options) => {
    commands.push({ command, args, cwd: options.cwd });
    return {
      command: [command, ...args],
      durationMs: 12,
      outputBytes: 34,
      outputSha256: "a".repeat(64),
    };
  });
  assert.deepEqual(commands.map(({ command, args }) => [command, ...args]), [
    ["mvn", "verify"],
    ["npm", "run", "verify"],
  ]);
  assert.equal(result.checks.at(-1).status, "skipped");
});

test("structured evidence rejects inflated compatibility claims", () => {
  const evidence = {
    format: "preflight-fusion-acceptance-v1",
    hypervisor: "vmware-fusion",
    mode: "windows-x64-package-under-arm64-emulation",
    environment: {
      sourceRevision: "1".repeat(40),
      sourceDirty: false,
      sourceStatusBytes: 0,
      sourceStatusSha256: "2".repeat(64),
    },
    claims: claimsForMode("windows-x64-package-under-arm64-emulation"),
    checks: [{ id: "package", status: "passed" }],
    deferred: [{ id: "game", reason: "Needs a licensed installation." }],
  };
  assert.doesNotThrow(() => validateAcceptanceEvidence(evidence));
  assert.throws(
    () => validateAcceptanceEvidence({ ...evidence, claims: { ...evidence.claims, nativePerformance: true } }),
    /Malformed Fusion acceptance evidence/,
  );
  assert.throws(
    () => validateAcceptanceEvidence({
      ...evidence,
      claims: { ...evidence.claims, x64BehaviorUnderWindowsArmEmulation: false },
    }),
    /Malformed Fusion acceptance evidence/,
  );
  assert.throws(
    () => validateAcceptanceEvidence({ ...evidence, mode: "invented-mode" }),
    /Malformed Fusion acceptance evidence/,
  );
});

test("synthetic package assertions require exact discovery, cold/warm preparation, and bounded reports", () => {
  const fixture = "/tmp/Synthetic Game – path Ω";
  const launcher = `${fixture}/starsector.sh`;
  assert.doesNotThrow(() => assertSyntheticDiscovery({
    protocol: 1,
    ready: true,
    selected: { installRoot: fixture, launcher, kind: "shell-script" },
  }, fixture, launcher));
  const stage = (artifactHit) => ({
    successful: true,
    resourceIndex: { status: "SUCCESS", artifactHit },
    classpathIndex: { status: "SUCCESS" },
    textures: { status: "SKIPPED" },
  });
  assert.doesNotThrow(() => assertSyntheticPreparation(stage(false), stage(true)));
  assert.throws(() => assertSyntheticPreparation(stage(false), stage(false)), /cold build and warm reuse/);

});
