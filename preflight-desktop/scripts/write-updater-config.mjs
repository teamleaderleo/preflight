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

export function updaterRehearsalConfig(publicKey, version) {
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version ?? "")) {
    throw new Error(`Rehearsal version must be SemVer: ${version}`);
  }
  return {
    ...updaterReleaseConfig(publicKey),
    version,
  };
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const output = process.argv[2];
  const version = process.argv[3];
  if (!output) throw new Error("Usage: node write-updater-config.mjs <output> [version]");
  const destination = resolve(output);
  mkdirSync(dirname(destination), { recursive: true });
  const config = version
    ? updaterRehearsalConfig(process.env.PREFLIGHT_UPDATER_PUBLIC_KEY, version)
    : updaterReleaseConfig(process.env.PREFLIGHT_UPDATER_PUBLIC_KEY);
  writeFileSync(
    destination,
    `${JSON.stringify(config, null, 2)}\n`,
    { mode: 0o600 },
  );
  console.log(`Wrote release updater configuration to ${destination}`);
}
