const encoder = new TextEncoder();

export const INSTALLATION_AUTH_VERSION = 2;
export const MAX_CHALLENGE_LIFETIME_SECONDS = 5 * 60;

export type InstallationAction = "recover" | "delete";

export interface InstallationChallenge {
  v: number;
  action: InstallationAction;
  nonce: string;
  exp: number;
  caseId?: string;
}

export type ConsumeInstallationNonce = (
  installationKeyId: string,
  challenge: InstallationChallenge,
) => Promise<boolean>;

export function canonicalInstallationChallenge(challenge: InstallationChallenge): string {
  validateChallenge(challenge, Number.MIN_SAFE_INTEGER);
  return [
    `v=${challenge.v}`,
    `action=${challenge.action}`,
    `case=${challenge.caseId ?? ""}`,
    `nonce=${challenge.nonce}`,
    `exp=${challenge.exp}`,
  ].join("\n");
}

export async function verifyInstallationRequest(
  publicKey: JsonWebKey,
  challenge: InstallationChallenge,
  signature: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<boolean> {
  validatePublicKey(publicKey);
  validateChallenge(challenge, nowSeconds);
  const key = await crypto.subtle.importKey(
    "jwk",
    publicKey,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    decodeBase64url(signature),
    encoder.encode(canonicalInstallationChallenge(challenge)),
  );
}

export async function authorizeInstallationRequest(
  publicKey: JsonWebKey,
  challenge: InstallationChallenge,
  signature: string,
  consumeNonce: ConsumeInstallationNonce,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<boolean> {
  if (!await verifyInstallationRequest(publicKey, challenge, signature, nowSeconds)) return false;
  const keyId = await installationKeyId(publicKey);
  if (!await consumeNonce(keyId, challenge)) {
    throw new Error("installation challenge was already consumed");
  }
  return true;
}

export async function installationKeyId(publicKey: JsonWebKey): Promise<string> {
  validatePublicKey(publicKey);
  const canonical = JSON.stringify({
    crv: publicKey.crv,
    kty: publicKey.kty,
    x: publicKey.x,
    y: publicKey.y,
  });
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(canonical));
  return base64url(new Uint8Array(digest));
}

function validatePublicKey(key: JsonWebKey): void {
  if (key.kty !== "EC" || key.crv !== "P-256" || typeof key.x !== "string" || typeof key.y !== "string") {
    throw new Error("invalid installation public key");
  }
  if ("d" in key && key.d !== undefined) {
    throw new Error("installation public key contains private material");
  }
  decodeBase64url(key.x);
  decodeBase64url(key.y);
}

function validateChallenge(challenge: InstallationChallenge, nowSeconds: number): void {
  if (challenge.v !== INSTALLATION_AUTH_VERSION) throw new Error("unsupported installation auth version");
  if (challenge.action !== "recover" && challenge.action !== "delete") throw new Error("invalid installation action");
  if (!Number.isSafeInteger(challenge.exp)) throw new Error("invalid installation challenge expiry");
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(challenge.nonce)) throw new Error("invalid installation challenge nonce");
  if (challenge.action === "delete") {
    if (!challenge.caseId || !/^[A-Za-z0-9_-]{1,128}$/.test(challenge.caseId)) {
      throw new Error("delete challenge requires a bounded case id");
    }
  } else if (challenge.caseId !== undefined) {
    throw new Error("recover challenge must not name a case");
  }
  if (nowSeconds !== Number.MIN_SAFE_INTEGER) {
    if (challenge.exp < nowSeconds || challenge.exp > nowSeconds + MAX_CHALLENGE_LIFETIME_SECONDS) {
      throw new Error("expired or overlong installation challenge");
    }
  }
}

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
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (base64url(bytes) !== value) throw new Error("non-canonical base64url");
  return bytes;
}

export function encodeInstallationSignature(bytes: ArrayBuffer): string {
  return base64url(new Uint8Array(bytes));
}
