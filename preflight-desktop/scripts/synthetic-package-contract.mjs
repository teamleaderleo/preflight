import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  chmodSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { inflateRawSync } from "node:zlib";
import { encodeArgv } from "./utf8-argv.mjs";

const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

/**
 * Runs only redistributable synthetic data through the installed engine. The fixture deliberately
 * uses spaces and non-ASCII characters so the result exercises path transport, not just cache code.
 */
export function exerciseSyntheticPackageContract(packageRoot, options = {}) {
  const engineDirectory = findPackagedEngine(packageRoot);
  const java = join(engineDirectory, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
  const jar = join(engineDirectory, "preflight.jar");
  const temporary = mkdtempSync(join(tmpdir(), "preflight-synthetic-contract-"));
  const home = join(temporary, "Operator Home");
  const fixture = join(temporary, "Synthetic Game – path Ω");
  const cache = join(home, ".starsector-preflight", "cache");
  const launcherSentinel = join(temporary, "launcher-was-executed.txt");
  const environment = isolatedEnvironment(home);
  const invoke = options.invoke ?? ((args) => capture(java, [
    `-Duser.home=${home}`,
    "-Djava.awt.headless=true",
    "-jar",
    jar,
    ...encodeArgv(args),
  ], { cwd: engineDirectory, env: environment }));

  try {
    mkdirSync(home, { recursive: true });
    const launcher = writeSyntheticInstallation(fixture, launcherSentinel);
    const before = treeIdentity(fixture);
    const expectedVersion = JSON.parse(readFileSync(join(engineDirectory, "bundle.json"), "utf8")).sourceVersion;

    const snapshot = JSON.parse(invoke(["desktop", "snapshot", "--game", fixture]));
    assertSyntheticDiscovery(snapshot, fixture, launcher, expectedVersion);

    const firstReport = join(temporary, "prepare-first.json");
    const secondReport = join(temporary, "prepare-second.json");
    const preparation = [
      "prepare",
      "--game", fixture,
      "--cache-dir", cache,
      "--workers", "2",
      "--memory-mb", "64",
      "--parallel-stages",
      "--no-textures",
    ];
    invoke([...preparation, "--report", firstReport]);
    invoke([...preparation, "--report", secondReport]);
    const first = JSON.parse(readFileSync(firstReport, "utf8"));
    const second = JSON.parse(readFileSync(secondReport, "utf8"));
    assertSyntheticPreparation(first, second);

    const dryRun = invoke([
      "run",
      "--game", fixture,
      "--dry-run",
      "--optimization-preset", "off",
      "--no-scan",
      "--no-summary",
      "--no-record",
      "--no-adapter",
    ]);
    if (!dryRun.includes(launcher)) {
      throw new Error(`Dry-run launch plan didn't contain the exact synthetic launcher: ${dryRun}`);
    }
    if (existsSync(launcherSentinel)) {
      throw new Error("Dry-run launch planning executed the synthetic launcher");
    }

    const evidenceDirectory = join(home, ".starsector-preflight", "runs", "fusion-synthetic");
    mkdirSync(evidenceDirectory, { recursive: true });
    writeFileSync(join(evidenceDirectory, "run.json"), "{\"kind\":\"synthetic-package-contract\"}\n");
    writeFileSync(join(evidenceDirectory, "console.txt"), "PRIVATE-SENTINEL-MUST-NOT-EXPORT\n");
    const diagnosticsPath = join(temporary, "diagnostics.zip");
    const diagnostics = JSON.parse(invoke([
      "evidence", "export", "--json", "--runs", "1", "--benchmarks", "0",
      "--output", diagnosticsPath,
    ]));
    assertSyntheticDiagnostics(diagnostics, diagnosticsPath);

    const after = treeIdentity(fixture);
    if (before !== after) {
      throw new Error("Synthetic discovery, preparation, or launch planning changed the fixture installation");
    }

    return {
      format: "preflight-synthetic-package-contract-v1",
      fixture: "generated-no-license-content",
      pathStress: "spaces-and-unicode",
      discovery: {
        ready: true,
        launcherKind: snapshot.selected.kind,
        explicitPathPreserved: true,
      },
      preparation: {
        firstPass: "built",
        secondPass: "reused",
        installationUnchanged: true,
      },
      launchPlan: {
        dryRun: true,
        exactLauncherPreserved: true,
        launcherExecuted: false,
      },
      diagnostics: {
        boundedExport: true,
        bytes: diagnostics.bytes,
        files: diagnostics.files,
        sha256: diagnostics.sha256,
        privateConsoleExcluded: true,
      },
    };
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
}

export function assertSyntheticDiscovery(snapshot, fixture, launcher, expectedVersion = null) {
  if (snapshot?.protocol !== 1 || snapshot?.ready !== true || !snapshot.selected
      || resolve(snapshot.selected.installRoot) !== resolve(fixture)
      || resolve(snapshot.selected.launcher) !== resolve(launcher)
      || !["windows-script", "shell-script"].includes(snapshot.selected.kind)
      || (expectedVersion !== null && snapshot.engineVersion !== expectedVersion)) {
    throw new Error(`Synthetic discovery result is malformed: ${JSON.stringify(snapshot)}`);
  }
}

export function assertSyntheticPreparation(first, second) {
  const firstStages = first?.stages ?? first;
  const secondStages = second?.stages ?? second;
  if (first?.successful !== true || second?.successful !== true
      || firstStages?.resourceIndex?.status !== "SUCCESS" || firstStages?.classpathIndex?.status !== "SUCCESS"
      || secondStages?.resourceIndex?.status !== "SUCCESS" || secondStages?.classpathIndex?.status !== "SUCCESS"
      || firstStages?.textures?.status !== "SKIPPED" || secondStages?.textures?.status !== "SKIPPED"
      || !containsBoolean(first, "artifactHit", false)
      || !containsBoolean(second, "artifactHit", true)) {
    throw new Error(
      `Synthetic preparation didn't establish a cold build and warm reuse: ${JSON.stringify({ first, second })}`,
    );
  }
}

export function assertSyntheticDiagnostics(result, output) {
  const included = Array.isArray(result?.included) ? result.included : [];
  const present = existsSync(output);
  const entries = present ? readZipEntries(output) : new Map();
  const name = (entry) => String(entry?.entry ?? entry?.source ?? entry?.path ?? "");
  // Reported one condition at a time. This assertion runs on three platforms and its failures are
  // read from a CI log, where a single combined verdict costs a whole run to narrow down.
  const problems = [];
  if (result?.format !== "starsector-preflight-diagnostics-export-v1") {
    problems.push(`format is ${JSON.stringify(result?.format)}`);
  }
  if (!present) {
    problems.push(`no archive at ${output}`);
  } else if (realpathSync(result.output) !== realpathSync(output)) {
    // Windows hands out short 8.3 directory names, so the archive the engine reports and the one
    // the caller asked for can be the same file under two spellings.
    problems.push(
      `archive path resolves to ${realpathSync(result.output)}, asked for ${realpathSync(output)}`,
    );
  }
  if (!Number.isSafeInteger(result?.bytes) || result.bytes <= 0) {
    problems.push(`bytes is ${JSON.stringify(result?.bytes)}`);
  }
  if (!Number.isSafeInteger(result?.files) || result.files < 3) {
    problems.push(`files is ${JSON.stringify(result?.files)}`);
  }
  if (!/^[a-f0-9]{64}$/.test(result?.sha256 ?? "")) {
    problems.push(`sha256 is ${JSON.stringify(result?.sha256)}`);
  }
  if (!included.some((entry) => name(entry).endsWith("run.json"))) {
    problems.push("no run.json among the included entries");
  }
  if (included.some((entry) => name(entry).endsWith("console.txt"))) {
    problems.push("console.txt was included");
  }
  for (const required of ["README.txt", "manifest.json", "runs/1/run.json"]) {
    if (!entries.has(required)) problems.push(`archive is missing ${required}`);
  }
  if ([...entries.keys()].some((entry) => entry.endsWith("console.txt"))) {
    problems.push("archive contains a console.txt");
  }
  if ([...entries.values()].some((bytes) => bytes.includes(Buffer.from("PRIVATE-SENTINEL-MUST-NOT-EXPORT")))) {
    problems.push("archive carries private content past the boundary");
  }
  if (problems.length) {
    throw new Error(
      `Synthetic diagnostics boundary is malformed (${problems.join("; ")}): ${JSON.stringify(result)}`,
    );
  }
}

function readZipEntries(path) {
  const archive = readFileSync(path);
  let endOffset = -1;
  for (let offset = archive.length - 22; offset >= Math.max(0, archive.length - 65_557); offset -= 1) {
    if (archive.readUInt32LE(offset) === 0x06054b50) {
      endOffset = offset;
      break;
    }
  }
  if (endOffset < 0) throw new Error(`Diagnostics ZIP has no end record: ${path}`);
  const count = archive.readUInt16LE(endOffset + 10);
  const centralOffset = archive.readUInt32LE(endOffset + 16);
  const entries = new Map();
  let cursor = centralOffset;
  let totalBytes = 0;
  for (let index = 0; index < count; index += 1) {
    if (cursor + 46 > archive.length || archive.readUInt32LE(cursor) !== 0x02014b50) {
      throw new Error(`Diagnostics ZIP has a malformed central directory: ${path}`);
    }
    const method = archive.readUInt16LE(cursor + 10);
    const compressedSize = archive.readUInt32LE(cursor + 20);
    const uncompressedSize = archive.readUInt32LE(cursor + 24);
    const nameLength = archive.readUInt16LE(cursor + 28);
    const extraLength = archive.readUInt16LE(cursor + 30);
    const commentLength = archive.readUInt16LE(cursor + 32);
    const localOffset = archive.readUInt32LE(cursor + 42);
    const name = archive.subarray(cursor + 46, cursor + 46 + nameLength).toString("utf8");
    if (!name || name.startsWith("/") || name.includes("\\")
        || name.split("/").some((part) => !part || part === "." || part === "..")
        || entries.has(name) || localOffset + 30 > archive.length
        || archive.readUInt32LE(localOffset) !== 0x04034b50) {
      throw new Error(`Diagnostics ZIP has an unsafe entry: ${name}`);
    }
    const localNameLength = archive.readUInt16LE(localOffset + 26);
    const localExtraLength = archive.readUInt16LE(localOffset + 28);
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
    const localName = archive.subarray(localOffset + 30, localOffset + 30 + localNameLength).toString("utf8");
    if (localName !== name || dataOffset + compressedSize > archive.length) {
      throw new Error(`Diagnostics ZIP local entry differs from its directory: ${name}`);
    }
    const compressed = archive.subarray(dataOffset, dataOffset + compressedSize);
    const bytes = method === 0 ? compressed : method === 8 ? inflateRawSync(compressed) : null;
    if (!bytes || bytes.length !== uncompressedSize) {
      throw new Error(`Diagnostics ZIP entry has an unsupported or inconsistent encoding: ${name}`);
    }
    totalBytes += bytes.length;
    if (totalBytes > 64 * 1024 * 1024) throw new Error(`Diagnostics ZIP is unexpectedly large: ${path}`);
    entries.set(name, bytes);
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

function writeSyntheticInstallation(root, launcherSentinel) {
  const core = join(root, "starsector-core");
  const mod = join(root, "mods", "Synthetic Mod");
  writeFixture(join(core, "graphics", "synthetic.png"), PNG_1X1);
  writeStoredZip(join(core, "starfarer_obf.jar"), new Map([
    ["synthetic/Game.class", Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 52])],
  ]));
  writeFixture(join(mod, "mod_info.json"), Buffer.from(
    "{\"id\":\"synthetic_mod\",\"name\":\"Synthetic Mod\",\"jars\":[\"jars/synthetic.jar\"]}\n",
  ));
  writeStoredZip(join(mod, "jars", "synthetic.jar"), new Map([
    ["synthetic/Plugin.class", Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 52])],
    ["synthetic/config.json", Buffer.from("{\"synthetic\":true}\n")],
  ]));
  writeFixture(
    join(root, "mods", "enabled_mods.json"),
    Buffer.from("{\"enabledMods\":[\"synthetic_mod\"]}\n"),
  );
  const launcher = process.platform === "win32"
    ? join(root, "starsector.bat")
    : join(root, "starsector.sh");
  const contents = process.platform === "win32"
    ? `@echo off\r\n>"${launcherSentinel}" echo executed\r\n`
    : `#!/bin/sh\nprintf '%s\\n' executed > '${launcherSentinel.replaceAll("'", "'\\''")}'\n`;
  writeFixture(launcher, Buffer.from(contents));
  if (process.platform !== "win32") chmodSync(launcher, 0o700);
  return launcher;
}

