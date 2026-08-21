import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { buildCapabilityReceipt } from "./capability-receipt.mjs";

const scriptsDirectory = dirname(fileURLToPath(import.meta.url));
const desktopDirectory = resolve(scriptsDirectory, "..");
const repositoryRoot = resolve(desktopDirectory, "..");

const rootEntries = [
  "bundle.json",
  "capability-receipt.json",
  "legal",
  "preflight.jar",
  "runtime",
  "scenarios",
];
const legalFiles = new Map([
  ["LICENSE", join(repositoryRoot, "LICENSE")],
  ["KNOWN_LIMITATIONS.md", join(repositoryRoot, "docs", "known-limitations.md")],
  ["PRIVACY.md", join(repositoryRoot, "docs", "privacy.md")],
  ["THIRD_PARTY_NOTICES.md", join(repositoryRoot, "THIRD_PARTY_NOTICES.md")],
]);
const scenarioFiles = new Map([
  ["campaign-roam.json", join(repositoryRoot, "scripts", "scenarios", "campaign-roam.json")],
  ["campaign-roam-measurement-only.json", join(repositoryRoot, "scripts", "scenarios", "campaign-roam-measurement-only.json")],
  ["startup.json", join(repositoryRoot, "scripts", "scenarios", "startup.json")],
  ["startup-measurement-only.json", join(repositoryRoot, "scripts", "scenarios", "startup-measurement-only.json")],
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
  const entries = [];
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
    entries.push({ path: name, bytes: data.length, sha256: createHash("sha256").update(data).digest("hex") });
  }
  if (files === 0) throw new Error("Bundled runtime has no files");
  return { files, bytes, sha256: hash.digest("hex"), entries };
}

export function verifyEngineBoundary(
  engineDirectory = join(desktopDirectory, "src-tauri", "target", "engine"),
  options = {},
) {
  const report = verifyEngineIntegrity(engineDirectory, options);

  assertSameFile(join(engineDirectory, "preflight.jar"), join(repositoryRoot, "preflight-cli", "target", "preflight.jar"));
  for (const [name, source] of legalFiles) {
    assertSameFile(join(engineDirectory, "legal", name), source);
  }
  for (const [name, source] of scenarioFiles) {
    assertSameFile(join(engineDirectory, "scenarios", name), source);
  }

  const tauriConfig = JSON.parse(readFileSync(join(desktopDirectory, "src-tauri", "tauri.conf.json"), "utf8"));
  const expectedResources = {
    "licenses/B612-OFL.txt": "licenses/B612-OFL.txt",
    "licenses/IBM-Plex-Sans-OFL.txt": "licenses/IBM-Plex-Sans-OFL.txt",
    "licenses/Orbitron-OFL.txt": "licenses/Orbitron-OFL.txt",
    "target/engine/": "engine/",
  };
  if (stableJson(tauriConfig.bundle?.resources) !== stableJson(expectedResources)) {
    throw new Error("Tauri resource map differs from the reviewed desktop boundary");
  }

  return report;
}

/**
 * Verify a packaged engine using only the package's own bounded inventories and receipts.
 *
 * Distribution uses verifyEngineBoundary so package bytes are compared with the checked-out
 * sources. The upgrade rehearsal intentionally rebuilds those sources as an older version before
 * installing the exact candidate, so its post-install check must use the candidate's signed,
 * previously reviewed identity instead. This still checks the complete engine shape, manifest
 * digests, capability receipt, runtime inventory, and runnable Java entry point.
 */
