import { rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const bundleDirectory = join(desktopDirectory, "src-tauri", "target", "release", "bundle");

export function cleanBundleOutput(requestedDirectory = bundleDirectory) {
  const requested = resolve(requestedDirectory);
  if (requested !== bundleDirectory) {
    throw new Error(`Refusing to clean anything except the reviewed Tauri bundle output: ${requested}`);
  }
  rmSync(requested, { recursive: true, force: true });
  return requested;
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) console.log(`Cleared ${cleanBundleOutput()}`);