function writeFixture(path, bytes) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes, { mode: 0o600 });
}

function treeIdentity(root) {
  const hash = createHash("sha256");
  for (const path of regularFiles(root)) {
    const name = relative(root, path).split(sep).join("/");
    const bytes = readFileSync(path);
    hash.update(Buffer.from(name, "utf8"));
    hash.update(Buffer.from([0]));
    hash.update(bytes);
  }
  return hash.digest("hex");
}

function regularFiles(root) {
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const path = join(root, entry.name);
    const details = lstatSync(path);
    if (details.isSymbolicLink()) throw new Error(`Synthetic fixture contains a symbolic link: ${path}`);
    if (details.isDirectory()) result.push(...regularFiles(path));
    else if (details.isFile()) result.push(path);
    else throw new Error(`Synthetic fixture contains an unsupported entry: ${path}`);
  }
  return result;
}

function findPackagedEngine(root) {
  const candidates = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (!entry.isDirectory() || entry.isSymbolicLink()) continue;
      if (entry.name.toLowerCase() === "engine"
          && existsSync(join(path, "bundle.json"))
          && existsSync(join(path, "preflight.jar"))
          && existsSync(join(path, "runtime"))) {
        candidates.push(path);
      } else {
        visit(path);
      }
    }
  };
  visit(resolve(root));
  if (candidates.length !== 1) {
    throw new Error(`Installed package must contain one engine; found ${candidates.length}`);
  }
  return candidates[0];
}

