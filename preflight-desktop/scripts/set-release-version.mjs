import {
  mkdirSync,
  readFileSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const semver = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

export function setReleaseVersion(repository, version) {
  if (!semver.test(version ?? "")) {
    throw new Error(`Release version must be SemVer without a v prefix: ${version}`);
  }
  const notesPath = resolve(repository, "docs", "releases", `${version}.md`);
  if (!statSync(notesPath, { throwIfNoEntry: false })?.isFile()
      || !readFileSync(notesPath, "utf8").trim()) {
    throw new Error(`Write reviewed release notes first: docs/releases/${version}.md`);
  }

  const desktop = resolve(repository, "preflight-desktop");
  const rootPomPath = resolve(repository, "pom.xml");
  const rootPom = readFileSync(rootPomPath, "utf8");
  const updates = new Map();

  const packagePath = resolve(desktop, "package.json");
  const packageJson = JSON.parse(readFileSync(packagePath, "utf8"));
  packageJson.version = version;
  updates.set(packagePath, `${JSON.stringify(packageJson, null, 2)}\n`);

  const packageLockPath = resolve(desktop, "package-lock.json");
  const packageLock = JSON.parse(readFileSync(packageLockPath, "utf8"));
  packageLock.version = version;
  if (!packageLock.packages?.[""]) {
    throw new Error("package-lock.json has no root package entry");
  }
  packageLock.packages[""].version = version;
  updates.set(packageLockPath, `${JSON.stringify(packageLock, null, 2)}\n`);

  const tauriPath = resolve(desktop, "src-tauri", "tauri.conf.json");
  const tauri = JSON.parse(readFileSync(tauriPath, "utf8"));
  tauri.version = version;
  updates.set(tauriPath, `${JSON.stringify(tauri, null, 2)}\n`);

  const cargoTomlPath = resolve(desktop, "src-tauri", "Cargo.toml");
  updates.set(cargoTomlPath, replaceOne(
    readFileSync(cargoTomlPath, "utf8"),
    /^version\s*=\s*"[^"]+"/m,
    `version = "${version}"`,
    "Cargo.toml package version",
  ));

  const cargoLockPath = resolve(desktop, "src-tauri", "Cargo.lock");
  updates.set(cargoLockPath, replaceOne(
    readFileSync(cargoLockPath, "utf8"),
    /(\[\[package\]\]\s+name = "starsector-preflight-desktop"\s+version = ")[^"]+("\s+)/,
    `$1${version}$2`,
    "Cargo.lock desktop package version",
  ));

  updates.set(rootPomPath, replaceOne(
    rootPom,
    /<version>\s*[^<\s]+\s*<\/version>/,
    `<version>${version}</version>`,
    "root Maven project version",
  ));
  for (const match of rootPom.matchAll(/<module>\s*([^<\s]+)\s*<\/module>/g)) {
    const modulePomPath = resolve(repository, match[1], "pom.xml");
    updates.set(modulePomPath, replaceOne(
      readFileSync(modulePomPath, "utf8"),
      /<version>\s*[^<\s]+\s*<\/version>/,
      `<version>${version}</version>`,
      `${match[1]} parent version`,
    ));
  }

  for (const [path, contents] of updates) {
    const temporary = `${path}.preflight-version-${process.pid}`;
    mkdirSync(dirname(path), { recursive: true });
    try {
      writeFileSync(temporary, contents, { mode: statSync(path).mode });
      renameSync(temporary, path);
    } finally {
      if (statSync(temporary, { throwIfNoEntry: false })) unlinkSync(temporary);
    }
  }
  return [...updates.keys()];
}

function replaceOne(value, pattern, replacement, label) {
  const matches = value.match(new RegExp(pattern.source, pattern.flags.includes("g") ? pattern.flags : `${pattern.flags}g`));
  if (matches?.length !== 1) {
    throw new Error(`${label} must have exactly one writable match; found ${matches?.length ?? 0}`);
  }
  return value.replace(pattern, replacement);
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const version = process.argv[2];
  if (!version) throw new Error("Usage: node set-release-version.mjs <version>");
  const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
  const updated = setReleaseVersion(repository, version);
  console.log(`Set release ${version} in ${updated.length} version files`);
}
