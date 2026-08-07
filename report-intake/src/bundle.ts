import { Unzip, UnzipInflate, UnzipPassThrough } from "fflate";
import {
  BENCHMARK_FILES,
  BUNDLE_FORMAT,
  MAX_ARCHIVE_ENTRIES,
  MAX_CONTENT_BYTES,
  MAX_FILE_BYTES,
  MAX_MANIFEST_BYTES,
  MAX_README_BYTES,
  RUN_FILES,
} from "./protocol";
import { sha256Hex } from "./crypto";

type IncludedEntry = { entry: string; bytes: number; sha256: string };

const MANIFEST_KEYS = new Set([
  "format",
  "createdAt",
  "platform",
  "engineVersion",
  "selectedRuns",
  "selectedBenchmarks",
  "limits",
  "redactions",
  "included",
  "skipped",
  "excludedCategories",
]);

const EXCLUDED_CATEGORIES = [
  "acceleration caches",
  "game and mod files",
  "saves",
  "logs and crash dumps",
  "JFR recordings",
  "screenshots and audio",
  "unknown filenames",
  "symbolic links",
];

export type VerifiedBundle = {
  format: string;
  entries: number;
  contentBytes: number;
  evidenceEntries: number;
};

export async function verifyDiagnosticBundle(archive: Uint8Array): Promise<VerifiedBundle> {
  if (archive.byteLength < 4 || archive[0] !== 0x50 || archive[1] !== 0x4b) {
    throw new Error("archive is not a ZIP file");
  }

  const files = extractBounded(archive);
  const readme = files.get("README.txt");
  const manifestBytes = files.get("manifest.json");
  if (!readme || !manifestBytes) throw new Error("archive is missing README.txt or manifest.json");

  for (const [name, bytes] of files) {
    decodeUtf8(bytes, name);
  }

  let manifest: unknown;
  try {
    manifest = JSON.parse(decodeUtf8(manifestBytes, "manifest.json"));
  } catch (error) {
    throw new Error(`manifest.json is invalid: ${message(error)}`);
  }
  if (!manifest || typeof manifest !== "object") throw new Error("manifest.json must be an object");
  const value = manifest as Record<string, unknown>;
  const sessions = validateManifest(value);

  const declared = new Map<string, IncludedEntry>();
  for (const raw of value.included as unknown[]) {
    if (!isIncluded(raw)) throw new Error("manifest.json has an invalid included entry");
    if (declared.has(raw.entry)) throw new Error("manifest.json has a duplicate included entry");
    declared.set(raw.entry, raw);
  }

  const evidence = [...files.keys()].filter((name) => name !== "README.txt" && name !== "manifest.json");
  if (declared.size !== evidence.length) throw new Error("manifest.json doesn't inventory every evidence entry");
  let contentBytes = 0;
  for (const name of evidence) {
    const bytes = files.get(name)!;
    validateEvidenceText(name, decodeUtf8(bytes, name));
    const entry = declared.get(name);
    if (!entry || entry.bytes !== bytes.byteLength || entry.sha256 !== await sha256Hex(bytes)) {
      throw new Error(`manifest.json doesn't match ${name}`);
    }
    const [kind, rankText] = name.split("/");
    const selected = kind === "runs" ? sessions.runs : sessions.benchmarks;
    if (!selected.has(Number(rankText))) throw new Error(`${name} isn't part of a selected session`);
    contentBytes += bytes.byteLength;
  }
  if (contentBytes > MAX_CONTENT_BYTES) throw new Error("evidence exceeds the content limit");

  return {
    format: BUNDLE_FORMAT,
    entries: files.size,
    contentBytes,
    evidenceEntries: evidence.length,
  };
}

function validateEvidenceText(name: string, text: string): void {
  if (name.endsWith(".json")) {
    let value: unknown;
    try {
      value = JSON.parse(text);
    } catch (error) {
      throw new Error(`${name} is not valid JSON: ${message(error)}`);
    }
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error(`${name} must be a JSON object`);
    }
    return;
  }
  if (!name.endsWith(".jsonl")) throw new Error(`${name} has an unsupported text format`);
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (!line.trim()) continue;
    let value: unknown;
    try {
      value = JSON.parse(line);
    } catch (error) {
      throw new Error(`${name} line ${index + 1} is not valid JSON: ${message(error)}`);
    }
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error(`${name} line ${index + 1} must be a JSON object`);
    }
  }
}

