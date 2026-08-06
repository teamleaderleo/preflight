import { createHash } from "node:crypto";
import {
  copyFileSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, extname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const bundleDirectory = join(desktopDirectory, "src-tauri", "target", "release", "bundle");
const outputDirectory = join(desktopDirectory, "desktop-dist");
const packageExtensions = new Set([".appimage", ".deb", ".dmg", ".exe"]);

if (!statSync(bundleDirectory, { throwIfNoEntry: false })?.isDirectory()) {
  throw new Error(`Tauri bundle directory does not exist: ${bundleDirectory}`);
}

const packages = collectPackages(bundleDirectory).sort((left, right) => left.localeCompare(right));
if (packages.length === 0) {
  throw new Error(`No native desktop packages were found under ${bundleDirectory}`);
}

// This is a generated distribution directory fixed below preflight-desktop. Never accept an
// operator-provided deletion target here.
rmSync(outputDirectory, { recursive: true, force: true });
mkdirSync(outputDirectory, { recursive: true });

const usedNames = new Set();
const checksums = [];
for (const source of packages) {
  const name = basename(source);
  if (!usedNames.add(name)) {
    throw new Error(`Two native packages have the same filename: ${name}`);
  }
  const destination = join(outputDirectory, name);
  copyFileSync(source, destination);
  const digest = createHash("sha256").update(readFileSync(destination)).digest("hex");
  checksums.push(`${digest}  ${name}`);
}
const checksumName = `SHA256SUMS-${process.platform}-${process.arch}.txt`;
writeFileSync(join(outputDirectory, checksumName), `${checksums.join("\n")}\n`);

console.log(`Collected ${packages.length} native package(s) in ${outputDirectory}`);

function collectPackages(directory) {
  const found = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      found.push(...collectPackages(path));
    } else if (entry.isFile() && packageExtensions.has(extname(entry.name).toLowerCase())) {
      found.push(path);
    }
  }
  return found;
}
