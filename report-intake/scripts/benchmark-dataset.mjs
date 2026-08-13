#!/usr/bin/env node
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { Unzip, UnzipInflate, UnzipPassThrough } from "fflate";

const DIAGNOSTICS_FORMAT = "starsector-preflight-diagnostics-v1";
const BENCHMARK_FORMAT = "starsector-preflight-desktop-benchmark-v1";
const RECORD_FORMAT = "preflight-community-benchmark-v1";
const DATASET_FORMAT = "preflight-community-benchmark-dataset-v1";
const MAX_ARCHIVE_BYTES = 6 * 1024 * 1024;
const MAX_ENTRY_BYTES = 512 * 1024;
const MAX_TOTAL_BYTES = 6 * 1024 * 1024;
const MAX_ENTRIES = 512;

export function benchmarkRecordsFromArchive(archive, source = "report.zip") {
  const bytes = Uint8Array.from(archive);
  if (bytes.byteLength === 0 || bytes.byteLength > MAX_ARCHIVE_BYTES) {
    throw new Error(`${source}: archive is outside the 6 MiB report limit`);
  }
  const files = extractBounded(bytes, source);
  const manifestBytes = files.get("manifest.json");
  if (!manifestBytes) throw new Error(`${source}: archive has no manifest.json`);
  const manifest = jsonObject(manifestBytes, `${source}: manifest.json`);
  if (manifest.format !== DIAGNOSTICS_FORMAT) {
    throw new Error(`${source}: unsupported diagnostics format`);
  }
  if (!["mac", "windows", "linux"].includes(manifest.platform)) {
    throw new Error(`${source}: manifest has an unsupported platform`);
  }
  if (typeof manifest.engineVersion !== "string" || !manifest.engineVersion.trim()) {
    throw new Error(`${source}: manifest has no engine version`);
  }
  if (!Array.isArray(manifest.included) || !Array.isArray(manifest.selectedRuns)) {
    throw new Error(`${source}: manifest is missing its evidence inventory`);
  }

  const declared = new Map();
  for (const item of manifest.included) {
    if (!item || typeof item !== "object" || typeof item.entry !== "string"
        || !Number.isSafeInteger(item.bytes) || item.bytes < 0
        || typeof item.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(item.sha256)) {
      throw new Error(`${source}: manifest has an invalid included entry`);
    }
    if (declared.has(item.entry)) {
      throw new Error(`${source}: manifest has a duplicate included entry ${item.entry}`);
    }
    declared.set(item.entry, item);
  }
  verifyEvidenceInventory(files, declared, source);

  const archiveDigest = sha256(bytes);
  const records = [];
  for (const [name, evidence] of files) {
    const match = /^runs\/([1-9][0-9]?)\/benchmark-result\.json$/.exec(name);
    if (!match) continue;
    const declaration = declared.get(name);
    if (!declaration || declaration.bytes !== evidence.byteLength || declaration.sha256 !== sha256(evidence)) {
      throw new Error(`${source}: manifest doesn't match ${name}`);
    }
    const rank = Number(match[1]);
    const selected = manifest.selectedRuns[rank - 1];
    if (!selected || selected.rank !== rank || !Number.isSafeInteger(selected.modifiedMillis)) {
      throw new Error(`${source}: ${name} isn't tied to a selected run`);
    }
    const result = jsonObject(evidence, `${source}: ${name}`);
    records.push(normalizeBenchmark(result, {
      archiveDigest,
      rank,
      modifiedMillis: selected.modifiedMillis,
      platform: manifest.platform,
      productVersion: manifest.engineVersion,
      source,
    }));
  }
  if (!records.length) {
    throw new Error(`${source}: archive contains no paired benchmark result`);
  }
  return records;
}

export function buildBenchmarkDataset(inputs) {
  const records = inputs.flatMap(({ archive, source }) => benchmarkRecordsFromArchive(archive, source));
  const unique = new Map();
  for (const record of records) unique.set(record.submissionId, record);
  const submissions = [...unique.values()].sort((left, right) =>
    right.improvementPercent - left.improvementPercent
      || left.optimizedMs - right.optimizedMs
      || left.submissionId.localeCompare(right.submissionId));

  const byPlatform = {};
  for (const platform of ["windows", "mac", "linux"]) {
    const group = submissions.filter((record) => record.platform === platform);
    if (group.length) byPlatform[platform] = aggregate(group);
  }
  return {
    format: DATASET_FORMAT,
    generatedAt: new Date().toISOString(),
    aggregates: {
      ...aggregate(submissions),
      byPlatform,
    },
    leaderboard: submissions.map((record) => record.submissionId),
    submissions,
  };
}

