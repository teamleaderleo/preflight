import { describe, expect, it } from "vitest";
import {
  INSTALLATION_AUTH_VERSION,
  canonicalInstallationChallenge,
  encodeInstallationSignature,
  installationKeyId,
  verifyInstallationRequest,
  type InstallationChallenge,
} from "../src/installation-auth";

const encoder = new TextEncoder();

async function keyPair(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  ) as Promise<CryptoKeyPair>;
}

async function jwk(key: CryptoKey): Promise<JsonWebKey> {
  return crypto.subtle.exportKey("jwk", key) as Promise<JsonWebKey>;
}

async function signed(
  privateKey: CryptoKey,
  challenge: InstallationChallenge,
): Promise<string> {
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    encoder.encode(canonicalInstallationChallenge(challenge)),
  );
  return encodeInstallationSignature(signature);
}

describe("installation-key report authority", () => {
  it("verifies a bounded delete challenge for the bound installation key", async () => {
    const pair = await keyPair();
    const publicKey = await jwk(pair.publicKey);
    const challenge: InstallationChallenge = {
      v: INSTALLATION_AUTH_VERSION,
      action: "delete",
      caseId: "PF_CASE_A",
      nonce: "nonce_0123456789abcdef",
      exp: 1_800_000_120,
    };

    expect(await verifyInstallationRequest(
      publicKey,
      challenge,
      await signed(pair.privateKey, challenge),
      1_800_000_000,
    )).toBe(true);
  });

  it("cannot substitute another case into a captured delete signature", async () => {
    const pair = await keyPair();
    const publicKey = await jwk(pair.publicKey);
    const original: InstallationChallenge = {
      v: INSTALLATION_AUTH_VERSION,
      action: "delete",
      caseId: "PF_CASE_A",
      nonce: "nonce_0123456789abcdef",
      exp: 1_800_000_120,
    };
    const signature = await signed(pair.privateKey, original);

    expect(await verifyInstallationRequest(
      publicKey,
      { ...original, caseId: "PF_CASE_B" },
      signature,
      1_800_000_000,
    )).toBe(false);
  });

  it("cannot replay a delete signature as a recover request", async () => {
    const pair = await keyPair();
    const publicKey = await jwk(pair.publicKey);
    const deletion: InstallationChallenge = {
      v: INSTALLATION_AUTH_VERSION,
      action: "delete",
      caseId: "PF_CASE_A",
      nonce: "nonce_0123456789abcdef",
      exp: 1_800_000_120,
    };
    const signature = await signed(pair.privateKey, deletion);
    const recovery: InstallationChallenge = {
      v: INSTALLATION_AUTH_VERSION,
      action: "recover",
      nonce: deletion.nonce,
      exp: deletion.exp,
    };

    expect(await verifyInstallationRequest(
      publicKey,
      recovery,
      signature,
      1_800_000_000,
    )).toBe(false);
  });

  it("rejects expired and overlong challenges before signature verification", async () => {
    const pair = await keyPair();
    const publicKey = await jwk(pair.publicKey);
    const expired: InstallationChallenge = {
      v: INSTALLATION_AUTH_VERSION,
      action: "recover",
      nonce: "nonce_0123456789abcdef",
      exp: 1_799_999_999,
    };

    await expect(verifyInstallationRequest(
      publicKey,
      expired,
      await signed(pair.privateKey, expired),
      1_800_000_000,
    )).rejects.toThrow("expired or overlong");
  });

  it("derives a stable public installation identity without private material", async () => {
    const pair = await keyPair();
    const publicKey = await jwk(pair.publicKey);
    expect(await installationKeyId(publicKey)).toBe(await installationKeyId({ ...publicKey }));

    const privateKey = await jwk(pair.privateKey);
    await expect(installationKeyId(privateKey)).rejects.toThrow("private material");
  });
});