function validateManifest(value: Record<string, unknown>): { runs: Set<number>; benchmarks: Set<number> } {
  if (!exactKeys(value, MANIFEST_KEYS)) throw new Error("manifest.json has an unsupported schema");
  if (value.format !== BUNDLE_FORMAT) throw new Error("unsupported diagnostics format");
  if (!isIsoInstant(value.createdAt)) throw new Error("manifest.json has an invalid createdAt");
  if (value.platform !== "mac" && value.platform !== "windows" && value.platform !== "linux") {
    throw new Error("manifest.json has an invalid platform");
  }
  if (!isBoundedText(value.engineVersion, 1, 128)) throw new Error("manifest.json has an invalid engineVersion");

  const limits = record(value.limits, "manifest.json limits must be an object");
  if (!exactKeys(limits, new Set([
    "maximumRuns",
    "maximumBenchmarks",
    "maximumFileBytes",
    "maximumContentBytes",
  ]))) throw new Error("manifest.json has invalid limits");
  if (!boundedInteger(limits.maximumRuns, 0, 20)
      || !boundedInteger(limits.maximumBenchmarks, 0, 20)
      || limits.maximumFileBytes !== MAX_FILE_BYTES
      || limits.maximumContentBytes !== MAX_CONTENT_BYTES) {
    throw new Error("manifest.json limits don't match the diagnostics format");
  }

  const runs = sessions(value.selectedRuns, limits.maximumRuns as number, "selectedRuns");
  const benchmarks = sessions(
    value.selectedBenchmarks,
    limits.maximumBenchmarks as number,
    "selectedBenchmarks",
  );
  if (!Array.isArray(value.included) || value.included.length > MAX_ARCHIVE_ENTRIES) {
    throw new Error("manifest.json included must be a bounded array");
  }
  if (!Array.isArray(value.skipped) || value.skipped.length > MAX_ARCHIVE_ENTRIES) {
    throw new Error("manifest.json skipped must be a bounded array");
  }
  const skipped = new Set<string>();
  for (const raw of value.skipped) {
    const entry = record(raw, "manifest.json has an invalid skipped entry");
    if (!exactKeys(entry, new Set(["entry", "reason"]))
        || typeof entry.entry !== "string"
        || !isEvidenceEntry(entry.entry)
        || !isBoundedText(entry.reason, 1, 256)
        || skipped.has(entry.entry)) {
      throw new Error("manifest.json has an invalid skipped entry");
    }
    skipped.add(entry.entry);
  }
  if (!equalStringArray(value.redactions, ["current user home -> <home>"])) {
    throw new Error("manifest.json has an invalid redactions disclosure");
  }
  if (!equalStringArray(value.excludedCategories, EXCLUDED_CATEGORIES)) {
    throw new Error("manifest.json has an invalid excludedCategories disclosure");
  }
  return { runs, benchmarks };
}

function sessions(value: unknown, maximum: number, name: string): Set<number> {
  if (!Array.isArray(value) || value.length > maximum) {
    throw new Error(`manifest.json ${name} exceeds its declared maximum`);
  }
  const ranks = new Set<number>();
  for (let index = 0; index < value.length; index++) {
    const session = record(value[index], `manifest.json has an invalid ${name} entry`) as Record<string, unknown>;
    if (!exactKeys(session, new Set(["rank", "modifiedMillis"]))
        || session.rank !== index + 1
        || !boundedInteger(session.modifiedMillis, 0, Number.MAX_SAFE_INTEGER)) {
      throw new Error(`manifest.json has an invalid ${name} entry`);
    }
    ranks.add(session.rank as number);
  }
  return ranks;
}

