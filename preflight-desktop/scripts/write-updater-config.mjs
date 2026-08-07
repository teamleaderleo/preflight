import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function updaterReleaseConfig(publicKey) {
  const key = publicKey?.trim();
  if (!key || key.length > 16_384) {
    throw new Error("PREFLIGHT_UPDATER_PUBLIC_KEY is missing or invalid");
  }
  return {
    bundle: { createUpdaterArtifacts: true },
    plugins: { updater: { pubkey: key } },
  };
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const output = process.argv[2];
  if (!output) throw new Error("Usage: node write-updater-config.mjs <output>");
  const destination = resolve(output);
  mkdirSync(dirname(destination), { recursive: true });
  writeFileSync(
    destination,
    `${JSON.stringify(updaterReleaseConfig(process.env.PREFLIGHT_UPDATER_PUBLIC_KEY), null, 2)}\n`,
    { mode: 0o600 },
  );
  console.log(`Wrote release updater configuration to ${destination}`);
}
