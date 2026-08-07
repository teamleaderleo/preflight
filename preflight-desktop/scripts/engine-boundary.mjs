import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptsDirectory = dirname(fileURLToPath(import.meta.url));
const desktopDirectory = resolve(scriptsDirectory, "..");
const repositoryRoot = resolve(desktopDirectory, "..");

const rootEntries = ["bundle.json", "legal", "preflight.jar", "runtime", "scenarios"];
const legalFiles = new Map([
  ["LICENSE", join(repositoryRoot, "LICENSE")],
  ["KNOWN_LIMITATIONS.md", join(repositoryRoot, "docs", "known-limitations.md")],
  ["PRIVACY.md", join(repositoryRoot, "docs", "privacy.md")],
  ["THIRD_PARTY_NOTICES.md", join(repositoryRoot, "THIRD_PARTY_NOTICES.md")],
]);
const scenarioFiles = new Map([
  ["campaign-roam.json", join(repositoryRoot, "scripts", "scenarios", "campaign-roam.json")],
]);
const forbiddenSegments = new Set([
  "activation",
  "mods",
  "saves",
  "screenshots",
  "starsector.app",
  "starsector-core",
  "starsector.exe",
]);

export function runtimeInventory(runtimeDirectory) {
  const hash = createHash("sha256");
  let files = 0;
  let bytes = 0;
  for (const path of regularFiles(runtimeDirectory)) {
    const name = relative(runtimeDirectory, path).split(sep).join("/");
    rejectForbiddenPath(name);
    const data = readFileSync(path);
    const encodedName = Buffer.from(name, "utf8");
    const length = Buffer.alloc(8);
    length.writeBigUInt64BE(BigInt(data.length));
    hash.update(encodedName);
    hash.update(Buffer.from([0]));
    hash.update(length);
    hash.update(data);
    files += 1;
    bytes += data.length;
  }
  if (files === 0) throw new Error("Bundled runtime has no files");
  return { files, bytes, sha256: hash.digest("hex") };
}

export function verifyEngineBoundary(engineDirectory = join(desktopDirectory, "src-tauri", "target", "engine")) {
  assertDirectory(engineDirectory, "desktop engine");
  assertExactEntries(engineDirectory, rootEntries);
  assertDirectory(join(engineDirectory, "legal"), "legal directory");
  assertExactEntries(join(engineDirectory, "legal"), [...legalFiles.keys()].sort());
  assertDirectory(join(engineDirectory, "scenarios"), "scenario directory");
  assertExactEntries(join(engineDirectory, "scenarios"), [...scenarioFiles.keys()].sort());
  assertDirectory(join(engineDirectory, "runtime"), "runtime directory");

  assertSameFile(join(engineDirectory, "preflight.jar"), join(repositoryRoot, "preflight-cli", "target", "preflight.jar"));
  for (const [name, source] of legalFiles) {
    assertSameFile(join(engineDirectory, "legal", name), source);
  }
  for (const [name, source] of scenarioFiles) {
    assertSameFile(join(engineDirectory, "scenarios", name), source);
  }

  const manifestPath = join(engineDirectory, "bundle.json");
  assertRegularFile(manifestPath, "bundle manifest");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const expectedKeys = [
    "compression",
    "jarBytes",
    "legalFiles",
    "modules",
    "runtime",
    "smokeScenarioBytes",
    "sourceVersion",
  ];
  if (JSON.stringify(Object.keys(manifest).sort()) !== JSON.stringify(expectedKeys)) {
    throw new Error(`Unexpected desktop engine manifest fields: ${Object.keys(manifest).sort().join(", ")}`);
  }
  if (!Array.isArray(manifest.modules) || manifest.modules.length === 0 ||
      manifest.modules.some((value) => typeof value !== "string" || !/^([a-z][a-z0-9]*)(\.[a-z0-9]+)+$/.test(value))) {
    throw new Error("Desktop engine manifest has invalid Java modules");
  }
  if (!new Set(["2", "zip-6"]).has(manifest.compression)) {
    throw new Error(`Desktop engine manifest has invalid compression: ${manifest.compression}`);
  }
  if (manifest.jarBytes !== statSync(join(engineDirectory, "preflight.jar")).size) {
    throw new Error("Desktop engine manifest JAR size differs from the bundled JAR");
  }
  const scenario = join(engineDirectory, "scenarios", "campaign-roam.json");
  if (manifest.smokeScenarioBytes !== statSync(scenario).size) {
    throw new Error("Desktop engine manifest scenario size differs from the bundled scenario");
  }
  const actualLegal = Object.fromEntries(
    [...legalFiles.keys()].map((name) => [name, statSync(join(engineDirectory, "legal", name)).size]),
  );
  if (stableJson(manifest.legalFiles) !== stableJson(actualLegal)) {
    throw new Error("Desktop engine manifest legal-file inventory differs from the bundle");
  }
  if (typeof manifest.sourceVersion !== "string" || manifest.sourceVersion.length === 0) {
    throw new Error("Desktop engine manifest has no source version");
  }
  const actualRuntime = runtimeInventory(join(engineDirectory, "runtime"));
  if (stableJson(manifest.runtime) !== stableJson(actualRuntime)) {
    throw new Error("Desktop engine runtime differs from its content inventory");
  }

  const runtimeRoot = readdirSync(join(engineDirectory, "runtime"), { withFileTypes: true })
    .map((entry) => entry.name)
    .sort();
  for (const required of ["bin", "legal", "lib", "release"]) {
    if (!runtimeRoot.includes(required)) throw new Error(`Bundled runtime is missing ${required}`);
  }
  const javaName = process.platform === "win32" ? "java.exe" : "java";
  assertRegularFile(join(engineDirectory, "runtime", "bin", javaName), "bundled Java launcher");
  assertRegularFile(join(engineDirectory, "runtime", "release"), "bundled Java release metadata");

  const tauriConfig = JSON.parse(readFileSync(join(desktopDirectory, "src-tauri", "tauri.conf.json"), "utf8"));
  const expectedResources = {
    "licenses/Orbitron-OFL.txt": "licenses/Orbitron-OFL.txt",
    "target/engine/": "engine/",
  };
  if (stableJson(tauriConfig.bundle?.resources) !== stableJson(expectedResources)) {
    throw new Error("Tauri resource map differs from the reviewed desktop boundary");
  }

  return { runtimeFiles: actualRuntime.files, runtimeBytes: actualRuntime.bytes };
}

