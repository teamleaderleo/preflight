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
    included: [{ entry, bytes: benchmark.byteLength, sha256: sha256(benchmark) }],
    skipped: [],
    excludedCategories: [],
  };
  return zipSync({
    "README.txt": strToU8("review before sharing\n"),
    "manifest.json": strToU8(`${JSON.stringify(manifest)}\n`),
    [entry]: benchmark,
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
  const original = archive();
  const files = new Map();
  // This fixture is intentionally rebuilt with a stale manifest digest rather than mutating ZIP bytes.
  const benchmark = strToU8(JSON.stringify({
    format: "starsector-preflight-desktop-benchmark-v1",
    status: "passed",
    complete: true,
    comparison: {
      available: true,
      metrics: { processToMainMenuMs: { measurementOnly: 80_000, optimized: 10_000, improvementPercent: 87.5 } },
    },
  }));
  void original;
  const entry = "runs/1/benchmark-result.json";
  const staleDigest = "0".repeat(64);
  const manifest = {
    format: DIAGNOSTICS_FORMAT,
    platform: "windows",
    engineVersion: "0.1.0-test",
    selectedRuns: [{ rank: 1, modifiedMillis: 1_786_529_600_000 }],
    included: [{ entry, bytes: benchmark.byteLength, sha256: staleDigest }],
  };
  files.set("README.txt", strToU8("review\n"));
  files.set("manifest.json", strToU8(JSON.stringify(manifest)));
  files.set(entry, benchmark);
  const tampered = zipSync(Object.fromEntries(files));
  assert.throws(() => benchmarkRecordsFromArchive(tampered, "tampered.zip"), /manifest doesn't match/);
});
