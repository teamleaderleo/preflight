import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function validateReleaseVersion(tag, packageVersion, applicationVersion, rustPackageVersion) {
  if (!/^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(tag)) {
    throw new Error(`Release tag must be v-prefixed SemVer: ${tag}`);
  }
  const version = tag.slice(1);
  if (packageVersion !== version || applicationVersion !== version || rustPackageVersion !== version) {
    throw new Error(
      `Release ${tag} doesn't match package.json ${packageVersion}, tauri.conf.json ${applicationVersion}, and Cargo.toml ${rustPackageVersion}`,
    );
  }
  return version;
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const tag = process.argv[2];
  if (!tag) throw new Error("Usage: node validate-release-version.mjs <tag>");
  const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const packageJson = JSON.parse(readFileSync(resolve(desktopDirectory, "package.json"), "utf8"));
  const tauriConfig = JSON.parse(readFileSync(resolve(desktopDirectory, "src-tauri", "tauri.conf.json"), "utf8"));
  const cargoToml = readFileSync(resolve(desktopDirectory, "src-tauri", "Cargo.toml"), "utf8");
  const rustPackageVersion = cargoToml.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  const version = validateReleaseVersion(tag, packageJson.version, tauriConfig.version, rustPackageVersion);
  console.log(`Release versions agree on ${version}`);
}
