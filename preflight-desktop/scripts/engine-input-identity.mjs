import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readdirSync } from "node:fs";
import { join, relative, sep } from "node:path";

export const ENGINE_INPUT_IDENTITY_FORMAT = "preflight-desktop-engine-input-v1";

export function engineInputIdentity(root, inputs, values = {}) {
  const hash = createHash("sha256");
  hashField(hash, "format", ENGINE_INPUT_IDENTITY_FORMAT);
  for (const input of [...inputs].sort()) {
    hashPath(hash, root, join(root, input));
  }
  for (const [name, value] of Object.entries(values).sort(([left], [right]) => left.localeCompare(right))) {
    hashField(hash, `value:${name}`, value);
  }
  return {
    format: ENGINE_INPUT_IDENTITY_FORMAT,
    sha256: hash.digest("hex"),
  };
}

function hashPath(hash, root, path) {
  const details = lstatSync(path, { throwIfNoEntry: false });
  if (!details) throw new Error(`Desktop engine input is missing: ${relative(root, path)}`);
  const name = relative(root, path).split(sep).join("/");
  if (details.isDirectory()) {
    hashField(hash, `directory:${name}`, "");
    for (const entry of readdirSync(path).sort()) hashPath(hash, root, join(path, entry));
    return;
  }
  if (details.isSymbolicLink()) {
    throw new Error(`Desktop engine input cannot be a symbolic link: ${name}`);
  }
  if (!details.isFile()) throw new Error(`Unsupported desktop engine input: ${name}`);
  hashField(hash, `file:${name}`, readFileSync(path));
}

function hashField(hash, name, value) {
  const data = Buffer.isBuffer(value) ? value : Buffer.from(String(value), "utf8");
  const length = Buffer.alloc(8);
  length.writeBigUInt64BE(BigInt(data.length));
  hash.update(name);
  hash.update("\0");
  hash.update(length);
  hash.update(data);
}
