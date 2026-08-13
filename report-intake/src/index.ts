import { DailyReportQuota } from "./daily-quota";
import { verifyDiagnosticBundle } from "./bundle";
import { sha256Hex, signGrant, signReceipt, verifyGrant } from "./crypto";
import {
  type CreateCaseRequest,
  type GrantClaims,
  type Receipt,
  MAX_PRODUCT_VERSION_LENGTH,
  PROTOCOL_VERSION,
  ZIP_CONTENT_TYPE,
} from "./protocol";

export { DailyReportQuota } from "./daily-quota";

type IntakeEnv = Env & {
  DAILY_REPORT_QUOTA: DurableObjectNamespace<DailyReportQuota>;
  REPORT_SIGNING_KEY: string;
};

const DAILY_GRANT_LIMIT_BYTES = 500 * 1024 * 1024;

class HttpError extends Error {
  constructor(readonly status: number, message: string, readonly headers: HeadersInit = {}) {
    super(message);
  }
}

export default {
  async fetch(request, env): Promise<Response> {
    try {
      return await route(request, env as IntakeEnv);
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      if (status === 500) console.error("report intake failed", error);
      return json(
        { error: status === 500 ? "internal error" : message(error) },
        status,
        error instanceof HttpError ? error.headers : {},
      );
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: IntakeEnv): Promise<Response> {
  const url = new URL(request.url);
  const origin = publicOrigin(env);
  if (url.origin !== origin) throw new HttpError(421, "request origin doesn't match the configured intake origin");
  if (request.method === "GET" && url.pathname === "/healthz") {
    return json({ status: "ok", protocolVersion: PROTOCOL_VERSION });
  }
  if (request.method === "POST" || request.method === "PUT" || request.method === "DELETE") {
    await enforceRateLimit(env.MUTATION_RATE_LIMITER, request, "too many intake requests");
  }
  if (request.method === "POST" && url.pathname === "/v1/cases") {
    await enforceRateLimit(env.CASE_CREATION_RATE_LIMITER, request, "too many report cases");
    return createCase(request, env);
  }
  const match = /^\/v1\/cases\/([0-9a-f-]{36})\/(archive|finalize)$/.exec(url.pathname);
  if (match && request.method === "PUT" && match[2] === "archive") {
    return uploadArchive(request, env, match[1]);
  }
  if (match && request.method === "POST" && match[2] === "finalize") {
    return finalizeCase(request, env, match[1]);
  }
  const deleteMatch = /^\/v1\/cases\/([0-9a-f-]{36})$/.exec(url.pathname);
  if (deleteMatch && request.method === "DELETE") return deleteCase(request, env, deleteMatch[1]);
  throw new HttpError(404, "not found");
}

async function createCase(request: Request, env: IntakeEnv): Promise<Response> {
  requireJson(request);
  const body = await boundedJson(request, 2048);
  if (!isCreateCaseRequest(body)) throw new HttpError(400, "invalid case request");
  const maxUploadBytes = positiveInteger(env.MAX_UPLOAD_BYTES, "MAX_UPLOAD_BYTES");
  if (body.bytes < 1 || body.bytes > maxUploadBytes) throw new HttpError(413, "archive size is outside the allowed range");

  const now = new Date();
  const quota = env.DAILY_REPORT_QUOTA.getByName(now.toISOString().slice(0, 10));
  const reservation = await quota.reserve(body.bytes, DAILY_GRANT_LIMIT_BYTES);
  if (!reservation.accepted) {
    throw new HttpError(429, "daily report intake capacity has been reached", {
      "retry-after": String(secondsUntilNextUtcDay(now)),
    });
  }

  const nowSeconds = Math.floor(now.getTime() / 1000);
  const caseId = crypto.randomUUID();
  const objectKey = `accepted/${caseId}.zip`;
  const base = {
    v: PROTOCOL_VERSION,
    caseId,
    objectKey,
    productVersion: body.productVersion,
    bytes: body.bytes,
    sha256: body.sha256,
  };
  const uploadClaims: GrantClaims = {
    ...base,
    purpose: "upload",
    exp: nowSeconds + positiveInteger(env.GRANT_TTL_SECONDS, "GRANT_TTL_SECONDS"),
  };
  const deleteClaims: GrantClaims = {
    ...base,
    purpose: "delete",
    exp: nowSeconds + (positiveInteger(env.RETENTION_DAYS, "RETENTION_DAYS") + 2) * 86400,
  };
  return json({
    protocolVersion: PROTOCOL_VERSION,
    caseId,
    upload: {
      method: "PUT",
      url: new URL(`/v1/cases/${caseId}/archive`, publicOrigin(env)).toString(),
      contentType: ZIP_CONTENT_TYPE,
      expiresAt: new Date(uploadClaims.exp * 1000).toISOString(),
      token: await signGrant(env.REPORT_SIGNING_KEY, uploadClaims),
    },
    finalize: {
      method: "POST",
      url: new URL(`/v1/cases/${caseId}/finalize`, publicOrigin(env)).toString(),
      token: await signGrant(env.REPORT_SIGNING_KEY, uploadClaims),
    },
    deletion: {
      method: "DELETE",
      url: new URL(`/v1/cases/${caseId}`, publicOrigin(env)).toString(),
      token: await signGrant(env.REPORT_SIGNING_KEY, deleteClaims),
    },
  }, 201);
}

async function uploadArchive(request: Request, env: IntakeEnv, caseId: string): Promise<Response> {
  const claims = await authorized(request, env, "upload", caseId);
  if (request.headers.has("content-encoding")) throw new HttpError(415, "content-encoding isn't supported");
  if (request.headers.get("content-type")?.toLowerCase() !== ZIP_CONTENT_TYPE) {
    throw new HttpError(415, `content-type must be ${ZIP_CONTENT_TYPE}`);
  }
  const length = exactContentLength(request);
  if (length !== claims.bytes) throw new HttpError(400, "content-length doesn't match the grant");
  const maxUploadBytes = positiveInteger(env.MAX_UPLOAD_BYTES, "MAX_UPLOAD_BYTES");
  if (length > maxUploadBytes) throw new HttpError(413, "archive is too large");
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength !== claims.bytes) throw new HttpError(400, "received byte count doesn't match the grant");
  const digest = await sha256Hex(bytes);
  if (digest !== claims.sha256) throw new HttpError(400, "archive SHA-256 doesn't match the grant");
  let bundle;
  try {
    bundle = await verifyDiagnosticBundle(bytes);
  } catch (error) {
    throw new HttpError(400, `diagnostics bundle was rejected: ${message(error)}`);
  }

  const receivedAt = new Date().toISOString();
  const automaticDeletionAfter = new Date(
    Date.parse(receivedAt) + positiveInteger(env.RETENTION_DAYS, "RETENTION_DAYS") * 86400_000,
  ).toISOString();
  const retentionDeadline = new Date(
    Date.parse(automaticDeletionAfter) + 86400_000,
  ).toISOString();
  const stored = await env.REPORTS.put(claims.objectKey, bytes, {
    onlyIf: { etagDoesNotMatch: "*" },
    sha256: digest,
    httpMetadata: { contentType: ZIP_CONTENT_TYPE },
    customMetadata: {
      protocolVersion: String(PROTOCOL_VERSION),
      caseId,
      productVersion: claims.productVersion,
      sha256: digest,
      bytes: String(bytes.byteLength),
      receivedAt,
      automaticDeletionAfter,
      retentionDeadline,
      bundleFormat: bundle.format,
      evidenceEntries: String(bundle.evidenceEntries),
      contentBytes: String(bundle.contentBytes),
    },
  });
  if (!stored) throw new HttpError(409, "this case already has an uploaded archive");
  return json({ status: "uploaded", caseId, bytes: bytes.byteLength, sha256: digest });
}

async function finalizeCase(request: Request, env: IntakeEnv, caseId: string): Promise<Response> {
  const claims = await authorized(request, env, "upload", caseId);
  const object = await env.REPORTS.head(claims.objectKey);
  if (!object) throw new HttpError(409, "archive hasn't been accepted");
  const metadata = object.customMetadata ?? {};
  if (object.size !== claims.bytes || metadata.sha256 !== claims.sha256 || metadata.caseId !== caseId) {
    throw new HttpError(409, "stored archive doesn't match the case");
  }
  const deleteClaims: GrantClaims = {
    ...claims,
    purpose: "delete",
    exp: Math.floor(Date.parse(requiredMetadata(metadata, "retentionDeadline")) / 1000) + 86400,
  };
  const unsigned = {
    protocolVersion: PROTOCOL_VERSION,
    caseId,
    objectKey: claims.objectKey,
    bytes: object.size,
    sha256: claims.sha256,
    productVersion: claims.productVersion,
    receivedAt: requiredMetadata(metadata, "receivedAt"),
    retentionDeadline: requiredMetadata(metadata, "retentionDeadline"),
    deletion: {
      method: "DELETE" as const,
      url: new URL(`/v1/cases/${caseId}`, publicOrigin(env)).toString(),
      token: await signGrant(env.REPORT_SIGNING_KEY, deleteClaims),
    },
  };
  const receipt: Receipt = { ...unsigned, signature: await signReceipt(env.REPORT_SIGNING_KEY, unsigned) };
  return json(receipt);
}

async function deleteCase(request: Request, env: IntakeEnv, caseId: string): Promise<Response> {
  const claims = await authorized(request, env, "delete", caseId);
  await env.REPORTS.delete(claims.objectKey);
  return new Response(null, { status: 204 });
}

async function authorized(
  request: Request,
  env: IntakeEnv,
  purpose: "upload" | "delete",
  caseId: string,
): Promise<GrantClaims> {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ")) throw new HttpError(401, "missing bearer grant");
  try {
    return await verifyGrant(env.REPORT_SIGNING_KEY, authorization.slice(7), purpose, caseId);
  } catch {
    throw new HttpError(401, "invalid or expired bearer grant");
  }
}

async function boundedJson(request: Request, limit: number): Promise<unknown> {
  const length = request.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > limit)) throw new HttpError(413, "request body is too large");
  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > limit) throw new HttpError(413, "request body is too large");
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, "request body isn't valid JSON");
  }
}

