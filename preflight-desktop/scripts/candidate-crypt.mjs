import {
  createCipheriv,
  createDecipheriv,
  randomBytes,
  scrypt,
} from "node:crypto";
import {
  createReadStream,
  createWriteStream,
} from "node:fs";
import {
  access,
  appendFile,
  open,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { basename, resolve } from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const magic = Buffer.from("PFCAND01", "ascii");
const saltBytes = 16;
const nonceBytes = 12;
const tagBytes = 16;
const headerBytes = magic.length + saltBytes + nonceBytes;

export async function encryptCandidateFile(inputPath, outputPath, password) {
  validateArguments(inputPath, outputPath, password);
  await requireAbsent(outputPath);
  const salt = randomBytes(saltBytes);
  const nonce = randomBytes(nonceBytes);
  const key = await deriveKey(password, salt);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  const temporary = temporaryPath(outputPath);

  try {
    await writeFile(temporary, Buffer.concat([magic, salt, nonce]), { flag: "wx", mode: 0o600 });
    await pipeline(
      createReadStream(inputPath),
      cipher,
      createWriteStream(temporary, { flags: "a", mode: 0o600 }),
    );
    await appendFile(temporary, cipher.getAuthTag());
    await rename(temporary, outputPath);
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
}

export async function decryptCandidateFile(inputPath, outputPath, password) {
  validateArguments(inputPath, outputPath, password);
  await requireAbsent(outputPath);
  const metadata = await stat(inputPath);
  if (metadata.size < headerBytes + tagBytes) {
    throw new Error(`Candidate ciphertext is truncated: ${basename(inputPath)}`);
  }

  const handle = await open(inputPath, "r");
  let header;
  let tag;
  try {
    header = Buffer.alloc(headerBytes);
    tag = Buffer.alloc(tagBytes);
    await handle.read(header, 0, header.length, 0);
    await handle.read(tag, 0, tag.length, metadata.size - tagBytes);
  } finally {
    await handle.close();
  }
  if (!header.subarray(0, magic.length).equals(magic)) {
    throw new Error(`Candidate ciphertext has an unknown format: ${basename(inputPath)}`);
  }

  const salt = header.subarray(magic.length, magic.length + saltBytes);
  const nonce = header.subarray(magic.length + saltBytes, headerBytes);
  const key = await deriveKey(password, salt);
  const decipher = createDecipheriv("aes-256-gcm", key, nonce);
  decipher.setAuthTag(tag);
  const temporary = temporaryPath(outputPath);

  try {
    await pipeline(
      createReadStream(inputPath, { start: headerBytes, end: metadata.size - tagBytes - 1 }),
      decipher,
      createWriteStream(temporary, { flags: "wx", mode: 0o600 }),
    );
    await rename(temporary, outputPath);
  } catch (error) {
    await rm(temporary, { force: true });
    throw new Error(`Candidate ciphertext failed authentication: ${basename(inputPath)}`, { cause: error });
  }
}

function deriveKey(password, salt) {
  return new Promise((resolveKey, reject) => {
    scrypt(password, salt, 32, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }, (error, key) => {
      if (error) reject(error);
      else resolveKey(key);
    });
  });
}

function validateArguments(inputPath, outputPath, password) {
  if (!password || password.length < 32) {
    throw new Error("PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD must contain at least 32 characters");
  }
  if (resolve(inputPath) === resolve(outputPath)) {
    throw new Error("Candidate input and output paths must differ");
  }
}

async function requireAbsent(path) {
  try {
    await access(path);
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
  throw new Error(`Refusing to replace existing candidate file: ${basename(path)}`);
}

function temporaryPath(outputPath) {
  return `${outputPath}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const [, , operation, inputPath, outputPath] = process.argv;
  if (!operation || !inputPath || !outputPath || !["encrypt", "decrypt"].includes(operation)) {
    throw new Error("Usage: node candidate-crypt.mjs <encrypt|decrypt> <input> <output>");
  }
  const password = process.env.PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD;
  if (operation === "encrypt") {
    await encryptCandidateFile(inputPath, outputPath, password);
  } else {
    await decryptCandidateFile(inputPath, outputPath, password);
  }
}
