import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(desktopRoot, "..");

function read(path) {
  return readFileSync(join(repositoryRoot, path), "utf8");
}

function sortedUnique(values) {
  return [...new Set(values)].sort();
}

function typeUnionValues(source, typeName) {
  const body = source.match(new RegExp(`export\\s+type\\s+${typeName}\\s*=\\s*([^;]+);`))?.[1];
  if (!body) throw new Error(`Could not find TypeScript ${typeName} union`);
  const values = [...body.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
  if (values.length === 0) throw new Error(`TypeScript ${typeName} union has no string values`);
  return sortedUnique(values);
}

function rustMatchValues(source, marker) {
  const markerStart = source.indexOf(marker);
  if (markerStart < 0) throw new Error(`Could not find Rust boundary ${marker}`);
  const matchStart = source.indexOf("match ", markerStart);
  const defaultArm = source.indexOf("_ =>", matchStart);
  if (matchStart < 0 || defaultArm < 0) throw new Error(`Could not find Rust match boundary after ${marker}`);
  const values = [...source.slice(matchStart, defaultArm).matchAll(/"([^"]+)"/g)]
    .map((match) => match[1]);
  if (values.length === 0) throw new Error(`Rust boundary ${marker} has no accepted string values`);
  return sortedUnique(values);
}

function javaEnumOptionValues(source, enumName, excluded = new Set()) {
  const start = source.indexOf(`enum ${enumName} {`);
  if (start < 0) throw new Error(`Could not find Java enum ${enumName}`);
  const end = source.indexOf(";", start);
  if (end < 0) throw new Error(`Could not find Java enum terminator for ${enumName}`);
  const values = [...source.slice(start, end).matchAll(/\b[A-Z][A-Z0-9_]*\("([^"]+)"/g)]
    .map((match) => match[1])
    .filter((value) => !excluded.has(value));
  if (values.length === 0) throw new Error(`Java enum ${enumName} has no product option values`);
  return sortedUnique(values);
}

function assertVocabulary(name, layers) {
  const baseline = sortedUnique(layers[0].values);
  for (const layer of layers.slice(1)) {
    const actual = sortedUnique(layer.values);
    const missing = baseline.filter((value) => !actual.includes(value));
    const extra = actual.filter((value) => !baseline.includes(value));
    if (missing.length || extra.length) {
      const differences = [
        missing.length ? `missing ${missing.map(JSON.stringify).join(", ")}` : null,
        extra.length ? `adds ${extra.map(JSON.stringify).join(", ")}` : null,
      ].filter(Boolean).join("; ");
      throw new Error(
        `${name} vocabulary drift: ${layer.name} ${differences}; `
        + `${layers[0].name} accepts [${baseline.join(", ")}], ${layer.name} accepts [${actual.join(", ")}]`,
      );
    }
  }
}

const types = read("preflight-desktop/src/types.ts");
const nativeHost = read("preflight-desktop/src-tauri/src/lib.rs");
const nativeEngine = read("preflight-desktop/src-tauri/src/engine.rs");
const javaPresets = read("preflight-cli/src/main/java/dev/starsector/preflight/cli/OptimizationPreset.java");
const javaDomains = read("preflight-cli/src/main/java/dev/starsector/preflight/cli/OptimizationDomain.java");
const javaRemoval = read("preflight-cli/src/main/java/dev/starsector/preflight/cli/UninstallCommand.java");

test("desktop optimization presets agree across TypeScript, Rust and Java", () => {
  assertVocabulary("optimization preset", [
    { name: "TypeScript", values: typeUnionValues(types, "OptimizationPreset") },
    { name: "Rust", values: rustMatchValues(nativeHost, "fn validate_optimization_preset") },
    {
      name: "Java",
      // CUSTOM is an internal raw-flag mode; OptimizationPreset.parse deliberately rejects it.
      values: javaEnumOptionValues(javaPresets, "OptimizationPreset", new Set(["custom"])),
    },
  ]);
});

test("desktop optimization domains agree across TypeScript, Rust and Java", () => {
  assertVocabulary("optimization domain", [
    { name: "TypeScript", values: typeUnionValues(types, "OptimizationDomain") },
    { name: "Rust", values: rustMatchValues(nativeHost, "fn validate_optimization_domains") },
    { name: "Java", values: javaEnumOptionValues(javaDomains, "OptimizationDomain") },
  ]);
});

test("after-launch behavior agrees across renderer and native host", () => {
  assertVocabulary("after-launch behavior", [
    { name: "TypeScript", values: typeUnionValues(types, "AfterLaunchBehavior") },
    { name: "Rust", values: rustMatchValues(nativeHost, "impl AfterLaunchBehavior") },
  ]);
});

test("removal scope agrees across TypeScript, Rust and Java", () => {
  assertVocabulary("removal scope", [
    { name: "TypeScript", values: typeUnionValues(types, "RemovalScope") },
    { name: "Rust", values: rustMatchValues(nativeEngine, "fn validate_removal_scope") },
    { name: "Java", values: javaEnumOptionValues(javaRemoval, "Scope") },
  ]);
});

test("vocabulary failures name the drifting layer and value", () => {
  assert.throws(
    () => assertVocabulary("example option", [
      { name: "TypeScript", values: ["one", "two"] },
      { name: "Rust", values: ["one", "three"] },
    ]),
    /example option vocabulary drift: Rust missing "two"; adds "three"/,
  );
});
