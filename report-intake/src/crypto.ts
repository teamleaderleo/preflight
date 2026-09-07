import type { GrantClaims, GrantPurpose } from "./protocol";
import { PROTOCOL_VERSION } from "./protocol";

const encoder = new TextEncoder();

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function decodeBase64url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const padded = value.replaceAll("-", "+").replaceAll("_", "/")
    + "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(padded);
  const decoded = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (base64url(decoded) !== value) throw new Error("non-canonical base64url");
  return decoded;
}

async function hmacKey(secret: string): Promise<CryptoKey> {
  if (encoder.encode(secret).byteLength < 32) throw new Error("REPORT_SIGNING_KEY is too short");
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

async function hmac(secret: string, value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret), encoder.encode(value)));
}

async function signature(secret: string, value: string): Promise<string> {
  return base64url(await hmac(secret, value));
}

export async function deriveReportCaseId(
  secret: string,
  transactionId: string,
  productVersion: string,
  bytes: number,
  sha256: string,
): Promise<string> {
  const digest = await hmac(
    secret,
    JSON.stringify(["preflight-report-case-v1", transactionId, productVersion, bytes, sha256]),
  );
  const id = digest.slice(0, 16);
  // RFC 9562 UUIDv8 marks this as an application-defined, keyed deterministic identifier while
  // retaining the case-id syntax already used by the intake contract.
  id[6] = (id[6] & 0x0f) | 0x80;
  id[8] = (id[8] & 0x3f) | 0x80;
  const hex = Array.from(id, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export async function signGrant(secret: string, claims: GrantClaims): Promise<string> {
  const payload = base64url(encoder.encode(JSON.stringify(claims)));
  return `${payload}.${await signature(secret, payload)}`;
}

export async function verifyGrant(
  secret: string,
  token: string,
  purpose: GrantPurpose,
  caseId: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<GrantClaims> {
  const parts = token.split(".");
  if (parts.length !== 2) throw new Error("invalid grant");
  const [payload, suppliedSignature] = parts;
  const valid = await crypto.subtle.verify(
    "HMAC",
    await hmacKey(secret),
    decodeBase64url(suppliedSignature),
    encoder.encode(payload),
  );
  if (!valid) throw new Error("invalid grant");

  let claims: unknown;
  try {
    claims = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(decodeBase64url(payload)));
  } catch {
    throw new Error("invalid grant");
  }
  if (!isClaims(claims)
      || claims.v !== PROTOCOL_VERSION
      || claims.purpose !== purpose
      || claims.caseId !== caseId
      || claims.exp < nowSeconds) {
    throw new Error("invalid or expired grant");
  }
  return claims;
}

function isClaims(value: unknown): value is GrantClaims {
  if (!value || typeof value !== "object") return false;
  const claims = value as Record<string, unknown>;
  return Number.isInteger(claims.v)
    && (claims.purpose === "upload" || claims.purpose === "delete")
    && typeof claims.caseId === "string"
    && typeof claims.objectKey === "string"
    && typeof claims.productVersion === "string"
    && Number.isSafeInteger(claims.bytes)
    && typeof claims.sha256 === "string"
    && Number.isInteger(claims.exp)
    && (claims.quotaDay === undefined || isQuotaDay(claims.quotaDay));
}

function isQuotaDay(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 10) === value;
}

export async function sha256Hex(bytes: ArrayBuffer | Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function signReceipt(secret: string, receipt: object): Promise<string> {
  return signature(secret, JSON.stringify(receipt));
}