function extractBounded(archive: Uint8Array): Map<string, Uint8Array> {
  const files = new Map<string, Uint8Array>();
  let totalOutput = 0;
  let failure: Error | undefined;
  const unzip = new Unzip((file) => {
    try {
      if (files.size >= MAX_ARCHIVE_ENTRIES) throw new Error("archive has too many entries");
      validateEntryName(file.name);
      if (files.has(file.name)) throw new Error(`archive has duplicate entry ${file.name}`);
      if (file.compression !== 0 && file.compression !== 8) {
        throw new Error(`archive uses unsupported compression for ${file.name}`);
      }
      const limit = entryLimit(file.name);
      if (file.originalSize !== undefined && file.originalSize > limit) {
        throw new Error(`${file.name} exceeds its decompressed size limit`);
      }
      const chunks: Uint8Array[] = [];
      let size = 0;
      file.ondata = (error, data, final) => {
        if (error && !failure) failure = new Error(`couldn't decompress ${file.name}`);
        if (failure) return;
        size += data.byteLength;
        totalOutput += data.byteLength;
        if (size > limit || totalOutput > MAX_CONTENT_BYTES + MAX_MANIFEST_BYTES + MAX_README_BYTES) {
          failure = new Error(`${file.name} exceeds the diagnostics limits`);
          file.terminate();
          return;
        }
        chunks.push(data.slice());
        if (final) files.set(file.name, concatenate(chunks, size));
      };
      file.start();
    } catch (error) {
      failure ??= new Error(message(error));
      file.terminate();
    }
  });
  unzip.register(UnzipPassThrough);
  unzip.register(UnzipInflate);
  try {
    unzip.push(archive, true);
  } catch (error) {
    failure ??= new Error(`invalid ZIP archive: ${message(error)}`);
  }
  if (failure) throw failure;
  return files;
}

function validateEntryName(name: string): void {
  if (name === "README.txt" || name === "manifest.json") return;
  if (name.startsWith("/") || name.includes("\\") || name.includes("..") || name.endsWith("/")) {
    throw new Error(`archive has unsafe entry ${name}`);
  }
  const match = /^(runs|benchmarks)\/([1-9][0-9]?)\/([^/]+)$/.exec(name);
  if (!match) throw new Error(`archive has unexpected entry ${name}`);
  const [, kind, rankText, filename] = match;
  const rank = Number(rankText);
  if (rank > 20) throw new Error(`archive has out-of-range session rank in ${name}`);
  const allowlist = kind === "runs" ? RUN_FILES : BENCHMARK_FILES;
  if (!allowlist.has(filename)) throw new Error(`archive has unexpected entry ${name}`);
}

function entryLimit(name: string): number {
  if (name === "README.txt") return MAX_README_BYTES;
  if (name === "manifest.json") return MAX_MANIFEST_BYTES;
  return MAX_FILE_BYTES;
}

function decodeUtf8(bytes: Uint8Array, name: string): string {
  try {
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
  } catch {
    throw new Error(`${name} is not valid UTF-8 text`);
  }
}

function concatenate(chunks: Uint8Array[], size: number): Uint8Array {
  const output = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
}

function isIncluded(value: unknown): value is IncludedEntry {
  if (!value || typeof value !== "object") return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.entry === "string"
    && isEvidenceEntry(entry.entry)
    && Number.isSafeInteger(entry.bytes)
    && (entry.bytes as number) >= 0
    && typeof entry.sha256 === "string"
    && /^[0-9a-f]{64}$/.test(entry.sha256);
}

function exactKeys(value: Record<string, unknown>, expected: Set<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.size && keys.every((key) => expected.has(key));
}

function record(value: unknown, error: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(error);
  return value as Record<string, unknown>;
}

function boundedInteger(value: unknown, minimum: number, maximum: number): boolean {
  return Number.isSafeInteger(value) && (value as number) >= minimum && (value as number) <= maximum;
}

function isBoundedText(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === "string"
    && value.length >= minimum
    && value.length <= maximum
    && !/[\u0000-\u001f\u007f]/.test(value);
}

function isIsoInstant(value: unknown): value is string {
  return typeof value === "string"
    && value.length <= 64
    && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value)
    && Number.isFinite(Date.parse(value));
}

function equalStringArray(value: unknown, expected: string[]): boolean {
  return Array.isArray(value)
    && value.length === expected.length
    && value.every((entry, index) => entry === expected[index]);
}

function isEvidenceEntry(name: string): boolean {
  try {
    validateEntryName(name);
    return name !== "README.txt" && name !== "manifest.json";
  } catch {
    return false;
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
