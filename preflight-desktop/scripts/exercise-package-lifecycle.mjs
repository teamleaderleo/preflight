import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
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
import { basename, dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { verifyInstalledEngine } from "./verify-installed-engine.mjs";
import { privilegedCommand } from "./privileged-command.mjs";

/**
 * Install, upgrade, roll back, and remove a real package.
 *
 * The single-package exercise beside this one proves a package installs and removes cleanly. It
 * says nothing about the second install a player performs, which is the one that can lose their
 * data: an upgrade writes over a tree somebody is already using, and a rollback puts the old tree
 * back. macOS rehearsed that by hand; Windows and Linux never have.
 *
 * Three properties are checked, and each one is a way this has broken elsewhere. The version has to
 * actually move, or the run silently proves nothing. Rolling back has to restore the earlier tree
 * byte for byte, because "mostly the old version" is a state nobody can reason about afterwards.
 * And separately owned data -- caches, saves, mods, game files -- has to survive every step,
 * including the removal at the end.
 */
export function exercisePackageLifecycle(olderDirectory, newerDirectory, platform = process.platform) {
  const exercise = {
    darwin: exerciseMacLifecycle,
    linux: exerciseDebianLifecycle,
    win32: exerciseNsisLifecycle,
  }[platform];
  if (!exercise) throw new Error(`Package lifecycle exercise isn't implemented for ${platform}`);
  if (platform !== process.platform) {
    throw new Error(`Package lifecycle exercise for ${platform} can't run on ${process.platform}`);
  }
  return exercise(resolve(olderDirectory), resolve(newerDirectory));
}

function exerciseDebianLifecycle(olderDirectory, newerDirectory) {
  const older = onlyFile(olderDirectory, ".deb");
  const newer = onlyFile(newerDirectory, ".deb");
  const newerEngineDigest = debianPackageEngineDigest(newer);
  const packageName = capture("dpkg-deb", ["--field", older, "Package"]).trim();
  if (!packageName) throw new Error("The older Debian package declares no package name");
  if (capture("dpkg-deb", ["--field", newer, "Package"]).trim() !== packageName) {
    throw new Error("The two Debian packages declare different package names");
  }
  if (spawnSync("dpkg-query", ["--show", packageName]).status === 0) {
    throw new Error(`Runner already has ${packageName} installed`);
  }
  const data = separatelyOwnedData();
  let installed = true;
  try {
    // dpkg permits a downgrade but asks first; the rollback step is the point of this exercise, so
    // it is requested explicitly rather than left to the tool's default question.
    const install = (path) => runPrivileged("dpkg", ["--force-downgrade", "--install", path]);
    install(older);
    const first = debianInstalledRoot(packageName);
    const firstVersion = installedVersion(first);
    const firstDigest = treeDigest(first);
    data.assertUnchanged("the first install");

    install(newer);
    const upgraded = debianInstalledRoot(packageName);
    const upgradedVersion = installedVersion(upgraded);
    assertVersionMoved(firstVersion, upgradedVersion);
    assertEngineMatchesPackage(upgraded, newerEngineDigest);
    data.assertUnchanged("the upgrade");

    install(older);
    const rolledBack = debianInstalledRoot(packageName);
    assertRolledBack(firstVersion, installedVersion(rolledBack), firstDigest, treeDigest(rolledBack));
    data.assertUnchanged("the rollback");

    const ownedFiles = debianOwnedFiles(packageName).filter(isExistingFile);
    runPrivileged("dpkg", ["--remove", packageName]);
    installed = false;
    assertRemoved(ownedFiles);
    const status = spawnSync("dpkg-query", ["--show", "--showformat=${db:Status-Abbrev}", packageName], {
      encoding: "utf8",
      stdio: "pipe",
    });
    if (status.status === 0 && status.stdout.startsWith("ii")) {
      throw new Error(`${packageName} remained installed after removal`);
    }
    data.assertUnchanged("the removal");
    return report(basename(older), basename(newer), firstVersion, upgradedVersion, ownedFiles.length);
  } finally {
    if (installed) spawnPrivileged("dpkg", ["--purge", packageName], { stdio: "ignore" });
    data.discard();
  }
}

function exerciseNsisLifecycle(olderDirectory, newerDirectory) {
  const older = onlyFile(olderDirectory, "-setup.exe");
  const newer = onlyFile(newerDirectory, "-setup.exe");
  const newerEngineDigest = windowsPackageEngineDigest(newer);
  const installDirectory = join(tmpdir(), `preflight-lifecycle-${process.pid}`);
  if (existsSync(installDirectory)) throw new Error(`Lifecycle directory already exists: ${installDirectory}`);
  const data = separatelyOwnedData();
  try {
    const install = (path) => run(path, ["/S", `/D=${installDirectory}`]);
    install(older);
    const firstVersion = installedVersion(installDirectory);
    const firstDigest = treeDigest(installDirectory);
    data.assertUnchanged("the first install");

    install(newer);
    const upgradedVersion = installedVersion(installDirectory);
    assertVersionMoved(firstVersion, upgradedVersion);
    assertEngineMatchesPackage(installDirectory, newerEngineDigest);
    data.assertUnchanged("the upgrade");

    install(older);
    assertRolledBack(
      firstVersion,
      installedVersion(installDirectory),
      firstDigest,
      treeDigest(installDirectory),
    );
    data.assertUnchanged("the rollback");

    const ownedFiles = regularFiles(installDirectory);
    const uninstallers = ownedFiles.filter((path) => path.toLowerCase().endsWith("uninstall.exe"));
    if (uninstallers.length !== 1) {
      throw new Error(`Installed package must contain one uninstaller; found ${uninstallers.length}`);
    }
    run(uninstallers[0], ["/S"]);
    // The NSIS uninstaller returns before Windows has finished releasing the files it removes, so
    // the check is retried rather than read once.
    assertRemoved(ownedFiles, 20);
    data.assertUnchanged("the removal");
    return report(basename(older), basename(newer), firstVersion, upgradedVersion, ownedFiles.length);
  } finally {
    if (existsSync(installDirectory)) rmSync(installDirectory, { recursive: true, force: true });
    data.discard();
  }
}

function exerciseMacLifecycle(olderDirectory, newerDirectory) {
  const older = onlyFile(olderDirectory, ".dmg");
  const newer = onlyFile(newerDirectory, ".dmg");
  const newerEngineDigest = macPackageEngineDigest(newer);
  const installDirectory = mkdtempSync(join(tmpdir(), "preflight-lifecycle-"));
  const installedApp = join(installDirectory, "Preflight.app");
  const data = separatelyOwnedData();
  try {
    const install = (path) => {
      const mountDirectory = mkdtempSync(join(tmpdir(), "preflight-lifecycle-dmg-"));
      try {
        run("hdiutil", ["attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mountDirectory, path]);
        try {
          const packagedApp = join(mountDirectory, "Preflight.app");
          if (!lstatSync(packagedApp, { throwIfNoEntry: false })?.isDirectory()) {
            throw new Error(`Mounted DMG doesn't contain Preflight.app: ${path}`);
          }
          rmSync(installedApp, { recursive: true, force: true });
          run("ditto", [packagedApp, installedApp]);
        } finally {
          run("hdiutil", ["detach", mountDirectory]);
        }
      } finally {
        rmSync(mountDirectory, { recursive: true, force: true });
      }
    };
    install(older);
    const firstVersion = installedVersion(installedApp);
    const firstDigest = treeDigest(installedApp);
    data.assertUnchanged("the first install");

    install(newer);
    const upgradedVersion = installedVersion(installedApp);
    assertVersionMoved(firstVersion, upgradedVersion);
    assertEngineMatchesPackage(installedApp, newerEngineDigest);
    data.assertUnchanged("the upgrade");

    install(older);
    assertRolledBack(firstVersion, installedVersion(installedApp), firstDigest, treeDigest(installedApp));
    data.assertUnchanged("the rollback");

    const ownedFiles = regularFiles(installedApp);
    rmSync(installedApp, { recursive: true });
    assertRemoved(ownedFiles);
    data.assertUnchanged("the removal");
    return report(basename(older), basename(newer), firstVersion, upgradedVersion, ownedFiles.length);
  } finally {
    rmSync(installDirectory, { recursive: true, force: true });
    data.discard();
  }
}

export function assertVersionMoved(before, after) {
  if (before === after) {
    throw new Error(
      `Upgrade installed the same version as the first install (${before}), so nothing was upgraded`,
    );
  }
}

export function assertRolledBack(firstVersion, rolledBackVersion, firstDigest, rolledBackDigest) {
  if (rolledBackVersion !== firstVersion) {
    throw new Error(
      `Rollback left version ${rolledBackVersion} where the first install was ${firstVersion}`,
    );
  }
  if (rolledBackDigest !== firstDigest) {
    throw new Error("Rollback restored the earlier version's files with different contents");
  }
}

export function assertRemoved(ownedFiles, attempts = 1) {
  let remaining = ownedFiles.filter(isExistingFile);
  for (let attempt = 1; attempt < attempts && remaining.length; attempt += 1) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 100);
    remaining = remaining.filter(isExistingFile);
  }
  if (remaining.length) {
    throw new Error(
      `Removal left ${remaining.length} owned file(s): ${remaining.slice(0, 5).join(", ")}`,
    );
  }
}

/**
 * A digest of every regular file under a tree, path included.
 *
 * Comparing sizes or a file count would pass a rollback that restored the right shape with the
 * wrong bytes, which is the failure worth catching: an installer that leaves one file from the
 * newer version behind produces exactly that.
 */
export function treeDigest(root) {
  const base = resolve(root);
  const hash = createHash("sha256");
  for (const path of regularFiles(base)) {
    hash.update(relative(base, path).split("\\").join("/"));
    hash.update("\0");
    hash.update(readFileSync(path));
    hash.update("\0");
  }
  return hash.digest("hex");
}

/**
 * The version the installed package carries, read from the engine it shipped.
 *
 * Each platform records a version somewhere of its own -- Debian's database, a Windows file
 * resource, an Info.plist -- and reading three different sources would leave three ways for the
 * comparison to be wrong. The engine manifest is written by the build from the project version and
 * is already verified as part of the package payload, so it is the same answer everywhere.
 */
export function installedVersion(root) {
  const manifest = join(engineDirectory(root), "bundle.json");
  const version = JSON.parse(readFileSync(manifest, "utf8")).sourceVersion;
  if (typeof version !== "string" || !version.trim()) {
    throw new Error(`Installed engine manifest carries no source version: ${manifest}`);
  }
  return version;
}

export function assertEngineMatchesPackage(installedRoot, expectedDigest) {
  const actualDigest = treeDigest(engineDirectory(installedRoot));
  if (actualDigest !== expectedDigest) {
    throw new Error("Installed engine differs from the exact candidate package");
  }
  verifyInstalledEngine(installedRoot, { verifyReviewedSources: false });
}

/**
 * Files the package must never touch, checked after every step.
 *
 * Preparation caches, saves, mods and game files all live outside the package, and an upgrade or an
 * uninstaller that reaches into them is the one defect a player cannot recover from. The fixtures
 * sit in a disposable home so a failure here damages nothing real.
 */
function separatelyOwnedData() {
  const home = mkdtempSync(join(tmpdir(), "preflight-lifecycle-home-"));
  const fixtures = new Map();
  for (const [parts, contents] of [
    [[".starsector-preflight", "cache", "profile", "artifact.bin"], "owned cache\n"],
    [[".starsector-preflight", "runs", "run-1", "evidence.json"], "owned evidence\n"],
    [["Starsector", "starsector-core", "data", "config", "settings.json"], "game settings\n"],
    [["Starsector", "mods", "example", "mod_info.json"], "mod metadata\n"],
    [[".starsector", "saves", "example", "campaign.xml"], "save data\n"],
  ]) {
    const path = join(home, ...parts);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, contents, { mode: 0o600 });
    fixtures.set(path, contents);
  }
  return {
    home,
    assertUnchanged(step) {
      for (const [path, contents] of fixtures) {
        if (!existsSync(path) || readFileSync(path, "utf8") !== contents) {
          throw new Error(`${step} changed separately owned data: ${path}`);
        }
      }
    },
    discard() {
      rmSync(home, { recursive: true, force: true });
    },
  };
}

