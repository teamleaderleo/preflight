import { spawnSync } from "node:child_process";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { verifyInstalledEngine } from "./verify-installed-engine.mjs";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const bundleDirectory = join(desktopDirectory, "src-tauri", "target", "release", "bundle");

export function exerciseMacInstall(directory = bundleDirectory) {
  if (process.platform !== "darwin") throw new Error("macOS installation exercise requires macOS");
  const packagePath = onlyFile(directory, ".dmg");
  const mountDirectory = mkdtempSync(join(tmpdir(), "preflight-native-dmg-"));
  const installDirectory = mkdtempSync(join(tmpdir(), "preflight-native-install-"));
  const dataDirectory = mkdtempSync(join(tmpdir(), "preflight-native-data-"));
  const dataSentinel = join(dataDirectory, "user-data-sentinel");
  let mounted = false;
  try {
    writeFileSync(dataSentinel, "retained outside the application bundle\n", { mode: 0o600 });
    run("hdiutil", [
      "attach",
      "-readonly",
      "-nobrowse",
      "-noautoopen",
      "-mountpoint",
      mountDirectory,
      packagePath,
    ]);
    mounted = true;
    const packagedApp = join(mountDirectory, "Preflight.app");
    if (!lstatSync(packagedApp, { throwIfNoEntry: false })?.isDirectory()) {
      throw new Error("Mounted DMG doesn't contain Preflight.app");
    }
    const installedApp = join(installDirectory, "Preflight.app");
    run("ditto", [packagedApp, installedApp]);
    const report = verifyInstalledEngine(installedApp);
    const desktopSmokeProbe = exercisePackagedDesktopSmokeProbe(installedApp);
    const allDataRemoval = exercisePackagedAllDataRemoval(installedApp);
    const installedFiles = regularFiles(installedApp);
    if (installedFiles.length === 0) throw new Error("Installed macOS app owns no files");
    rmSync(installedApp, { recursive: true });
    const remainingFiles = installedFiles.filter((path) => existsSync(path));
    if (remainingFiles.length) {
      throw new Error(`macOS removal left ${remainingFiles.length} app file(s): ${remainingFiles.slice(0, 5).join(", ")}`);
    }
    if (readFileSync(dataSentinel, "utf8") !== "retained outside the application bundle\n") {
      throw new Error("Removing the macOS app changed separately owned user data");
    }
    return {
      package: basename(packagePath),
      installedCopy: true,
      removed: true,
      separateDataRetained: true,
      desktopSmokeProbe,
      allDataRemoval,
      engine: report.engine,
    };
  } finally {
    if (mounted) run("hdiutil", ["detach", mountDirectory]);
    rmSync(mountDirectory, { recursive: true, force: true });
    rmSync(installDirectory, { recursive: true, force: true });
    rmSync(dataDirectory, { recursive: true, force: true });
  }
}

export function exerciseDebianInstall(directory = bundleDirectory) {
  if (process.platform !== "linux") throw new Error("Debian installation exercise requires Linux");
  const packagePath = onlyFile(directory, ".deb");
  const packageName = capture("dpkg-deb", ["--field", packagePath, "Package"]).trim();
  if (!packageName || spawnSync("dpkg-query", ["--show", packageName]).status === 0) {
    throw new Error(`Development runner already has ${packageName || "the package"} installed`);
  }
  let cleanupNeeded = true;
  try {
    run("sudo", ["dpkg", "--install", packagePath]);
    const packageFiles = capture("dpkg-query", ["--listfiles", packageName]).trim().split(/\r?\n/);
    const manifests = packageFiles.filter((path) => /[/\\]engine[/\\]bundle\.json$/.test(path));
    if (manifests.length !== 1) {
      throw new Error(`Installed Debian package must own one engine manifest; found ${manifests.length}`);
    }
    const engineRoot = dirname(manifests[0]);
    const installedFiles = packageFiles.filter((path) => {
      const details = lstatSync(path, { throwIfNoEntry: false });
      return details && !details.isDirectory();
    });
    if (installedFiles.length === 0) throw new Error("Installed Debian package owns no files");
    const report = verifyInstalledEngine(dirname(engineRoot));
    const desktopSmokeProbe = exercisePackagedDesktopSmokeProbe(dirname(engineRoot));
    const allDataRemoval = exercisePackagedAllDataRemoval(dirname(engineRoot));
    run("sudo", ["dpkg", "--remove", packageName]);
    const remainingFiles = installedFiles.filter((path) => existsSync(path));
    if (remainingFiles.length) {
      throw new Error(`Debian removal left ${remainingFiles.length} owned file(s): ${remainingFiles.slice(0, 5).join(", ")}`);
    }
    const status = spawnSync("dpkg-query", ["--show", "--showformat=${db:Status-Abbrev}", packageName], {
      encoding: "utf8",
      stdio: "pipe",
    });
    if (status.status === 0 && status.stdout.startsWith("ii")) {
      throw new Error(`${packageName} remained installed after removal`);
    }
    cleanupNeeded = false;
    return {
      package: basename(packagePath),
      packageName,
      removed: true,
      desktopSmokeProbe,
      allDataRemoval,
      engine: report.engine,
    };
  } finally {
    if (cleanupNeeded) spawnSync("sudo", ["dpkg", "--purge", packageName], { stdio: "ignore" });
  }
}