function regularFiles(directory) {
  assertDirectory(directory, "directory");
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const path = join(directory, entry.name);
    const details = lstatSync(path);
    if (details.isSymbolicLink()) throw new Error(`Symbolic link isn't allowed in the desktop engine: ${path}`);
    if (details.isDirectory()) result.push(...regularFiles(path));
    else if (details.isFile()) result.push(path);
    else throw new Error(`Non-regular desktop engine entry: ${path}`);
  }
  return result;
}

function assertExactEntries(directory, expected) {
  const actual = readdirSync(directory).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(`Entries differ under ${directory}: expected ${wanted.join(", ")}; got ${actual.join(", ")}`);
  }
  for (const name of actual) rejectForbiddenPath(name);
}

function rejectForbiddenPath(name) {
  for (const segment of name.toLowerCase().split("/")) {
    if (forbiddenSegments.has(segment)) throw new Error(`Forbidden desktop engine path segment: ${segment}`);
  }
}

function assertDirectory(path, label) {
  const details = lstatSync(path, { throwIfNoEntry: false });
  if (!details?.isDirectory() || details.isSymbolicLink()) throw new Error(`${label} isn't a real directory: ${path}`);
}

function assertRegularFile(path, label) {
  const details = lstatSync(path, { throwIfNoEntry: false });
  if (!details?.isFile() || details.isSymbolicLink()) throw new Error(`${label} isn't a regular file: ${path}`);
}

function assertSameFile(actual, expected) {
  assertRegularFile(actual, "bundled file");
  assertRegularFile(expected, "source file");
  if (!readFileSync(actual).equals(readFileSync(expected))) {
    throw new Error(`Bundled file differs from its reviewed source: ${actual}`);
  }
}

function stableJson(value) {
  if (Array.isArray(value)) return JSON.stringify(value.map((item) => JSON.parse(stableJson(item))));
  if (value && typeof value === "object") {
    return JSON.stringify(Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, JSON.parse(stableJson(value[key]))]),
    ));
  }
  return JSON.stringify(value);
}
