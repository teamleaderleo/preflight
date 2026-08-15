import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptsDirectory = dirname(fileURLToPath(import.meta.url));
const desktopDirectory = resolve(scriptsDirectory, "..");
const repositoryRoot = resolve(desktopDirectory, "..");
const policyPath = join(desktopDirectory, "capabilities", "release-receipt-policy.json");
const sourceLockPath = join(desktopDirectory, "capabilities", "release-receipt-source-lock.json");
const nativeSourcePath = join(desktopDirectory, "src-tauri", "src", "lib.rs");
const updateSourcePath = join(desktopDirectory, "src-tauri", "src", "updates.rs");
const tauriCapabilityPath = join(desktopDirectory, "src-tauri", "capabilities", "default.json");

export function buildCapabilityReceipt({
  engineJarPath,
  productVersion,
  sourceRevision = currentSourceRevision(),
  sourceDirty = currentSourceDirty(),
  environment = process.env,
  sourceLock = readJson(sourceLockPath),
} = {}) {
  if (!engineJarPath) throw new Error("A capability receipt requires the packaged engine JAR");
  if (typeof productVersion !== "string" || !productVersion) {
    throw new Error("A capability receipt requires a product version");
  }
  if (!/^[a-f0-9]{40}$/.test(sourceRevision)) {
    throw new Error("Capability receipt source revision must be a 40-character Git SHA");
  }
  const sourceReview = verifySourceLock(sourceLock);
  const policy = readJson(policyPath);
  if (policy.format !== "preflight-capability-policy-v1") {
    throw new Error("Unknown capability receipt policy format");
  }
  const nativeSource = readFileSync(nativeSourcePath, "utf8");
  const updateSource = readFileSync(updateSourcePath, "utf8");
  const tauriCapability = readJson(tauriCapabilityPath);
  const updateEndpoint = effectiveUpdateEndpoint(updateSource, environment);
  const reportIntakeOrigin = effectiveReportOrigin(environment);
  const jar = readFileSync(engineJarPath);
  return {
    format: "preflight-release-capabilities-v1",
    productVersion,
    sourceRevision,
    sourceDirty: Boolean(sourceDirty),
    boundarySourceSha256: sourceReview.digest,
    engineJarSha256: sha256(jar),
    rendererBoundary: {
      nativeCommands: extractNativeCommands(nativeSource),
      tauriPermissions: exactStringArray(tauriCapability.permissions, "Tauri permissions"),
    },
    filesystem: policy.filesystem,
    processes: policy.processes,
    arbitraryShellCommandsAccepted: policy.arbitraryShellCommandsAccepted,
    network: {
      ...policy.network,
      updateConfigured: Boolean(environment.PREFLIGHT_UPDATER_PUBLIC_KEY?.trim()),
      updateEndpoint,
      reportIntakeOrigin,
      openedLinks: extractProjectLinks(nativeSource),
    },
  };
}

export function writeCapabilityReceipt(outputPath, options) {
  const receipt = buildCapabilityReceipt(options);
  writeFileSync(outputPath, `${JSON.stringify(receipt, null, 2)}\n`, { flag: "wx" });
  return receipt;
}

export function verifySourceLock(lock, root = repositoryRoot) {
  if (!lock || lock.format !== "preflight-capability-source-lock-v1" ||
      !lock.sources || typeof lock.sources !== "object" || Array.isArray(lock.sources)) {
    throw new Error("Invalid capability source lock");
  }
  const names = Object.keys(lock.sources);
  if (names.length === 0 || JSON.stringify(names) !== JSON.stringify([...names].sort())) {
    throw new Error("Capability source lock paths must be non-empty and sorted");
  }
  const aggregate = createHash("sha256");
  for (const name of names) {
    if (name.startsWith("/") || name.split("/").some((part) => !part || part === "." || part === "..")) {
      throw new Error(`Invalid capability source path: ${name}`);
    }
    const expected = lock.sources[name];
    if (!/^[a-f0-9]{64}$/.test(expected)) {
      throw new Error(`Invalid capability source digest: ${name}`);
    }
    const data = readFileSync(join(root, name));
    const actual = sha256(data);
    if (actual !== expected) {
      throw new Error(`Capability boundary changed without review: ${name}`);
    }
    aggregate.update(name);
    aggregate.update("\0");
    aggregate.update(actual);
    aggregate.update("\n");
  }
  return { digest: aggregate.digest("hex"), files: names.length };
}

