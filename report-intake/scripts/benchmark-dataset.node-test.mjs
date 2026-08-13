import assert from "node:assert/strict";
import test from "node:test";
import { strToU8, zipSync } from "fflate";
import { buildBenchmarkDataset, benchmarkRecordsFromArchive } from "./benchmark-dataset.mjs";
import { createHash } from "node:crypto";

const DIAGNOSTICS_FORMAT = "starsector-preflight-diagnostics-v1";

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function archive({
  platform = "windows",
  productVersion = "0.1.0-test",
  measurementOnlyMs = 80_000,
  optimizedMs = 20_000,
  modifiedMillis = 1_786_529_600_000,
  includeBenchmark = true,
  extraEntries = {},
  declareExtraEntries = true,
  duplicateBenchmarkDeclaration = false,
} = {}) {
  const benchmark = strToU8(JSON.stringify({
    format: "starsector-preflight-desktop-benchmark-v1",
    status: "passed",
    complete: true,
    sessionDirectory: "C:/Users/example/private/desktop-benchmark",
    identity: {
      installRoot: "D:/Games/Starsector",
      launcher: "D:/Games/Starsector/starsector.exe",
      sha256: "a".repeat(64),
    },
    comparison: {
      available: true,
      metrics: {
        processToMainMenuMs: {
          measurementOnly: measurementOnlyMs,
          optimized: optimizedMs,
          improvementPercent: 100 * (measurementOnlyMs - optimizedMs) / measurementOnlyMs,
        },
      },
      context: {
        optimized: {
          adapterMode: "active",
          cacheHits: 15_511,
          cacheMisses: 0,
          fallbacks: 0,
          failures: 0,
          memoryAvailablePercent: 42,
        },
        storage: {
          scope: "all-prepared-data",
          bytes: 1_234_567,
          files: 321,
          categories: [{ path: "C:/Users/example/private/cache", bytes: 1_234_567, files: 321 }],
        },
        measurementOverhead: {
          measurementOnly: { routeSharePercent: 0.25, withinBudget: true },
          optimized: { routeSharePercent: 0.5, withinBudget: true },
        },
      },
    },
  }));
  const entry = "runs/1/benchmark-result.json";
  const included = [];
  if (includeBenchmark) {
    included.push({ entry, bytes: benchmark.byteLength, sha256: sha256(benchmark) });
    if (duplicateBenchmarkDeclaration) included.push({ ...included[0] });
  }
  if (declareExtraEntries) {
    for (const [name, bytes] of Object.entries(extraEntries)) {
      included.push({ entry: name, bytes: bytes.byteLength, sha256: sha256(bytes) });
    }
  }
  const manifest = {
    format: DIAGNOSTICS_FORMAT,
    createdAt: new Date(modifiedMillis + 1_000).toISOString(),
    platform,
    engineVersion: productVersion,
    selectedRuns: [{ rank: 1, modifiedMillis }],
    selectedBenchmarks: [],
    limits: {
      maximumRuns: 3,
      maximumBenchmarks: 2,
      maximumFileBytes: 512 * 1024,
      maximumContentBytes: 5 * 1024 * 1024,
    },
    redactions: ["current user home -> <home>"],
    included,
    skipped: [],
    excludedCategories: [],
  };
  const evidence = { ...extraEntries };
  if (includeBenchmark) evidence[entry] = benchmark;
  return zipSync({
    "README.txt": strToU8("review before sharing\n"),
    "manifest.json": strToU8(`${JSON.stringify(manifest)}\n`),
    ...evidence,
  }, { level: 6 });
}

