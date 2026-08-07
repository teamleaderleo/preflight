import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repository = "teamleaderleo/preflight";
const releaseAssetBase = `https://github.com/${repository}/releases/download`;
const releasePackages = [
  { pattern: /^Preflight-Linux-(x86_64|arm64)\.AppImage$/, platform: "linux" },
  { pattern: /^Preflight-macOS-(x86_64|arm64)\.app\.tar\.gz$/, platform: "darwin" },
  { pattern: /^Preflight-Windows-(x86_64|arm64)\.exe$/, platform: "windows" },
];
const supportedSystems = ["linux", "darwin", "windows"];

export function buildUpdaterManifest(
  inputDirectory,
  tag,
  requestedAssetBase = `${releaseAssetBase}/${tag}`,
  requiredSystems = supportedSystems,
) {
  const version = releaseVersion(tag);
  const assetBase = exactHttpsBase(requestedAssetBase);
  const required = exactRequiredSystems(requiredSystems);
  const entries = readdirSync(inputDirectory, { withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .sort();
  const platforms = {};
  const representedSystems = new Set();

  for (const filename of entries) {
    const definition = releasePackages.find(({ pattern }) => pattern.test(filename));
    if (!definition) continue;
    if (!required.includes(definition.platform)) continue;
    const match = filename.match(definition.pattern);
    const architecture = match[1] === "arm64" ? "aarch64" : match[1];
    const target = `${definition.platform}-${architecture}`;
    if (platforms[target]) throw new Error(`Duplicate updater package for ${target}`);
    const signaturePath = resolve(inputDirectory, `${filename}.sig`);
    if (!statSync(signaturePath, { throwIfNoEntry: false })?.isFile()) {
      throw new Error(`Missing updater signature: ${filename}.sig`);
    }
    const signature = readFileSync(signaturePath, "utf8").trim();
    if (!signature || signature.length > 16_384) {
      throw new Error(`Invalid updater signature: ${filename}.sig`);
    }
    platforms[target] = {
      signature,
      url: `${assetBase}/${filename}`,
    };
    representedSystems.add(definition.platform);
  }

  for (const system of required) {
    if (!representedSystems.has(system)) throw new Error(`Missing ${system} updater package`);
  }

  return {
    version,
    notes: `Preflight ${version}`,
    platforms: Object.fromEntries(Object.entries(platforms).sort(([left], [right]) => left.localeCompare(right))),
  };
}

export function writeUpdaterManifest(inputDirectory, tag, outputPath, assetBase, requiredSystems) {
  const json = `${JSON.stringify(buildUpdaterManifest(inputDirectory, tag, assetBase, requiredSystems), null, 2)}\n`;
  writeFileSync(outputPath, json);
  const digest = createHash("sha256").update(json).digest("hex");
  writeFileSync(`${outputPath}.sha256`, `${digest}  ${basename(outputPath)}\n`);
}

function exactRequiredSystems(values) {
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error("At least one updater system is required");
  }
  const unique = new Set(values);
  if (unique.size !== values.length || values.some((value) => !supportedSystems.includes(value))) {
    throw new Error(`Updater systems must be unique members of: ${supportedSystems.join(", ")}`);
  }
  return [...values];
}

function exactHttpsBase(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`Updater asset base must be an absolute HTTPS URL: ${value}`);
  }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
    throw new Error(`Updater asset base must be an absolute HTTPS URL without credentials, query, or fragment: ${value}`);
  }
  return url.toString().replace(/\/$/, "");
}

function releaseVersion(tag) {
  if (!/^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(tag)) {
    throw new Error(`Release tag must be v-prefixed SemVer: ${tag}`);
  }
  return tag.slice(1);
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const [, , inputDirectory, tag, requestedOutput, assetBase] = process.argv;
  if (!inputDirectory || !tag) {
    throw new Error("Usage: node build-updater-manifest.mjs <artifact-directory> <tag> [output] [asset-base-url]");
  }
  const output = resolve(requestedOutput ?? resolve(inputDirectory, "latest.json"));
  writeUpdaterManifest(resolve(inputDirectory), tag, output, assetBase);
  console.log(`Wrote signature-verified update manifest to ${output}`);
}
