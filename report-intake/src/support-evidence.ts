import {
  MAX_SUPPORT_FIELD_PATH_CHARS,
  MAX_SUPPORT_FIELDS_PER_RECORD,
  MAX_SUPPORT_RECORDS,
  MAX_SUPPORT_STRING_CHARS,
  SUPPORT_EVIDENCE_FORMAT,
  SUPPORT_EVIDENCE_SEGMENTS,
} from "./protocol";

const ENVELOPE_KEYS = new Set(["format", "source", "records"]);
const RECORD_KEYS = new Set(["fields"]);

export function validateSupportEvidence(source: string, text: string): void {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`support evidence is not valid JSON: ${message(error)}`);
  }
  const envelope = record(parsed, "support evidence must be an object");
  if (!exactKeys(envelope, ENVELOPE_KEYS)) throw new Error("support evidence has an unsupported envelope");
  if (envelope.format !== SUPPORT_EVIDENCE_FORMAT) throw new Error("support evidence has an unsupported format");
  if (envelope.source !== source) throw new Error("support evidence source doesn't match its archive filename");
  if (!Array.isArray(envelope.records) || envelope.records.length > MAX_SUPPORT_RECORDS) {
    throw new Error("support evidence records exceed the schema limit");
  }

  for (const rawRecord of envelope.records) {
    const item = record(rawRecord, "support evidence record must be an object");
    if (!exactKeys(item, RECORD_KEYS)) throw new Error("support evidence record has an unsupported schema");
    const fields = record(item.fields, "support evidence fields must be an object");
    const entries = Object.entries(fields);
    if (entries.length > MAX_SUPPORT_FIELDS_PER_RECORD) {
      throw new Error("support evidence record has too many fields");
    }
    for (const [path, value] of entries) {
      validateFieldPath(path);
      validateScalar(value, path);
    }
  }
}

function validateFieldPath(path: string): void {
  if (path.length < 1 || path.length > MAX_SUPPORT_FIELD_PATH_CHARS) {
    throw new Error("support evidence field path is outside the schema limit");
  }
  const segments = path.split(".");
  if (segments.some((segment) => segment.length === 0)) {
    throw new Error(`support evidence has invalid field path ${path}`);
  }
  for (const segment of segments) {
    if (/^(0|[1-9][0-9]{0,2})$/.test(segment)) continue;
    if (!SUPPORT_EVIDENCE_SEGMENTS.has(segment)) {
      throw new Error(`support evidence has unsupported field ${path}`);
    }
  }
}

function validateScalar(value: unknown, path: string): void {
  if (value === null || typeof value === "boolean") return;
  if (typeof value === "number") {
    if (!Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER) {
      throw new Error(`support evidence has invalid numeric value at ${path}`);
    }
    return;
  }
  if (typeof value !== "string") throw new Error(`support evidence has non-scalar value at ${path}`);
  if (value.length > MAX_SUPPORT_STRING_CHARS || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new Error(`support evidence has invalid string value at ${path}`);
  }
  if (looksLikeAbsolutePath(value)) {
    throw new Error(`support evidence has absolute-path-looking value at ${path}`);
  }
}

function looksLikeAbsolutePath(text: string): boolean {
  const value = text.trim();
  if (value.startsWith("<home>")) return false;
  return value.startsWith("/")
    || value.startsWith("\\\\")
    || value.startsWith("file:")
    || /^[A-Za-z]:[\\/]/.test(value);
}

function exactKeys(value: Record<string, unknown>, expected: Set<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.size && keys.every((key) => expected.has(key));
}

function record(value: unknown, error: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(error);
  return value as Record<string, unknown>;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