function normalizeBenchmark(result, source) {
  if (result.format !== BENCHMARK_FORMAT || result.status !== "passed" || result.complete !== true) {
    throw new Error(`${source.source}: run ${source.rank} is not a completed paired benchmark`);
  }
  const comparison = object(result.comparison);
  const metrics = object(comparison?.metrics);
  const startup = object(metrics?.processToMainMenuMs);
  const measurementOnlyMs = positiveNumber(startup?.measurementOnly, "measurement-only startup");
  const optimizedMs = positiveNumber(startup?.optimized, "optimized startup");
  const calculatedImprovement = round(100 * (measurementOnlyMs - optimizedMs) / measurementOnlyMs, 3);
  if (startup?.improvementPercent !== null && startup?.improvementPercent !== undefined) {
    const declared = finiteNumber(startup.improvementPercent, "declared improvement");
    if (Math.abs(declared - calculatedImprovement) > 0.1) {
      throw new Error(`${source.source}: run ${source.rank} has an inconsistent improvement percentage`);
    }
  }

  const context = object(comparison?.context);
  const optimized = object(context?.optimized);
  const storage = object(context?.storage);
  const overheads = object(context?.measurementOverhead);
  const measurementOnlyOverhead = object(overheads?.measurementOnly);
  const optimizedOverhead = object(overheads?.optimized);
  const recordedAt = new Date(source.modifiedMillis);
  if (!Number.isFinite(recordedAt.getTime())) {
    throw new Error(`${source.source}: run ${source.rank} has an invalid evidence timestamp`);
  }

  const safeCore = {
    productVersion: source.productVersion,
    platform: source.platform,
    recordedAt: recordedAt.toISOString(),
    measurementOnlyMs,
    optimizedMs,
    improvementPercent: calculatedImprovement,
  };
  const submissionId = sha256(new TextEncoder().encode(JSON.stringify(safeCore))).slice(0, 20);
  return {
    format: RECORD_FORMAT,
    submissionId,
    ...safeCore,
    runtime: optionalRuntime(optimized),
    storage: optionalStorage(storage),
    measurementOverhead: optionalOverhead(measurementOnlyOverhead, optimizedOverhead),
  };
}

function optionalRuntime(value) {
  if (!value) return null;
  return compactObject({
    cacheHits: optionalInteger(value.cacheHits, 0),
    cacheMisses: optionalInteger(value.cacheMisses, 0),
    fallbacks: optionalInteger(value.fallbacks, 0),
    failures: optionalInteger(value.failures, 0),
    memoryAvailablePercent: optionalNumber(value.memoryAvailablePercent, 0, 100),
  });
}

function optionalStorage(value) {
  if (!value) return null;
  return compactObject({
    bytes: optionalInteger(value.bytes, 0),
    files: optionalInteger(value.files, 0),
  });
}

function optionalOverhead(measurementOnly, optimized) {
  const normalized = {
    measurementOnlyRouteSharePercent: optionalNumber(measurementOnly?.routeSharePercent, 0, 100),
    optimizedRouteSharePercent: optionalNumber(optimized?.routeSharePercent, 0, 100),
    measurementOnlyWithinBudget: typeof measurementOnly?.withinBudget === "boolean"
      ? measurementOnly.withinBudget : undefined,
    optimizedWithinBudget: typeof optimized?.withinBudget === "boolean"
      ? optimized.withinBudget : undefined,
  };
  return compactObject(normalized);
}

function aggregate(records) {
  return {
    count: records.length,
    medianMeasurementOnlyMs: median(records.map((record) => record.measurementOnlyMs)),
    medianOptimizedMs: median(records.map((record) => record.optimizedMs)),
    medianImprovementPercent: median(records.map((record) => record.improvementPercent)),
  };
}

function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  const value = sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  return round(value, 3);
}

