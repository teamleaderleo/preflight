import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  decryptCandidateFile,
  encryptCandidateFile,
} from "./candidate-crypt.mjs";

const password = "correct horse battery staple candidate key 0123456789";

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "preflight-candidate-crypt-"));
  const plain = join(directory, "candidate.bin");
  const encrypted = join(directory, "candidate.bin.pfcandidate");
  const decrypted = join(directory, "candidate-restored.bin");
  writeFileSync(plain, Buffer.concat([Buffer.from("candidate\0payload\n"), Buffer.alloc(128 * 1024, 0xa5)]));
  return { plain, encrypted, decrypted };
}

test("candidate encryption round-trips without exposing plaintext", async () => {
  const { plain, encrypted, decrypted } = fixture();
  await encryptCandidateFile(plain, encrypted, password);
  assert.notDeepEqual(readFileSync(encrypted), readFileSync(plain));
  await decryptCandidateFile(encrypted, decrypted, password);
  assert.deepEqual(readFileSync(decrypted), readFileSync(plain));
});

test("candidate decryption rejects the wrong key without publishing output", async () => {
  const { plain, encrypted, decrypted } = fixture();
  await encryptCandidateFile(plain, encrypted, password);
  await assert.rejects(
    decryptCandidateFile(encrypted, decrypted, "wrong candidate password with at least 32 characters"),
    /failed authentication/,
  );
  assert.throws(() => readFileSync(decrypted), /ENOENT/);
});

test("candidate decryption rejects modified ciphertext", async () => {
  const { plain, encrypted, decrypted } = fixture();
  await encryptCandidateFile(plain, encrypted, password);
  const bytes = readFileSync(encrypted);
  bytes[Math.floor(bytes.length / 2)] ^= 0xff;
  writeFileSync(encrypted, bytes);
  await assert.rejects(decryptCandidateFile(encrypted, decrypted, password), /failed authentication/);
  assert.throws(() => readFileSync(decrypted), /ENOENT/);
});
