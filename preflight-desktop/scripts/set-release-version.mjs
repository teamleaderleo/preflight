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

  updates.set(rootPomPath, replaceInSection(
    rootPom,
    projectCoordinateSection(rootPom, "pom.xml"),
    version,
    "root Maven project version",
  ));
  for (const match of rootPom.matchAll(/<module>\s*([^<\s]+)\s*<\/module>/g)) {
    const modulePomPath = resolve(repository, match[1], "pom.xml");
    const modulePom = readFileSync(modulePomPath, "utf8");
    updates.set(modulePomPath, replaceInSection(
      modulePom,
      parentSection(modulePom, `${match[1]}/pom.xml`),
      version,
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

/**
 * The span of a POM that holds the project's own version, and nothing else that looks like one.
 *
 * A POM is full of `<version>` elements — dependencies, plugins, enforcer ranges — and every one of
 * them is a wrong answer. Searching the whole file found ten in this project's root POM and refused
 * to touch any of them, so the version bump every release depends on could not run at all.
 *
 * Maven's own rule is that the project's coordinates are direct children of `<project>`, written
 * before any of the sections that carry other people's versions. That is the span taken here. If a
 * POM is ever reordered so the version moves after one of those sections, this finds nothing and
 * says so, which is the behaviour worth keeping from the original: refuse rather than guess.
 */
export function projectCoordinateSection(pom, source) {
  const boundary = pom.search(
    /<(dependencies|dependencyManagement|build|profiles|modules|reporting|distributionManagement)>/,
  );
  const head = boundary === -1 ? pom : pom.slice(0, boundary);
  if (!/<artifactId>/.test(head)) {
    throw new Error(`${source} declares no project artifactId before its first versioned section`);
  }
  return { text: head, start: 0 };
}

/**
 * The `<parent>` block of a module POM, which is where its inherited version lives.
 *
 * A module also names versions for its own dependencies. Those belong to whoever published them and
 * must not move with a Preflight release.
 */
export function parentSection(pom, source) {
  const match = pom.match(/<parent>[\s\S]*?<\/parent>/);
  if (!match) throw new Error(`${source} has no parent block to take its version from`);
  return { text: match[0], start: match.index };
}

function replaceInSection(pom, section, version, label) {
  const replaced = replaceOne(
    section.text,
    /<version>\s*[^<\s]+\s*<\/version>/,
    `<version>${version}</version>`,
    label,
  );
  return pom.slice(0, section.start) + replaced + pom.slice(section.start + section.text.length);
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