function debianOwnedFiles(packageName) {
  return capture("dpkg-query", ["--listfiles", packageName]).trim().split(/\r?\n/)
    .filter((path) => path && !lstatSync(path, { throwIfNoEntry: false })?.isDirectory());
}

function debianInstalledRoot(packageName) {
  const manifests = debianOwnedFiles(packageName)
    .filter((path) => /[/\\]engine[/\\]bundle\.json$/.test(path));
  if (manifests.length !== 1) {
    throw new Error(`Installed Debian package must own one engine manifest; found ${manifests.length}`);
  }
  return dirname(dirname(manifests[0]));
}

function engineDirectory(root) {
  const manifests = regularFiles(root).filter((path) => basename(path) === "bundle.json"
    && basename(dirname(path)).toLowerCase() === "engine");
  if (manifests.length !== 1) {
    throw new Error(`Installed package must contain one engine manifest; found ${manifests.length}`);
  }
  return dirname(manifests[0]);
}

function debianPackageEngineDigest(path) {
  return extractedPackageEngineDigest("preflight-lifecycle-deb-", (directory) => {
    run("dpkg-deb", ["--extract", path, directory]);
  });
}

function windowsPackageEngineDigest(path) {
  return extractedPackageEngineDigest("preflight-lifecycle-nsis-", (directory) => {
    run("7z", ["x", "-y", `-o${directory}`, path]);
  });
}