export function exerciseNsisInstall(directory = bundleDirectory) {
  if (process.platform !== "win32") throw new Error("NSIS installation exercise requires Windows");
  const packagePath = onlyFile(directory, "-setup.exe");
  const installDirectory = join(tmpdir(), `preflight-native-install-${process.pid}`);
  if (existsSync(installDirectory)) throw new Error(`Install test directory already exists: ${installDirectory}`);
  try {
    run(packagePath, ["/S", `/D=${installDirectory}`]);
    const report = verifyInstalledEngine(installDirectory);
    const desktopSmokeProbe = exercisePackagedDesktopSmokeProbe(installDirectory);
    const allDataRemoval = exercisePackagedAllDataRemoval(installDirectory);
    const installedFiles = regularFiles(installDirectory);
    if (installedFiles.length === 0) throw new Error("Installed NSIS package owns no files");
    const uninstallers = filesWithSuffix(installDirectory, "uninstall.exe");
    if (uninstallers.length !== 1) {
      throw new Error(`Installed NSIS package must contain one uninstaller; found ${uninstallers.length}`);
    }
    run(uninstallers[0], ["/S"]);
    let remainingFiles = installedFiles.filter((path) => existsSync(path));
    for (let attempt = 0; attempt < 20 && remainingFiles.length; attempt += 1) {
      synchronousPause(100);
      remainingFiles = installedFiles.filter((path) => existsSync(path));
    }
    if (remainingFiles.length) {
      throw new Error(`NSIS removal left ${remainingFiles.length} owned file(s): ${remainingFiles.slice(0, 5).join(", ")}`);
    }
    return { package: basename(packagePath), removed: true, desktopSmokeProbe, allDataRemoval, engine: report.engine };
  } finally {
    if (existsSync(installDirectory)) rmSync(installDirectory, { recursive: true, force: true });
  }
}