export function verifyEngineIntegrity(
  engineDirectory = join(desktopDirectory, "src-tauri", "target", "engine"),
  options = {},
) {
  assertDirectory(engineDirectory, "desktop engine");
  assertExactEntries(engineDirectory, rootEntries);
  assertDirectory(join(engineDirectory, "legal"), "legal directory");
  assertExactEntries(join(engineDirectory, "legal"), [...legalFiles.keys()].sort());
  assertDirectory(join(engineDirectory, "scenarios"), "scenario directory");
  assertExactEntries(join(engineDirectory, "scenarios"), [...scenarioFiles.keys()].sort());
  assertDirectory(join(engineDirectory, "runtime"), "runtime directory");

  const manifestPath = join(engineDirectory, "bundle.json");
  assertRegularFile(manifestPath, "bundle manifest");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const expectedKeys = [
    "capabilityReceiptBytes",
    "capabilityReceiptSha256",
    "compression",
    "jarBytes",
    "jarSha256",
    "legalFiles",
    "measurementScenarioBytes",
    "modules",
    "runtime",
    "smokeScenarioBytes",
    "sourceVersion",
    "startupMeasurementScenarioBytes",
    "startupScenarioBytes",
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
  const actualJarSha256 = createHash("sha256")
    .update(readFileSync(join(engineDirectory, "preflight.jar")))
    .digest("hex");
  if (!/^[a-f0-9]{64}$/.test(manifest.jarSha256) || manifest.jarSha256 !== actualJarSha256) {
    throw new Error("Desktop engine manifest JAR digest differs from the bundled JAR");
  }
  const scenario = join(engineDirectory, "scenarios", "campaign-roam.json");
  if (manifest.smokeScenarioBytes !== statSync(scenario).size) {
    throw new Error("Desktop engine manifest scenario size differs from the bundled scenario");
  }
  const measurementScenario = join(engineDirectory, "scenarios", "campaign-roam-measurement-only.json");
  if (manifest.measurementScenarioBytes !== statSync(measurementScenario).size) {
    throw new Error("Desktop engine manifest measurement scenario size differs from the bundled scenario");
  }
  const startupScenario = join(engineDirectory, "scenarios", "startup.json");
  if (manifest.startupScenarioBytes !== statSync(startupScenario).size) {
    throw new Error("Desktop engine manifest startup scenario size differs from the bundled scenario");
  }
  const startupMeasurementScenario = join(engineDirectory, "scenarios", "startup-measurement-only.json");
  if (manifest.startupMeasurementScenarioBytes !== statSync(startupMeasurementScenario).size) {
    throw new Error("Desktop engine manifest startup measurement scenario size differs from the bundled scenario");
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
  const capabilityReceiptPath = join(engineDirectory, "capability-receipt.json");
  assertRegularFile(capabilityReceiptPath, "release capability receipt");
  const capabilityReceiptBytes = readFileSync(capabilityReceiptPath);
  if (manifest.capabilityReceiptBytes !== capabilityReceiptBytes.length ||
      !/^[a-f0-9]{64}$/.test(manifest.capabilityReceiptSha256) ||
      manifest.capabilityReceiptSha256 !== createHash("sha256").update(capabilityReceiptBytes).digest("hex")) {
    throw new Error("Desktop engine capability receipt differs from the bundle manifest");
  }
  const capabilityReceipt = JSON.parse(capabilityReceiptBytes.toString("utf8"));
  const expectedCapabilityReceipt = buildCapabilityReceipt({
    engineJarPath: join(engineDirectory, "preflight.jar"),
    productVersion: manifest.sourceVersion,
    sourceRevision: capabilityReceipt.sourceRevision,
    sourceDirty: capabilityReceipt.sourceDirty,
    environment: {
      PREFLIGHT_UPDATER_ENDPOINT: capabilityReceipt.network?.updateEndpoint,
      ...(capabilityReceipt.network?.updateConfigured
        ? { PREFLIGHT_UPDATER_PUBLIC_KEY: "configured" }
        : {}),
      ...(capabilityReceipt.network?.reportIntakeOrigin === "disabled"
        ? {}
        : { PREFLIGHT_REPORT_INTAKE_ORIGIN: capabilityReceipt.network?.reportIntakeOrigin }),
    },
  });
  if (stableJson(capabilityReceipt) !== stableJson(expectedCapabilityReceipt)) {
    throw new Error("Desktop engine capability receipt differs from the reviewed boundary");
  }
  const actualRuntime = runtimeInventory(join(engineDirectory, "runtime"));
  const runtimeChanges = verifyRuntimeInventory(
    manifest.runtime,
    actualRuntime,
    options.allowedRuntimeChanges ?? [],
  );

  const runtimeRoot = readdirSync(join(engineDirectory, "runtime"), { withFileTypes: true })
    .map((entry) => entry.name)
    .sort();
  for (const required of ["bin", "legal", "lib", "release"]) {
    if (!runtimeRoot.includes(required)) throw new Error(`Bundled runtime is missing ${required}`);
  }
  const javaName = process.platform === "win32" ? "java.exe" : "java";
  assertRegularFile(join(engineDirectory, "runtime", "bin", javaName), "bundled Java launcher");
  assertRegularFile(join(engineDirectory, "runtime", "release"), "bundled Java release metadata");

  return { runtimeFiles: actualRuntime.files, runtimeBytes: actualRuntime.bytes, runtimeChanges };
}

export function verifyRuntimeInventory(expected, actual, allowedChanges = []) {
  validateRuntimeInventory(expected, "manifest");
  validateRuntimeInventory(actual, "packaged runtime");
  const expectedEntries = new Map(
    expected.entries.map((entry) => [entry.path, entry]),
  );
  const actualEntries = new Map(actual.entries.map((entry) => [entry.path, entry]));
  const missing = [...expectedEntries.keys()].filter((path) => !actualEntries.has(path));
  const added = [...actualEntries.keys()].filter((path) => !expectedEntries.has(path));
  const changed = [...expectedEntries.keys()].filter((path) => {
    const found = actualEntries.get(path);
    return found && stableJson(expectedEntries.get(path)) !== stableJson(found);
  });
  const allowed = [...allowedChanges].sort();
  if (!missing.length && !added.length && !changed.length && allowed.length === 0 &&
      stableJson(expected) === stableJson(actual)) {
    return [];
  }
  if (allowed.length > 0 && !missing.length && !added.length &&
      stableJson(changed.sort()) === stableJson(allowed)) {
    return changed;
  }
  const details = [];
  if (missing.length) details.push(`missing ${missing.join(", ")}`);
  if (added.length) details.push(`added ${added.join(", ")}`);
  if (changed.length) details.push(`changed ${changed.join(", ")}`);
  if (allowed.length) details.push(`allowed changes ${allowed.join(", ")}`);
  if (!details.length) {
    details.push(
      `summary expected ${stableJson(runtimeSummary(expected))}, got ${stableJson(runtimeSummary(actual))}`,
    );
  }
  throw new Error(`Desktop engine runtime differs from its content inventory: ${details.join("; ")}`);
}

function validateRuntimeInventory(inventory, label) {
  const expectedKeys = ["bytes", "entries", "files", "sha256"];
  if (!inventory || typeof inventory !== "object" ||
      stableJson(Object.keys(inventory).sort()) !== stableJson(expectedKeys)) {
    throw new Error(`Invalid ${label} inventory fields`);
  }
  if (!Number.isSafeInteger(inventory.files) || inventory.files <= 0 ||
      !Number.isSafeInteger(inventory.bytes) || inventory.bytes <= 0 ||
      !/^[a-f0-9]{64}$/.test(inventory.sha256) || !Array.isArray(inventory.entries) ||
      inventory.entries.length !== inventory.files) {
    throw new Error(`Invalid ${label} inventory summary`);
  }
  const paths = [];
  let bytes = 0;
  for (const entry of inventory.entries) {
    if (!entry || typeof entry !== "object" ||
        stableJson(Object.keys(entry).sort()) !== stableJson(["bytes", "path", "sha256"]) ||
        typeof entry.path !== "string" || !entry.path || entry.path.includes("\\") ||
        entry.path.startsWith("/") || entry.path.split("/").some((part) => !part || part === "." || part === "..") ||
        !Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || !/^[a-f0-9]{64}$/.test(entry.sha256)) {
      throw new Error(`Invalid ${label} inventory entry`);
    }
    rejectForbiddenPath(entry.path);
    paths.push(entry.path);
    bytes += entry.bytes;
  }
  if (new Set(paths).size !== paths.length ||
      stableJson(paths) !== stableJson([...paths].sort((a, b) => a.localeCompare(b))) || bytes !== inventory.bytes) {
    throw new Error(`Invalid ${label} inventory contents`);
  }
}

function runtimeSummary(inventory) {
  return { files: inventory?.files, bytes: inventory?.bytes, sha256: inventory?.sha256 };
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
