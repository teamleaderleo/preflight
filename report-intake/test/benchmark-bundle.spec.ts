import { strToU8, zipSync } from "fflate";
import { expect, it } from "vitest";
import { verifyDiagnosticBundle } from "../src/bundle";
import { sha256Hex } from "../src/crypto";
import { BUNDLE_FORMAT, SUPPORT_EVIDENCE_FORMAT } from "../src/protocol";

it("accepts the projected sealed paired desktop benchmark result", async () => {
  const entry = "runs/1/benchmark-result.json";
  const evidence = strToU8(JSON.stringify({
    format: SUPPORT_EVIDENCE_FORMAT,
    source: "benchmark-result.json",
    records: [{
      fields: {
        format: "starsector-preflight-desktop-benchmark-v1",
        "metrics.processToMainMenuMs.measurementOnly": 80_000,
        "metrics.processToMainMenuMs.optimized": 20_000,
        "metrics.processToMainMenuMs.improvementPercent": 75,
      },
    }],
  }));
  const manifest = {
    format: BUNDLE_FORMAT,
    createdAt: new Date().toISOString(),
    platform: "windows",
    engineVersion: "0.1.0-test",
    selectedRuns: [{ rank: 1, modifiedMillis: 1 }],
    selectedBenchmarks: [],
    limits: {
      maximumRuns: 3,
      maximumBenchmarks: 2,
      maximumFileBytes: 512 * 1024,
      maximumContentBytes: 5 * 1024 * 1024,
    },
    redactions: ["current user home -> <home>"],
    included: [{
      entry,
      bytes: evidence.byteLength,
      sha256: await sha256Hex(evidence),
    }],
    skipped: [],
    excludedCategories: [
      "acceleration caches",
      "game and mod files",
      "saves",
      "logs and crash dumps",
      "JFR recordings",
      "screenshots and audio",
      "unknown filenames",
      "symbolic links",
    ],
  };
  const archive = zipSync({
    "README.txt": strToU8("Bounded Preflight diagnostics. Review before sharing.\n"),
    "manifest.json": strToU8(`${JSON.stringify(manifest)}\n`),
    [entry]: evidence,
  }, { level: 6 });

  await expect(verifyDiagnosticBundle(archive)).resolves.toMatchObject({
    format: BUNDLE_FORMAT,
    evidenceEntries: 1,
  });
});

it("rejects the old raw evidence object even when its filename and digest are valid", async () => {
  const entry = "runs/1/benchmark-result.json";
  const evidence = strToU8(JSON.stringify({
    format: "starsector-preflight-desktop-benchmark-v1",
    secret: "arbitrary raw field",
  }));
  const manifest = {
    format: BUNDLE_FORMAT,
    createdAt: new Date().toISOString(),
    platform: "windows",
    engineVersion: "0.1.0-test",
    selectedRuns: [{ rank: 1, modifiedMillis: 1 }],
    selectedBenchmarks: [],
    limits: {
      maximumRuns: 3,
      maximumBenchmarks: 2,
      maximumFileBytes: 512 * 1024,
      maximumContentBytes: 5 * 1024 * 1024,
    },
    redactions: ["current user home -> <home>"],
    included: [{ entry, bytes: evidence.byteLength, sha256: await sha256Hex(evidence) }],
    skipped: [],
    excludedCategories: [
      "acceleration caches",
      "game and mod files",
      "saves",
      "logs and crash dumps",
      "JFR recordings",
      "screenshots and audio",
      "unknown filenames",
      "symbolic links",
    ],
  };
  const archive = zipSync({
    "README.txt": strToU8("Bounded Preflight diagnostics. Review before sharing.\n"),
    "manifest.json": strToU8(`${JSON.stringify(manifest)}\n`),
    [entry]: evidence,
  }, { level: 6 });

  await expect(verifyDiagnosticBundle(archive)).rejects.toThrow("support evidence");
});
