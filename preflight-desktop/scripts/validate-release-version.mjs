import { readFileSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function validateReleaseVersion(tag, sources) {
  if (!/^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(tag)) {
    throw new Error(`Release tag must be v-prefixed SemVer: ${tag}`);
  }
  const version = tag.slice(1);
  const mismatches = Object.entries(sources)
    .filter(([, sourceVersion]) => sourceVersion !== version)
    .map(([source, sourceVersion]) => `${source}=${sourceVersion ?? "missing"}`);
  if (mismatches.length > 0) {
    throw new Error(`Release ${tag} doesn't match ${mismatches.join(", ")}`);
  }
  return version;
}

export function validateReleaseNotes(tag, text, source) {
  if (!text.trim()) {
    throw new Error(`Release ${tag} is missing reviewed notes: ${source}`);
  }

  // Draft notes are useful while the integration branch is moving, but neither a signed candidate
  // nor a tag should be able to turn that placeholder into published release copy accidentally.
  // Keep this deliberately tied to an explicit marker near the top of the document: ordinary prose
  // may discuss historical drafts without making the release itself a draft.
  const preamble = text.slice(0, 1024);
  if (/\bdraft release notes\b/i.test(preamble)) {
    throw new Error(`Release ${tag} still has draft notes: ${source}`);
  }
}

function firstXmlVersion(xml, source) {
  const version = xml.match(/<version>\s*([^<\s]+)\s*<\/version>/)?.[1];
  if (!version) throw new Error(`${source} has no project or parent version`);
  return version;
}

function cargoPackageVersion(lock, packageName) {
  const escaped = packageName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return lock.match(new RegExp(`\\[\\[package\\]\\]\\s+name = "${escaped}"\\s+version = "([^"]+)"`))?.[1];
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const tag = process.argv[2];
  if (!tag) throw new Error("Usage: node validate-release-version.mjs <tag>");
  const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const repository = resolve(desktopDirectory, "..");
  const packageJson = JSON.parse(readFileSync(resolve(desktopDirectory, "package.json"), "utf8"));
  const packageLock = JSON.parse(readFileSync(resolve(desktopDirectory, "package-lock.json"), "utf8"));
  const tauriConfig = JSON.parse(readFileSync(resolve(desktopDirectory, "src-tauri", "tauri.conf.json"), "utf8"));
  const cargoToml = readFileSync(resolve(desktopDirectory, "src-tauri", "Cargo.toml"), "utf8");
  const cargoLock = readFileSync(resolve(desktopDirectory, "src-tauri", "Cargo.lock"), "utf8");
  const rustPackageVersion = cargoToml.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  const rootPomPath = resolve(repository, "pom.xml");
  const rootPom = readFileSync(rootPomPath, "utf8");
  const sources = {
    "package.json": packageJson.version,
    "package-lock.json": packageLock.version,
    "package-lock.json packages root": packageLock.packages?.[""]?.version,
    "tauri.conf.json": tauriConfig.version,
    "Cargo.toml": rustPackageVersion,
    "Cargo.lock": cargoPackageVersion(cargoLock, "starsector-preflight-desktop"),
    "pom.xml": firstXmlVersion(rootPom, "pom.xml"),
  };
  for (const match of rootPom.matchAll(/<module>\s*([^<\s]+)\s*<\/module>/g)) {
    const modulePom = `${match[1]}/pom.xml`;
    sources[modulePom] = firstXmlVersion(
      readFileSync(resolve(repository, modulePom), "utf8"),
      modulePom,
    );
  }
  const version = validateReleaseVersion(tag, sources);
  const notesRelative = `docs/releases/${version}.md`;
  const notes = resolve(repository, notesRelative);
  if (!statSync(notes, { throwIfNoEntry: false })?.isFile()) {
    throw new Error(`Release ${tag} is missing reviewed notes: ${notesRelative}`);
  }
  validateReleaseNotes(tag, readFileSync(notes, "utf8"), notesRelative);
  console.log(`Release versions and notes agree on ${version}`);
}
