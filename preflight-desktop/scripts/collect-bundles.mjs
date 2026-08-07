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
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const bundleDirectory = join(desktopDirectory, "src-tauri", "target", "release", "bundle");
const outputDirectory = join(desktopDirectory, "desktop-dist");
const packageSuffixes = [
  ".appimage.sig",
  ".app.tar.gz.sig",
  ".app.tar.gz",
  ".exe.sig",
  ".appimage",
  ".deb",
  ".dmg",
  ".exe",
];

export function expectedPackageExtensions(platform, updateRelease) {
  const ordinary = {
    darwin: [".dmg"],
    linux: [".appimage", ".deb"],
    win32: [".exe"],
  }[platform];
  if (!ordinary) throw new Error(`Unsupported release platform: ${platform}`);
  if (!updateRelease) return ordinary;
  return {
    darwin: [".app.tar.gz", ".app.tar.gz.sig", ".dmg"],
    linux: [".appimage", ".appimage.sig", ".deb"],
    win32: [".exe", ".exe.sig"],
  }[platform];
}

export function collectPackages(directory, expectedExtensions) {
  const found = collectCandidates(directory);
  const byExtension = new Map();
  for (const path of found) {
    const extension = packageExtension(path);
    const existing = byExtension.get(extension);
    if (existing) throw new Error(`Two native packages have extension ${extension}: ${existing}, ${path}`);
    byExtension.set(extension, path);
  }
  const actual = [...byExtension.keys()].sort();
  const expected = [...expectedExtensions].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`Native package set differs: expected ${expected.join(", ")}; got ${actual.join(", ") || "none"}`);
  }
  return [...byExtension.values()].sort((left, right) => left.localeCompare(right));
}

function collectCandidates(directory) {
  const found = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      found.push(...collectCandidates(path));
    } else if (entry.isFile() && packageSuffixes.some((suffix) => entry.name.toLowerCase().endsWith(suffix))) {
      found.push(path);
    }
  }
  return found;
}

export function publicPackageName(source, platform = process.platform, architectureName = process.arch) {
  const platformLabel = {
    darwin: "macOS",
    linux: "Linux",
    win32: "Windows",
  }[platform];
  const architecture = {
    arm64: "arm64",
    x64: "x86_64",
  }[architectureName];
  if (!platformLabel || !architecture) {
    throw new Error(`Unsupported release target: ${platform}/${architectureName}`);
  }

  const extension = packageExtension(source);
  const publicExtension = extension.replace(".appimage", ".AppImage");
  return `Preflight-${platformLabel}-${architecture}${publicExtension}`;
}

function packageExtension(source) {
  const sourceName = basename(source).toLowerCase();
  const extension = packageSuffixes.find((suffix) => sourceName.endsWith(suffix));
  if (!extension) throw new Error(`Unsupported package extension: ${source}`);
  return extension;
}

function main() {
  if (!statSync(bundleDirectory, { throwIfNoEntry: false })?.isDirectory()) {
    throw new Error(`Tauri bundle directory does not exist: ${bundleDirectory}`);
  }
  const updateValue = process.env.PREFLIGHT_UPDATE_RELEASE ?? "false";
  if (!new Set(["true", "false"]).has(updateValue)) {
    throw new Error("PREFLIGHT_UPDATE_RELEASE must be true or false");
  }
  const expectedExtensions = expectedPackageExtensions(process.platform, updateValue === "true");
  const packages = collectPackages(bundleDirectory, expectedExtensions);

  // This is a generated distribution directory fixed below preflight-desktop. Never accept an
  // operator-provided deletion target here.
  rmSync(outputDirectory, { recursive: true, force: true });
  mkdirSync(outputDirectory, { recursive: true });

  const usedNames = new Set();
  const checksums = [];
  for (const source of packages) {
    const name = publicPackageName(source);
    if (!usedNames.add(name)) throw new Error(`Two native packages have the same filename: ${name}`);
    const destination = join(outputDirectory, name);
    copyFileSync(source, destination);
    const digest = createHash("sha256").update(readFileSync(destination)).digest("hex");
    checksums.push(`${digest}  ${name}`);
  }
  checksums.sort();
  const checksumName = `SHA256SUMS-${process.platform}-${process.arch}.txt`;
  writeFileSync(join(outputDirectory, checksumName), `${checksums.join("\n")}\n`);
  const expectedOutput = [...usedNames, checksumName].sort();
  const actualOutput = readdirSync(outputDirectory).sort();
  if (JSON.stringify(actualOutput) !== JSON.stringify(expectedOutput)) {
    throw new Error(`Desktop distribution files differ: expected ${expectedOutput}; got ${actualOutput}`);
  }
  console.log(`Collected ${packages.length} native package(s) in ${outputDirectory}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
