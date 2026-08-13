import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { strToU8, zipSync } from "fflate";

const PROTOCOL_VERSION = 1;
const BUNDLE_FORMAT = "starsector-preflight-diagnostics-v1";
const SUPPORT_EVIDENCE_FORMAT = "starsector-preflight-support-evidence-v1";
const ZIP_CONTENT_TYPE = "application/zip";
const MAX_RESPONSE_BYTES = 64 * 1024;

export async function runCanary(requestedOrigin, fetchImpl = fetch) {
  const origin = exactHttpsOrigin(requestedOrigin);
  const archive = canaryBundle();
  const sha256 = createHash("sha256").update(archive).digest("hex");
  let deletion;
  let deleted = false;

  try {
    const health = await requestJson(fetchImpl, `${origin}/healthz`, { method: "GET" }, 200, "health check");
    if (health.status !== "ok" || health.protocolVersion !== PROTOCOL_VERSION) {
      throw new Error("health check returned an unsupported protocol");
    }

    const grant = await requestJson(fetchImpl, `${origin}/v1/cases`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        protocolVersion: PROTOCOL_VERSION,
        productVersion: "production-canary",
        bytes: archive.byteLength,
        sha256,
      }),
    }, 201, "case creation");
    const caseId = requiredCaseId(grant.caseId);
    const upload = endpoint(grant.upload, origin, `/v1/cases/${caseId}/archive`, "PUT");
    const finalize = endpoint(grant.finalize, origin, `/v1/cases/${caseId}/finalize`, "POST");
    deletion = endpoint(grant.deletion, origin, `/v1/cases/${caseId}`, "DELETE");

    const uploaded = await requestJson(fetchImpl, upload.url, {
      method: "PUT",
      headers: {
        authorization: `Bearer ${upload.token}`,
        "content-length": String(archive.byteLength),
        "content-type": ZIP_CONTENT_TYPE,
      },
      body: archive,
    }, 200, "archive upload");
    if (uploaded.status !== "uploaded" || uploaded.caseId !== caseId
        || uploaded.bytes !== archive.byteLength || uploaded.sha256 !== sha256) {
      throw new Error("archive upload receipt doesn't match the canary");
    }

    const receipt = await requestJson(fetchImpl, finalize.url, {
      method: "POST",
      headers: { authorization: `Bearer ${finalize.token}` },
    }, 200, "case finalization");
    if (receipt.protocolVersion !== PROTOCOL_VERSION || receipt.caseId !== caseId
        || receipt.objectKey !== `accepted/${caseId}.zip` || receipt.bytes !== archive.byteLength
        || receipt.sha256 !== sha256 || receipt.productVersion !== "production-canary"
        || !isIsoInstant(receipt.receivedAt) || !isIsoInstant(receipt.retentionDeadline)
        || typeof receipt.signature !== "string" || !/^[A-Za-z0-9_-]+$/.test(receipt.signature)) {
      throw new Error("final receipt doesn't match the canary");
    }
    deletion = endpoint(receipt.deletion, origin, `/v1/cases/${caseId}`, "DELETE");

    await requestEmpty(fetchImpl, deletion.url, {
      method: "DELETE",
      headers: { authorization: `Bearer ${deletion.token}` },
    }, 204, "case deletion");
    deleted = true;

    const afterDeletion = await fetchImpl(finalize.url, requestOptions({
      method: "POST",
      headers: { authorization: `Bearer ${finalize.token}` },
    }));
    const afterDeletionBody = await responseText(afterDeletion, "deletion confirmation");
    if (afterDeletion.status !== 409 || !afterDeletionBody.includes("archive hasn't been accepted")) {
      throw new Error(`deletion confirmation returned HTTP ${afterDeletion.status}`);
    }

    return {
      format: "preflight-report-intake-canary-v1",
      origin,
      caseId,
      archiveBytes: archive.byteLength,
      sha256,
      receivedAt: receipt.receivedAt,
      retentionDeadline: receipt.retentionDeadline,
      deleted: true,
    };
  } finally {
    if (deletion && !deleted) {
      try {
        await fetchImpl(deletion.url, requestOptions({
          method: "DELETE",
          headers: { authorization: `Bearer ${deletion.token}` },
        }));
      } catch {
        // Preserve the original error. The case ID appears in any successful creation response and
        // can be removed through the operator path if this best-effort cleanup cannot reach intake.
      }
    }
  }
}

