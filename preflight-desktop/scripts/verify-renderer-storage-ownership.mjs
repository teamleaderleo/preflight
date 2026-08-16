import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_DESKTOP_ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const STORAGE_OWNER = "src/desktopStorage.ts";
const THEME_BOOTSTRAP = "public/theme-init.js";
const PRODUCTION_EXTENSIONS = new Set([".js", ".jsx", ".ts", ".tsx"]);
const NON_STORAGE_PREFLIGHT_LITERALS = new Map([
  ["src/components/PreparationPage.tsx", new Set(["preflight.jar"])],
]);

function portable(path) {
  return path.split(sep).join("/");
}

function productionFiles(directory, root = directory) {
  const files = [];
  for (const entry of readdirSync(directory)) {
    const absolute = join(directory, entry);
    const info = statSync(absolute);
    if (info.isDirectory()) {
      files.push(...productionFiles(absolute, root));
      continue;
    }
    if (!info.isFile() || entry.includes(".test.") || entry.includes(".spec.")) continue;
    const extension = entry.slice(entry.lastIndexOf("."));
    if (PRODUCTION_EXTENSIONS.has(extension)) files.push(portable(relative(root, absolute)));
  }
  return files;
}

function stringLiterals(source) {
  return [...source.matchAll(/(["'`])(preflight\.[A-Za-z0-9._-]+)\1/g)].map((match) => match[2]);
}

function literalStorageArguments(source) {
  return [...source.matchAll(/(?:window\.)?localStorage\s*\.\s*(?:getItem|setItem|removeItem)\s*\(\s*(["'`])([^"'`\r\n]+)\1/g)]
    .map((match) => match[2]);
}

function storageInventory(source) {
  const constants = new Map();
  for (const match of source.matchAll(/export const ([A-Z][A-Z0-9_]*_STORAGE_KEY)\s*=\s*(["'])([^"']+)\2\s*;/g)) {
    constants.set(match[1], match[3]);
  }
  if (constants.size === 0) throw new Error(`${STORAGE_OWNER}: no storage-key constants found`);
  const listBody = source.match(/export const PREFLIGHT_LOCAL_STORAGE_KEYS\s*=\s*Object\.freeze\(\[([\s\S]*?)\]\s*as const\);/)?.[1];
  if (!listBody) throw new Error(`${STORAGE_OWNER}: PREFLIGHT_LOCAL_STORAGE_KEYS could not be parsed`);
  const listed = [...listBody.matchAll(/\b([A-Z][A-Z0-9_]*_STORAGE_KEY)\b/g)].map((match) => match[1]);
  if (new Set(listed).size !== listed.length) throw new Error(`${STORAGE_OWNER}: storage inventory contains a duplicate constant`);
  for (const name of constants.keys()) {
    if (!listed.includes(name)) throw new Error(`${STORAGE_OWNER}: ${name} is missing from PREFLIGHT_LOCAL_STORAGE_KEYS`);
  }
  for (const name of listed) {
    if (!constants.has(name)) throw new Error(`${STORAGE_OWNER}: inventory names unknown constant ${name}`);
  }
  const values = [...constants.values()];
  if (new Set(values).size !== values.length) throw new Error(`${STORAGE_OWNER}: storage-key values must be unique`);
  for (const value of values) {
    if (!value.startsWith("preflight.")) throw new Error(`${STORAGE_OWNER}: storage key ${JSON.stringify(value)} is outside the preflight.* namespace`);
  }
  return { constants, values };
}

export function verifyRendererStorageOwnership(desktopRoot = DEFAULT_DESKTOP_ROOT) {
  const ownerPath = join(desktopRoot, STORAGE_OWNER);
  const bootstrapPath = join(desktopRoot, THEME_BOOTSTRAP);
  const inventory = storageInventory(readFileSync(ownerPath, "utf8"));
  const themeKey = inventory.constants.get("THEME_STORAGE_KEY");
  if (!themeKey) throw new Error(`${STORAGE_OWNER}: THEME_STORAGE_KEY is required by the pre-paint bootstrap`);

  const bootstrap = readFileSync(bootstrapPath, "utf8");
  const bootstrapArguments = literalStorageArguments(bootstrap);
  const bootstrapLiterals = stringLiterals(bootstrap);
  if (bootstrapArguments.length !== 1 || bootstrapArguments[0] !== themeKey) {
    throw new Error(`${THEME_BOOTSTRAP}: pre-paint storage access must use exactly ${JSON.stringify(themeKey)}`);
  }
  if (bootstrapLiterals.length !== 1 || bootstrapLiterals[0] !== themeKey) {
    throw new Error(`${THEME_BOOTSTRAP}: only the reviewed theme key ${JSON.stringify(themeKey)} may be duplicated`);
  }

  const rendererFiles = [
    ...productionFiles(join(desktopRoot, "src"), desktopRoot),
    ...productionFiles(join(desktopRoot, "public"), desktopRoot),
  ].sort();
  for (const file of rendererFiles) {
    if (file === STORAGE_OWNER || file === THEME_BOOTSTRAP) continue;
    const source = readFileSync(join(desktopRoot, file), "utf8");
    const allowed = NON_STORAGE_PREFLIGHT_LITERALS.get(file) ?? new Set();
    for (const literal of stringLiterals(source)) {
      if (!allowed.has(literal)) {
        throw new Error(`${file}: renderer storage literal ${JSON.stringify(literal)} bypasses ${STORAGE_OWNER}`);
      }
    }
    const rawArgument = literalStorageArguments(source)[0];
    if (rawArgument !== undefined) {
      throw new Error(`${file}: literal localStorage key ${JSON.stringify(rawArgument)} bypasses ${STORAGE_OWNER}`);
    }
  }

  return { files: rendererFiles.length, keys: inventory.values.length, themeKey };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = verifyRendererStorageOwnership();
  console.log(`Renderer storage ownership verified: ${result.keys} keys across ${result.files} production files.`);
}
