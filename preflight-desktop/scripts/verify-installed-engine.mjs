import { lstatSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { verifyExtractedPayload } from "./verify-native-package.mjs";

export function verifyInstalledEngine(root, options = {}) {
  const installedRoot = resolve(root);
  const details = lstatSync(installedRoot, { throwIfNoEntry: false });
  if (!details?.isDirectory() || details.isSymbolicLink()) {
    throw new Error(`Installed package root isn't a real directory: ${installedRoot}`);
  }
  return verifyExtractedPayload(installedRoot, options);
}

function main() {
  if (process.argv.length !== 3) throw new Error("Usage: verify-installed-engine.mjs <installed-package-root>");
  console.log(JSON.stringify(verifyInstalledEngine(process.argv[2])));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