function verifyEvidenceInventory(files, declared, source) {
  for (const [name, evidence] of files) {
    if (name === "README.txt" || name === "manifest.json") continue;
    const declaration = declared.get(name);
    if (!declaration) {
      throw new Error(`${source}: manifest doesn't inventory ${name}`);
    }
    if (declaration.bytes !== evidence.byteLength || declaration.sha256 !== sha256(evidence)) {
      throw new Error(`${source}: manifest doesn't match ${name}`);
    }
  }
  for (const name of declared.keys()) {
    if (!files.has(name)) {
      throw new Error(`${source}: manifest inventories missing evidence ${name}`);
    }
  }
}

function extractBounded(archive, source) {
  const files = new Map();
  let total = 0;
  let count = 0;
  let failure;
  const unzip = new Unzip((file) => {
    try {
      count += 1;
      if (count > MAX_ENTRIES) throw new Error("archive has too many entries");
      if (file.name.startsWith("/") || file.name.includes("\\") || file.name.includes("..") || file.name.endsWith("/")) {
        throw new Error(`archive has unsafe entry ${file.name}`);
      }
      if (files.has(file.name)) throw new Error(`archive has duplicate entry ${file.name}`);
      if (file.compression !== 0 && file.compression !== 8) {
        throw new Error(`archive uses unsupported compression for ${file.name}`);
      }
      if (file.originalSize !== undefined && file.originalSize > MAX_ENTRY_BYTES) {
        throw new Error(`${file.name} exceeds the 512 KiB entry limit`);
      }
      const chunks = [];
      let size = 0;
      file.ondata = (error, data, final) => {
        if (error && !failure) failure = new Error(`couldn't decompress ${file.name}`);
        if (failure) return;
        size += data.byteLength;
        total += data.byteLength;
        if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
          failure = new Error(`${file.name} exceeds the bounded extraction limits`);
          file.terminate();
          return;
        }
        chunks.push(data.slice());
        if (final) files.set(file.name, concatenate(chunks, size));
      };
      file.start();
    } catch (error) {
      failure ??= error instanceof Error ? error : new Error(String(error));
      file.terminate();
    }
  });
  unzip.register(UnzipPassThrough);
  unzip.register(UnzipInflate);
  try {
    unzip.push(archive, true);
  } catch (error) {
    failure ??= error instanceof Error ? error : new Error(String(error));
  }
  if (failure) throw new Error(`${source}: ${failure.message}`);
  return files;
}

function concatenate(chunks, size) {
  const output = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
}

function jsonObject(bytes, label) {
  let value;
  try {
    value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch (error) {
    throw new Error(`${label} is not valid UTF-8 JSON: ${error instanceof Error ? error.message : error}`);
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object`);
  }
  return value;
}

function object(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function positiveNumber(value, label) {
  const number = finiteNumber(value, label);
  if (number <= 0) throw new Error(`${label} must be positive`);
  return number;
}

function finiteNumber(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value)) throw new Error(`${label} must be finite`);
  return value;
}

function optionalInteger(value, minimum) {
  return Number.isSafeInteger(value) && value >= minimum ? value : undefined;
}

function optionalNumber(value, minimum, maximum) {
  return typeof value === "number" && Number.isFinite(value) && value >= minimum && value <= maximum
    ? value : undefined;
}

function compactObject(value) {
  const entries = Object.entries(value).filter(([, item]) => item !== undefined);
  return entries.length ? Object.fromEntries(entries) : null;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function round(value, places) {
  const scale = 10 ** places;
  return Math.round(value * scale) / scale;
}

function usage() {
  return "Usage: npm run benchmark:dataset -- [--output dataset.json] accepted-report.zip [...]";
}

async function main(argv) {
  let output;
  const paths = [];
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === "--output") {
      output = argv[++index];
      if (!output) throw new Error("--output requires a path");
    } else if (argv[index] === "--help" || argv[index] === "-h") {
      console.log(usage());
      return;
    } else {
      paths.push(argv[index]);
    }
  }
  if (!paths.length) throw new Error(usage());
  const dataset = buildBenchmarkDataset(paths.map((path) => ({
    source: path,
    archive: readFileSync(path),
  })));
  const json = `${JSON.stringify(dataset, null, 2)}\n`;
  if (output) writeFileSync(output, json, { flag: "wx" });
  else process.stdout.write(json);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`benchmark-dataset: ${error instanceof Error ? error.message : error}`);
    process.exitCode = 1;
  });
}