export function exercisePackagedDesktopSmokeProbe(packageRoot) {
  const engineDirectory = onlyPackagedEngine(packageRoot);
  const java = join(engineDirectory, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
  const probe = JSON.parse(capture(
    java,
    ["-jar", join(engineDirectory, "preflight.jar"), "desktop", "smoke", "probe"],
    { cwd: engineDirectory },
  ));
  if (probe.protocol !== 1 || typeof probe.probe?.ready !== "boolean"
      || !Array.isArray(probe.probe?.diagnostics)) {
    throw new Error(`Packaged desktop smoke probe is malformed: ${JSON.stringify(probe)}`);
  }
  if (probe.probe.ready) {
    if (typeof probe.probe.driver?.id !== "string"
        || !Array.isArray(probe.probe.driver?.capabilities)) {
      throw new Error(`Ready packaged desktop smoke probe has no driver: ${JSON.stringify(probe)}`);
    }
  } else if (probe.probe.driver !== null || probe.probe.diagnostics.length === 0) {
    throw new Error(`Unavailable packaged desktop smoke probe has no reason: ${JSON.stringify(probe)}`);
  }
  return {
    ready: probe.probe.ready,
    driver: probe.probe.driver?.id ?? null,
    diagnostic: probe.probe.diagnostics[0] ?? null,
  };
}

export function exercisePackagedAllDataRemoval(packageRoot) {
  const engineDirectory = onlyPackagedEngine(packageRoot);
  const java = join(engineDirectory, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
  const jar = join(engineDirectory, "preflight.jar");
  const isolatedHome = mkdtempSync(join(tmpdir(), "preflight-removal-home-"));
  const localAppData = join(isolatedHome, "AppData", "Local");
  const ownedRoot = join(isolatedHome, ".starsector-preflight");
  const preserved = [
    join(isolatedHome, "Starsector", "starsector-core", "data", "config", "settings.json"),
    join(isolatedHome, "Starsector", "mods", "example", "mod_info.json"),
    join(isolatedHome, ".starsector", "saves", "example", "campaign.xml"),
  ];
  const integrations = packagedRemovalIntegrations(isolatedHome, localAppData);
  try {
    writeFixture(join(ownedRoot, "cache", "profile", "artifact.bin"), "owned cache\n");
    writeFixture(join(ownedRoot, "runs", "run-1", "evidence.json"), "owned evidence\n");
    for (const path of integrations) writeFixture(path, "owned integration\n");
    for (const path of preserved) writeFixture(path, `preserved ${basename(path)}\n`);
    const before = new Map(preserved.map((path) => [path, readFileSync(path)]));
    const environment = {
      ...process.env,
      HOME: isolatedHome,
      USERPROFILE: isolatedHome,
      LOCALAPPDATA: localAppData,
    };
    const common = [`-Duser.home=${isolatedHome}`, "-jar", jar, "uninstall", "--scope", "all-data", "--json"];
    const preview = JSON.parse(capture(java, common, { cwd: engineDirectory, env: environment }));
    assertRemovalResult(preview, false);
    const expectedTargets = [ownedRoot, ...packagedRemovalTargets(isolatedHome, localAppData)]
      .map((path) => resolve(path))
      .sort();
    const previewTargets = preview.targets.map((target) => resolve(target.path)).sort();
    if (JSON.stringify(previewTargets) !== JSON.stringify(expectedTargets)) {
      throw new Error(`All-data preview targets differ: expected ${expectedTargets}; got ${previewTargets}`);
    }
    for (const path of expectedTargets) {
      if (!existsSync(path)) throw new Error(`All-data preview changed its target: ${path}`);
    }

    const applied = JSON.parse(capture(java, [...common, "--yes"], { cwd: engineDirectory, env: environment }));
    assertRemovalResult(applied, true);
    const remaining = expectedTargets.filter((path) => existsSync(path));
    if (remaining.length) throw new Error(`All-data removal left owned targets: ${remaining.join(", ")}`);
    for (const [path, bytes] of before) {
      if (!existsSync(path) || !readFileSync(path).equals(bytes)) {
        throw new Error(`All-data removal changed separately owned game data: ${path}`);
      }
    }
    return {
      previewed: true,
      applied: true,
      ownedTargetsRemoved: expectedTargets.length,
      gameModAndSaveDataRetained: true,
    };
  } finally {
    rmSync(isolatedHome, { recursive: true, force: true });
  }
}

function assertRemovalResult(result, applied) {
  if (result.format !== "preflight-removal-v1" || result.scope !== "all-data"
      || result.safe !== true || result.applied !== applied || !Array.isArray(result.targets)) {
    throw new Error(`Unexpected all-data removal result: ${JSON.stringify(result)}`);
  }
}

function packagedRemovalIntegrations(home, localAppData) {
  if (process.platform === "darwin") return [join(home, "Applications", "Preflight.app", "Contents", "fixture")];
  if (process.platform === "linux") return [
    join(home, ".local", "bin", "preflight"),
    join(home, ".local", "share", "applications", "preflight.desktop"),
  ];
  if (process.platform === "win32") return [join(localAppData, "Preflight", "Preflight.cmd")];
  throw new Error(`Packaged removal exercise isn't implemented for ${process.platform}`);
}

function packagedRemovalTargets(home, localAppData) {
  if (process.platform === "darwin") return [join(home, "Applications", "Preflight.app")];
  if (process.platform === "linux") return packagedRemovalIntegrations(home, localAppData);
  if (process.platform === "win32") return [join(localAppData, "Preflight")];
  throw new Error(`Packaged removal exercise isn't implemented for ${process.platform}`);
}

function onlyPackagedEngine(root) {
  const engines = regularDirectories(root).filter((path) => {
    if (basename(path).toLowerCase() !== "engine") return false;
    const names = new Set(readdirSync(path));
    return ["bundle.json", "preflight.jar", "runtime"].every((name) => names.has(name));
  });
  if (engines.length !== 1) throw new Error(`Installed package must contain one engine; found ${engines.length}`);
  return engines[0];
}

function regularDirectories(directory) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory() && !entry.isSymbolicLink()) {
      result.push(path, ...regularDirectories(path));
    }
  }
  return result;
}

function writeFixture(path, contents) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, contents);
}

function onlyFile(directory, suffix) {
  const files = filesWithSuffix(directory, suffix);
  if (files.length !== 1) throw new Error(`Expected one ${suffix} package; found ${files.length}`);
  return files[0];
}

function filesWithSuffix(directory, suffix) {
  return regularFiles(directory).filter((path) => path.toLowerCase().endsWith(suffix));
}

function regularFiles(directory) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory() && !entry.isSymbolicLink()) result.push(...regularFiles(path));
    else result.push(path);
  }
  return result.sort();
}

function run(command, args) {
  const result = spawnSync(command, args, { stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`);
}

function capture(command, args, options = {}) {
  const result = spawnSync(command, args, { ...options, encoding: "utf8", stdio: "pipe" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}: ${result.stderr.trim()}`);
  return result.stdout;
}

function synchronousPause(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function main() {
  const report = {
    darwin: exerciseMacInstall,
    linux: exerciseDebianInstall,
    win32: exerciseNsisInstall,
  }[process.platform]?.();
  if (!report) throw new Error(`Native installation exercise isn't implemented for ${process.platform}`);
  console.log(JSON.stringify(report));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
