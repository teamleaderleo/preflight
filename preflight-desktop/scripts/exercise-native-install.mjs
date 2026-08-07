import { spawnSync } from "node:child_process";
import { existsSync, lstatSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { verifyInstalledEngine } from "./verify-installed-engine.mjs";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const bundleDirectory = join(desktopDirectory, "src-tauri", "target", "release", "bundle");

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
    return { package: basename(packagePath), packageName, removed: true, engine: report.engine };
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
    return { package: basename(packagePath), removed: true, engine: report.engine };
  } finally {
    if (existsSync(installDirectory)) rmSync(installDirectory, { recursive: true, force: true });
  }
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

function capture(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", stdio: "pipe" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}: ${result.stderr.trim()}`);
  return result.stdout;
}

function synchronousPause(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function main() {
  const report = { linux: exerciseDebianInstall, win32: exerciseNsisInstall }[process.platform]?.();
  if (!report) throw new Error(`Native installation exercise isn't implemented for ${process.platform}`);
  console.log(JSON.stringify(report));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