function isolatedEnvironment(home) {
  return {
    ...process.env,
    HOME: home,
    USERPROFILE: home,
    LOCALAPPDATA: join(home, "AppData", "Local"),
    STARSECTOR_HOME: "",
    STARSECTOR_DIR: "",
  };
}

function capture(command, args, options) {
  const result = spawnSync(command, args, { ...options, encoding: "utf8", stdio: "pipe" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${basename(command)} exited with ${result.status}: ${(result.stderr ?? "").trim()}`);
  }
  return result.stdout;
}

function containsBoolean(value, key, expected) {
  if (!value || typeof value !== "object") return false;
  if (value[key] === expected) return true;
  return Object.values(value).some((nested) => containsBoolean(nested, key, expected));
}

function writeStoredZip(path, entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const [name, raw] of [...entries.entries()].sort(([left], [right]) => left.localeCompare(right))) {
    const filename = Buffer.from(name, "utf8");
    const data = Buffer.from(raw);
    const checksum = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x0021, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(filename.length, 26);
    localParts.push(local, filename, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x0021, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(filename.length, 28);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, filename);
    offset += local.length + filename.length + data.length;
  }
  const centralBytes = centralParts.reduce((total, part) => total + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.size, 8);
  end.writeUInt16LE(entries.size, 10);
  end.writeUInt32LE(centralBytes, 12);
  end.writeUInt32LE(offset, 16);
  writeFixture(path, Buffer.concat([...localParts, ...centralParts, end]));
}

const CRC32_TABLE = Array.from({ length: 256 }, (_, value) => {
  let result = value;
  for (let bit = 0; bit < 8; bit += 1) result = (result >>> 1) ^ ((result & 1) ? 0xedb88320 : 0);
  return result >>> 0;
});

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) value = CRC32_TABLE[(value ^ byte) & 0xff] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}