function macPackageEngineDigest(path) {
  const mountDirectory = mkdtempSync(join(tmpdir(), "preflight-lifecycle-reference-dmg-"));
  let mounted = false;
  try {
    run("hdiutil", ["attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mountDirectory, path]);
    mounted = true;
    return treeDigest(engineDirectory(join(mountDirectory, "Preflight.app")));
  } finally {
    if (mounted) run("hdiutil", ["detach", mountDirectory]);
    rmSync(mountDirectory, { recursive: true, force: true });
  }
}

function extractedPackageEngineDigest(prefix, extract) {
  const directory = mkdtempSync(join(tmpdir(), prefix));
  try {
    extract(directory);
    return treeDigest(engineDirectory(directory));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function report(olderPackage, newerPackage, firstVersion, upgradedVersion, ownedFiles) {
  return {
    olderPackage,
    newerPackage,
    installedVersion: firstVersion,
    upgradedVersion,
    upgradedEngineByteIdentical: true,
    rolledBackByteIdentical: true,
    removedOwnedFiles: ownedFiles,
    separatelyOwnedDataRetained: true,
  };
}

function isExistingFile(path) {
  return existsSync(path);
}

function onlyFile(directory, suffix) {
  const files = regularFiles(directory).filter((path) => path.toLowerCase().endsWith(suffix));
  if (files.length !== 1) throw new Error(`Expected one ${suffix} package in ${directory}; found ${files.length}`);
  return files[0];
}

function regularFiles(directory) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory() && !entry.isSymbolicLink()) result.push(...regularFiles(path));
    else if (entry.isFile() && !entry.isSymbolicLink()) result.push(path);
  }
  return result.sort();
}

function run(command, args) {
  const result = spawnSync(command, args, { stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`);
}

function runPrivileged(command, args) {
  const selected = privilegedCommand(command, args);
  run(selected.command, selected.args);
}

function spawnPrivileged(command, args, options) {
  const selected = privilegedCommand(command, args);
  return spawnSync(selected.command, selected.args, options);
}

function capture(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", stdio: "pipe" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}: ${result.stderr.trim()}`);
  return result.stdout;
}

function main() {
  const [, , olderDirectory, newerDirectory, reportPath] = process.argv;
  if (!olderDirectory || !newerDirectory) {
    throw new Error(
      "Usage: node exercise-package-lifecycle.mjs <older-bundle-directory> <newer-bundle-directory> [report.json]",
    );
  }
  const json = `${JSON.stringify(exercisePackageLifecycle(olderDirectory, newerDirectory), null, 2)}\n`;
  // Written here rather than piped, so the caller never needs a shell. The Windows runner's bash is
  // MSYS, and handing it the runner's own backslash paths is a way to lose a two-build run to
  // path translation rather than to anything the rehearsal was meant to find.
  if (reportPath) {
    mkdirSync(dirname(resolve(reportPath)), { recursive: true });
    writeFileSync(resolve(reportPath), json);
  }
  process.stdout.write(json);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