function isCreateCaseRequest(value: unknown): value is CreateCaseRequest {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const body = value as Record<string, unknown>;
  return Object.keys(body).length === 4
    && body.protocolVersion === PROTOCOL_VERSION
    && typeof body.productVersion === "string"
    && body.productVersion.length >= 1
    && body.productVersion.length <= MAX_PRODUCT_VERSION_LENGTH
    && !/[\u0000-\u001f\u007f]/.test(body.productVersion)
    && Number.isSafeInteger(body.bytes)
    && typeof body.sha256 === "string"
    && /^[0-9a-f]{64}$/.test(body.sha256);
}

function requireJson(request: Request): void {
  if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
    throw new HttpError(415, "content-type must be application/json");
  }
}

function exactContentLength(request: Request): number {
  const value = request.headers.get("content-length");
  if (!value || !/^\d+$/.test(value)) throw new HttpError(411, "content-length is required");
  const length = Number(value);
  if (!Number.isSafeInteger(length)) throw new HttpError(400, "invalid content-length");
  return length;
}

function positiveInteger(value: string, name: string): number {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error(`${name} must be a positive integer`);
  return parsed;
}

function publicOrigin(env: IntakeEnv): string {
  let url: URL;
  try {
    url = new URL(env.PUBLIC_ORIGIN);
  } catch {
    throw new Error("PUBLIC_ORIGIN must be an absolute HTTPS origin");
  }
  if (url.protocol !== "https:"
      || url.username
      || url.password
      || url.pathname !== "/"
      || url.search
      || url.hash
      || url.origin.endsWith(".invalid")) {
    throw new Error("PUBLIC_ORIGIN must be a configured absolute HTTPS origin");
  }
  return url.origin;
}

function requiredMetadata(metadata: Record<string, string>, name: string): string {
  const value = metadata[name];
  if (!value) throw new HttpError(409, `stored archive is missing ${name}`);
  return value;
}

function secondsUntilNextUtcDay(now: Date): number {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}

async function enforceRateLimit(limiter: RateLimit, request: Request, detail: string): Promise<void> {
  const key = request.headers.get("cf-connecting-ip") ?? "unknown-client";
  const result = await limiter.limit({ key });
  if (!result.success) throw new HttpError(429, detail, { "retry-after": "60" });
}

function json(value: unknown, status = 200, extraHeaders: HeadersInit = {}): Response {
  return Response.json(value, {
    status,
    headers: {
      "cache-control": "no-store",
      "content-type": "application/json; charset=utf-8",
      "x-content-type-options": "nosniff",
      ...Object.fromEntries(new Headers(extraHeaders)),
    },
  });
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