export function canaryBundle(now = new Date()) {
  const evidence = strToU8(JSON.stringify({
    format: SUPPORT_EVIDENCE_FORMAT,
    source: "run.json",
    records: [{ fields: { state: "production-canary", status: "synthetic" } }],
  }));
  const entry = "runs/1/run.json";
  const manifest = {
    format: BUNDLE_FORMAT,
    createdAt: now.toISOString(),
    platform: process.platform === "win32" ? "windows" : process.platform === "linux" ? "linux" : "mac",
    engineVersion: "production-canary",
    selectedRuns: [{ rank: 1, modifiedMillis: now.getTime() }],
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
      sha256: createHash("sha256").update(evidence).digest("hex"),
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
  return zipSync({
    "README.txt": strToU8("Synthetic Preflight report-intake production canary. Contains no game or user data.\n"),
    "manifest.json": strToU8(`${JSON.stringify(manifest)}\n`),
    [entry]: evidence,
  });
}

function exactHttpsOrigin(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("canary origin must be an absolute HTTPS origin");
  }
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/"
      || url.search || url.hash || url.hostname.endsWith(".invalid")) {
    throw new Error("canary origin must be an exact configured HTTPS origin");
  }
  return url.origin;
}

function requiredCaseId(value) {
  if (typeof value !== "string" || !/^[0-9a-f-]{36}$/.test(value)) {
    throw new Error("case creation returned an invalid case ID");
  }
  return value;
}

function endpoint(value, origin, pathname, method) {
  if (!value || typeof value !== "object" || value.method !== method
      || typeof value.url !== "string" || typeof value.token !== "string"
      || value.token.length < 32 || value.token.length > 16_384) {
    throw new Error(`case creation returned an invalid ${method} endpoint`);
  }
  const url = new URL(value.url);
  if (url.origin !== origin || url.pathname !== pathname || url.search || url.hash) {
    throw new Error(`case creation returned an out-of-bound ${method} endpoint`);
  }
  return { url: url.toString(), token: value.token };
}

async function requestJson(fetchImpl, url, options, expectedStatus, label) {
  const response = await fetchImpl(url, requestOptions(options));
  const text = await responseText(response, label);
  if (response.status !== expectedStatus) {
    throw new Error(`${label} returned HTTP ${response.status}: ${text.slice(0, 512)}`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error(`${label} didn't return JSON`);
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} didn't return a JSON object`);
  }
  return value;
}

async function requestEmpty(fetchImpl, url, options, expectedStatus, label) {
  const response = await fetchImpl(url, requestOptions(options));
  const text = await responseText(response, label);
  if (response.status !== expectedStatus || text) {
    throw new Error(`${label} returned HTTP ${response.status} with an unexpected body`);
  }
}

function requestOptions(options) {
  return { ...options, redirect: "manual", signal: AbortSignal.timeout(30_000) };
}

async function responseText(response, label) {
  const declared = response.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_RESPONSE_BYTES)) {
    throw new Error(`${label} response is too large`);
  }
  const text = await response.text();
  if (new TextEncoder().encode(text).byteLength > MAX_RESPONSE_BYTES) {
    throw new Error(`${label} response is too large`);
  }
  return text;
}

function isIsoInstant(value) {
  return typeof value === "string" && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(value)
    && Number.isFinite(Date.parse(value));
}

const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const origin = process.argv[2] ?? process.env.PREFLIGHT_REPORT_INTAKE_ORIGIN;
  if (!origin) throw new Error("Usage: npm run canary -- https://reports.example.com");
  console.log(JSON.stringify(await runCanary(origin), null, 2));
}
