import { constants, copyFileSync, lstatSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildUpdaterManifest } from "./build-updater-manifest.mjs";
import { createHash } from "node:crypto";

const systemByPlatform = new Map([
  ["darwin", "darwin"],
  ["linux", "linux"],
  ["win32", "windows"],
]);

export function stageUpdateRehearsal(
  inputDirectory,
  tag,
  assetBase,
  outputDirectory,
  platform = process.platform,
  signatureMode = "valid",
) {
  if (signatureMode !== "valid" && signatureMode !== "rejected") {
    throw new Error(`Unknown rehearsal signature mode: ${signatureMode}`);
  }
  const system = systemByPlatform.get(platform);
  if (!system) throw new Error(`Update rehearsal isn't supported on ${platform}`);
  const input = resolve(inputDirectory);
  const output = resolve(outputDirectory);
  if (output === input) throw new Error("Rehearsal output must differ from its artifact input");
  const manifest = buildUpdaterManifest(input, tag, assetBase, [system]);
  mkdirSync(output, { recursive: false, mode: 0o700 });
  const copied = [];
  for (const entry of Object.values(manifest.platforms)) {
    const filename = exactAssetFilename(entry.url);
    copyRegularFile(resolve(input, filename), resolve(output, filename));
    copyRegularFile(resolve(input, `${filename}.sig`), resolve(output, `${filename}.sig`));
    copied.push(filename, `${filename}.sig`);
  }
  if (signatureMode === "rejected") {
    for (const entry of Object.values(manifest.platforms)) {
      entry.signature = rejectedSignature(entry.signature);
    }
  }
  const json = `${JSON.stringify(manifest, null, 2)}\n`;
  writeFileSync(resolve(output, "latest.json"), json, { flag: "wx", mode: 0o600 });
  const digest = createHash("sha256").update(json).digest("hex");
  writeFileSync(resolve(output, "latest.json.sha256"), `${digest}  latest.json\n`, {
    flag: "wx",
    mode: 0o600,
  });
  return {
    output,
    version: manifest.version,
    platforms: Object.keys(manifest.platforms),
    signatureMode,
    copied,
  };
}

export function rejectedSignature(signature) {
  let decoded;
  try {
    decoded = Buffer.from(signature, "base64").toString("utf8");
  } catch {
    throw new Error("Updater signature isn't valid base64");
  }
  const lines = decoded.split("\n");
  if (lines.length < 4 || !lines[0].startsWith("untrusted comment:") ||
      !lines[2].startsWith("trusted comment:") || !/^[A-Za-z0-9+/=]+$/.test(lines[1])) {
    throw new Error("Updater signature isn't a recognized Minisign document");
  }
  const offset = Math.floor(lines[1].length / 2);
  lines[1] = `${lines[1].slice(0, offset)}${lines[1][offset] === "A" ? "B" : "A"}${lines[1].slice(offset + 1)}`;
  return Buffer.from(lines.join("\n"), "utf8").toString("base64");
}

function exactAssetFilename(value) {
  const url = new URL(value);
  const filename = decodeURIComponent(url.pathname.split("/").at(-1));
  if (!filename || basename(filename) !== filename || filename.includes("\\") || filename.includes("\0")) {
    throw new Error(`Unsafe updater asset filename: ${value}`);
  }
  return filename;
}

function copyRegularFile(source, destination) {
  const details = lstatSync(source, { throwIfNoEntry: false });
  if (!details?.isFile() || details.isSymbolicLink()) {
    throw new Error(`Rehearsal artifact isn't a regular file: ${source}`);
  }
  if (readFileSync(source).length === 0) throw new Error(`Rehearsal artifact is empty: ${source}`);
  copyFileSync(source, destination, constants.COPYFILE_EXCL);
}

function main() {
  const [, , inputDirectory, tag, assetBase, outputDirectory, requestedMode] = process.argv;
  if (!inputDirectory || !tag || !assetBase || !outputDirectory) {
    throw new Error(
      "Usage: node stage-update-rehearsal.mjs <artifact-directory> <tag> <asset-base-url> " +
      "<new-output-directory> [valid|rejected]",
    );
  }
  const report = stageUpdateRehearsal(
    inputDirectory,
    tag,
    assetBase,
    outputDirectory,
    process.platform,
    requestedMode ?? "valid",
  );
  console.log(JSON.stringify(report, null, 2));
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) main();
