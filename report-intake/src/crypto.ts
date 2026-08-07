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

async function signature(secret: string, value: string): Promise<string> {
  const bytes = await crypto.subtle.sign("HMAC", await hmacKey(secret), encoder.encode(value));
  return base64url(new Uint8Array(bytes));
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
    && Number.isInteger(claims.exp);
}

export async function sha256Hex(bytes: ArrayBuffer | Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function signReceipt(secret: string, receipt: object): Promise<string> {
  return signature(secret, JSON.stringify(receipt));
}
