#!/usr/bin/env node
/*
 * Re-review the capability source lock.
 *
 * The lock is a review gate, not a checksum: touching any guarded file fails the build with
 * "Capability boundary changed without review" until a human looks at the diff and accepts it.
 * That gate is worth keeping. What was not worth keeping is the only way to accept a change --
 * hand-computing a SHA-256 with the same normalization the verifier uses and editing JSON. Get the
 * normalization wrong and the digest you write is wrong in a way nothing catches until CI, and the
 * obvious `shasum -a 256 <file>` is only right by accident on a file with no CRLF in it.
 *
 * So this rewrites the digests using the verifier's own normalization, and prints what moved. The
 * gate survives because running it is deliberate and the changed digest lands in the diff, exactly
 * like a dependency lockfile: the tool does the arithmetic, the reviewer still approves the change.
 *
 * Usage:
 *   npm run capabilities:review           # rewrite digests, print what changed
 *   npm run capabilities:review -- --check  # report drift and exit non-zero, changing nothing
 */
import { readFileSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const desktopDirectory = dirname(dirname(fileURLToPath(import.meta.url)));
const repositoryRoot = dirname(desktopDirectory);
const lockPath = join(desktopDirectory, "capabilities", "release-receipt-source-lock.json");

/** The verifier's normalization, so a CRLF checkout cannot produce a digest CI disagrees with. */
function normalizedSourceBytes(path) {
  return Buffer.from(readFileSync(path, "utf8").replaceAll("\r\n", "\n"), "utf8");
}

function sha256(data) {
  return createHash("sha256").update(data).digest("hex");
}

const checkOnly = process.argv.includes("--check");
const lock = JSON.parse(readFileSync(lockPath, "utf8"));
if (lock.format !== "preflight-capability-source-lock-v1") {
  console.error(`Unknown capability source lock format: ${lock.format}`);
  process.exit(1);
}

const drift = [];
const sources = {};
// The verifier requires sorted paths, so writing them sorted keeps this script from being the
// thing that breaks the lock it exists to maintain.
for (const name of Object.keys(lock.sources).sort()) {
  const expected = lock.sources[name];
  let actual;
  try {
    actual = sha256(normalizedSourceBytes(join(repositoryRoot, name)));
  } catch (error) {
    console.error(`Guarded file is missing or unreadable: ${name}\n  ${error.message}`);
    process.exit(1);
  }
  if (actual !== expected) drift.push({ name, expected, actual });
  sources[name] = actual;
}

if (drift.length === 0) {
  console.log(`Capability lock matches all ${Object.keys(sources).length} guarded files.`);
  process.exit(0);
}

for (const { name, expected, actual } of drift) {
  console.log(`${checkOnly ? "drifted" : "re-reviewed"}: ${name}\n  ${expected.slice(0, 12)} -> ${actual.slice(0, 12)}`);
}

if (checkOnly) {
  console.error(`\n${drift.length} guarded file${drift.length === 1 ? "" : "s"} changed without review.`);
  console.error("Read the diff, then accept it with: npm run capabilities:review --prefix preflight-desktop");
  process.exit(1);
}

writeFileSync(lockPath, `${JSON.stringify({ ...lock, sources }, null, 2)}\n`);
console.log(`\nRewrote ${drift.length} digest${drift.length === 1 ? "" : "s"}. The change is in your diff -- review it before committing.`);