test("normalizes a paired benchmark without carrying private paths or identity", () => {
  const [record] = benchmarkRecordsFromArchive(archive(), "case.zip");
  assert.equal(record.format, "preflight-community-benchmark-v1");
  assert.equal(record.platform, "windows");
  assert.equal(record.measurementOnlyMs, 80_000);
  assert.equal(record.optimizedMs, 20_000);
  assert.equal(record.improvementPercent, 75);
  assert.deepEqual(record.runtime, {
    cacheHits: 15_511,
    cacheMisses: 0,
    fallbacks: 0,
    failures: 0,
    memoryAvailablePercent: 42,
  });
  assert.deepEqual(record.storage, { bytes: 1_234_567, files: 321 });
  const serialized = JSON.stringify(record);
  assert.doesNotMatch(serialized, /Users|Games|Starsector|launcher|installRoot|identity|categories/i);
});

test("builds median aggregates and improvement-first leaderboard ordering", () => {
  const dataset = buildBenchmarkDataset([
    { source: "one.zip", archive: archive({ measurementOnlyMs: 100_000, optimizedMs: 50_000 }) },
    { source: "two.zip", archive: archive({ platform: "mac", measurementOnlyMs: 80_000, optimizedMs: 20_000, modifiedMillis: 1_786_529_601_000 }) },
  ]);
  assert.equal(dataset.format, "preflight-community-benchmark-dataset-v1");
  assert.equal(dataset.aggregates.count, 2);
  assert.equal(dataset.aggregates.medianMeasurementOnlyMs, 90_000);
  assert.equal(dataset.aggregates.medianOptimizedMs, 35_000);
  assert.equal(dataset.aggregates.medianImprovementPercent, 62.5);
  assert.equal(dataset.aggregates.byPlatform.windows.count, 1);
  assert.equal(dataset.aggregates.byPlatform.mac.count, 1);
  assert.equal(dataset.submissions[0].improvementPercent, 75);
  assert.deepEqual(dataset.leaderboard, dataset.submissions.map((record) => record.submissionId));
});

test("rejects tampered benchmark evidence even when the ZIP is otherwise readable", () => {
  const benchmark = strToU8(JSON.stringify({
    format: "starsector-preflight-desktop-benchmark-v1",
    status: "passed",
    complete: true,
    comparison: {
      available: true,
      metrics: { processToMainMenuMs: { measurementOnly: 80_000, optimized: 10_000, improvementPercent: 87.5 } },
    },
  }));
  const entry = "runs/1/benchmark-result.json";
  const staleDigest = "0".repeat(64);
  const manifest = {
    format: DIAGNOSTICS_FORMAT,
    platform: "windows",
    engineVersion: "0.1.0-test",
    selectedRuns: [{ rank: 1, modifiedMillis: 1_786_529_600_000 }],
    included: [{ entry, bytes: benchmark.byteLength, sha256: staleDigest }],
  };
  const tampered = zipSync({
    "README.txt": strToU8("review\n"),
    "manifest.json": strToU8(JSON.stringify(manifest)),
    [entry]: benchmark,
  });
  assert.throws(() => benchmarkRecordsFromArchive(tampered, "tampered.zip"), /manifest doesn't match/);
});

test("rejects evidence that is present in the ZIP but absent from the manifest", () => {
  const undeclared = archive({
    extraEntries: { "runs/1/run.json": strToU8('{"state":"stopped"}\n') },
    declareExtraEntries: false,
  });
  assert.throws(
    () => benchmarkRecordsFromArchive(undeclared, "undeclared.zip"),
    /manifest doesn't inventory runs\/1\/run\.json/,
  );
});

test("rejects duplicate manifest inventory entries", () => {
  assert.throws(
    () => benchmarkRecordsFromArchive(archive({ duplicateBenchmarkDeclaration: true }), "duplicate.zip"),
    /duplicate included entry runs\/1\/benchmark-result\.json/,
  );
});

test("rejects a diagnostics ZIP that has no paired benchmark", () => {
  assert.throws(
    () => benchmarkRecordsFromArchive(archive({ includeBenchmark: false }), "support-only.zip"),
    /archive contains no paired benchmark result/,
  );
});