export function extractNativeCommands(source) {
  const body = source.match(/tauri::generate_handler!\[([\s\S]*?)\]\)/)?.[1];
  if (!body) throw new Error("Could not find the native command registration boundary");
  const commands = body.split(",").map((value) => value.trim()).filter(Boolean);
  if (commands.length === 0 || commands.some((value) => !/^[a-z][a-z0-9_]*$/.test(value)) ||
      new Set(commands).size !== commands.length) {
    throw new Error("Native command registration is malformed");
  }
  return [...commands].sort();
}

export function extractProjectLinks(source) {
  const body = source.match(/const PROJECT_LINKS:[\s\S]*?= \[([\s\S]*?)\];/)?.[1];
  if (!body) throw new Error("Could not find the fixed project-link boundary");
  const links = {};
  for (const match of body.matchAll(/\(\s*"([a-z0-9-]+)"\s*,\s*"(https:\/\/[^"\s]+)"\s*,?\s*\)/g)) {
    links[match[1]] = match[2];
  }
  if (Object.keys(links).length === 0) throw new Error("No fixed project links were found");
  return Object.fromEntries(Object.entries(links).sort(([left], [right]) => left.localeCompare(right)));
}

function effectiveUpdateEndpoint(source, environment) {
  const fallback = source.match(/DEFAULT_UPDATE_ENDPOINT:[\s\S]*?=\s*"(https:\/\/[^"\s]+)"/)?.[1];
  if (!fallback) throw new Error("Could not find the default update endpoint");
  const endpoint = environment.PREFLIGHT_UPDATER_ENDPOINT?.trim() || fallback;
  return exactHttpsUrl(endpoint, { originOnly: false, rejectInvalidHost: false, label: "update endpoint" });
}

function effectiveReportOrigin(environment) {
  const value = environment.PREFLIGHT_REPORT_INTAKE_ORIGIN?.trim();
  return value
    ? exactHttpsUrl(value, { originOnly: true, rejectInvalidHost: true, label: "report intake origin" })
    : "disabled";
}

function exactHttpsUrl(value, { originOnly, rejectInvalidHost, label }) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`Invalid ${label}`);
  }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash ||
      (originOnly && url.pathname !== "/") ||
      (rejectInvalidHost && url.hostname.endsWith(".invalid"))) {
    throw new Error(`Invalid ${label}`);
  }
  return originOnly ? url.origin : url.href;
}

function exactStringArray(value, label) {
  if (!Array.isArray(value) || value.length === 0 ||
      value.some((item) => typeof item !== "string" || !item) || new Set(value).size !== value.length) {
    throw new Error(`${label} must be a non-empty duplicate-free string array`);
  }
  return [...value].sort();
}

function currentSourceRevision() {
  const configured = process.env.PREFLIGHT_SOURCE_REVISION?.trim() || process.env.GITHUB_SHA?.trim();
  if (configured) return configured.toLowerCase();
  const result = spawnSync("git", ["rev-parse", "HEAD"], { cwd: repositoryRoot, encoding: "utf8" });
  if (result.status !== 0) throw new Error("Could not identify the source revision");
  return result.stdout.trim().toLowerCase();
}

function currentSourceDirty() {
  const result = spawnSync("git", ["status", "--porcelain", "--untracked-files=normal"], {
    cwd: repositoryRoot,
    encoding: "utf8",
  });
  if (result.status !== 0) throw new Error("Could not inspect the source checkout");
  return result.stdout.trim().length > 0;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(data) {
  return createHash("sha256").update(data).digest("hex");
}
