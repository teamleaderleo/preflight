import {
  chmodSync,
  lstatSync,
  mkdtempSync,
  readFileSync,
  readlinkSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { verifyEngineBoundary } from "./engine-boundary.mjs";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const bundleDirectory = join(desktopDirectory, "src-tauri", "target", "release", "bundle");
const appImageRuntimeChanges = ["lib/jexec", "lib/jspawnhelper", "lib/server/libjvm.so"];

export function verifyMacPackage(directory = bundleDirectory) {
  if (process.platform !== "darwin") throw new Error("macOS package verification requires macOS");
  const dmgFiles = filesWithSuffix(directory, ".dmg");
  if (dmgFiles.length !== 1) throw new Error(`Expected one DMG, found ${dmgFiles.length}`);

  const mountDirectory = mkdtempSync(join(tmpdir(), "preflight-dmg-"));
  let mounted = false;
  try {
    run("hdiutil", ["attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mountDirectory, dmgFiles[0]]);
    mounted = true;
    assertEntries(mountDirectory, [".VolumeIcon.icns", "Applications", "Preflight.app"], [".DS_Store"]);
    const applications = lstatSync(join(mountDirectory, "Applications"));
    if (!applications.isSymbolicLink() || readlinkSync(join(mountDirectory, "Applications")) !== "/Applications") {
      throw new Error("DMG Applications entry isn't the standard /Applications link");
    }
    const appDirectory = join(mountDirectory, "Preflight.app");
    const signatureRequired = readBooleanEnvironment("PREFLIGHT_REQUIRE_PLATFORM_SIGNATURE", false);
    const updateRelease = readBooleanEnvironment("PREFLIGHT_UPDATE_RELEASE", false);
    const platformSignature = verifyMacApp(appDirectory, signatureRequired);

    const updaterArchives = filesWithSuffix(directory, ".app.tar.gz");
    const updaterSignatures = filesWithSuffix(directory, ".app.tar.gz.sig");
    const expectedUpdaterCount = updateRelease ? 1 : 0;
    if (updaterArchives.length !== expectedUpdaterCount || updaterSignatures.length !== expectedUpdaterCount) {
      throw new Error(
        `macOS updater artifact set differs: expected ${expectedUpdaterCount} archive/signature pair; ` +
        `found ${updaterArchives.length} archive(s) and ${updaterSignatures.length} signature(s)`,
      );
    }
    if (updateRelease) {
      const archiveEntries = run("tar", ["-tzf", updaterArchives[0]], true).trim().split("\n").filter(Boolean);
      if (archiveEntries.length === 0) throw new Error("macOS updater archive is empty");
      for (const entry of archiveEntries) {
        const normalized = entry.replace(/\/$/, "");
        if (!normalized || normalized.includes("\\") || normalized.startsWith("/") ||
            normalized.split("/").some((part) => part === "" || part === "." || part === "..") ||
            normalized.split("/")[0] !== "Preflight.app") {
          throw new Error(`Unsafe macOS updater archive path: ${entry}`);
        }
      }
      const extractDirectory = mkdtempSync(join(tmpdir(), "preflight-updater-"));
      try {
        run("tar", ["-xzf", updaterArchives[0], "-C", extractDirectory]);
        assertTreesEqual(join(extractDirectory, "Preflight.app"), appDirectory);
      } finally {
        rmSync(extractDirectory, { recursive: true, force: true });
      }
    }
    const installedDirectory = mkdtempSync(join(tmpdir(), "preflight-installed-app-"));
    const installedApp = join(installedDirectory, "Preflight.app");
    let installedEngine;
    let nativeHostBoot;
    try {
      run("ditto", [appDirectory, installedApp]);
      assertTreesEqual(appDirectory, installedApp);
      installedEngine = verifyPackagedEngine(join(installedApp, "Contents", "Resources", "engine"));
      nativeHostBoot = verifyNativeHostBoot(
        join(installedApp, "Contents", "MacOS", "starsector-preflight-desktop"),
      );
    } finally {
      rmSync(installedDirectory, { recursive: true, force: true });
    }
    return {
      dmg: basename(dmgFiles[0]),
      updaterArchive: updateRelease,
      platformSignature,
      installedCopy: true,
      nativeHostBoot,
      engine: installedEngine,
    };
  } finally {
    if (mounted) run("hdiutil", ["detach", mountDirectory]);
    rmSync(mountDirectory, { recursive: true, force: true });
  }
}

export function verifyLinuxPackages(directory = bundleDirectory) {
  if (process.platform !== "linux") throw new Error("Linux package verification requires Linux");
  const debFiles = filesWithSuffix(directory, ".deb");
  const appImageFiles = filesWithSuffix(directory, ".appimage");
  if (debFiles.length !== 1 || appImageFiles.length !== 1) {
    throw new Error(`Expected one Debian package and one AppImage; found ${debFiles.length} and ${appImageFiles.length}`);
  }
  const reports = [];
  const debDirectory = mkdtempSync(join(tmpdir(), "preflight-deb-"));
  try {
    run("dpkg-deb", ["--extract", debFiles[0], debDirectory]);
    reports.push({ package: basename(debFiles[0]), ...verifyExtractedPayload(debDirectory) });
  } finally {
    rmSync(debDirectory, { recursive: true, force: true });
  }

  const appImageDirectory = mkdtempSync(join(tmpdir(), "preflight-appimage-"));
  try {
    chmodSync(appImageFiles[0], statSync(appImageFiles[0]).mode | 0o100);
    const extraction = spawnSync(realPath(appImageFiles[0]), ["--appimage-extract"], {
      cwd: appImageDirectory,
      encoding: "utf8",
      stdio: "pipe",
    });
    if (extraction.error) throw extraction.error;
    if (extraction.status !== 0) {
      throw new Error(`AppImage extraction failed: ${(extraction.stderr ?? "").trim()}`);
    }
    reports.push({
      package: basename(appImageFiles[0]),
      ...verifyExtractedPayload(join(appImageDirectory, "squashfs-root"), {
        allowedRuntimeChanges: appImageRuntimeChanges,
        expectedRuntimeRpaths: Object.fromEntries(appImageRuntimeChanges.map((path) => [path, "$ORIGIN"])),
      }),
    });
  } finally {
    rmSync(appImageDirectory, { recursive: true, force: true });
  }
  return reports;
}

export function verifyWindowsPackage(directory = bundleDirectory) {
  if (process.platform !== "win32") throw new Error("Windows package verification requires Windows");
  const installers = filesWithSuffix(directory, ".exe");
  if (installers.length !== 1) throw new Error(`Expected one Windows installer, found ${installers.length}`);
  const extractDirectory = mkdtempSync(join(tmpdir(), "preflight-nsis-"));
  try {
    run("7z", ["x", "-y", `-o${extractDirectory}`, installers[0]], true);
    const payload = verifyExtractedPayload(extractDirectory);
    const signatureRequired = readBooleanEnvironment("PREFLIGHT_REQUIRE_PLATFORM_SIGNATURE", false);
    const signature = spawnSync(
      "powershell",
      ["-NoProfile", "-NonInteractive", "-Command", `(Get-AuthenticodeSignature -LiteralPath '${powershellLiteral(installers[0])}').Status`],
      { encoding: "utf8", stdio: "pipe" },
    );
    if (signature.error) throw signature.error;
    const signatureStatus = (signature.stdout ?? "").trim();
    if (signatureRequired && (signature.status !== 0 || signatureStatus !== "Valid")) {
      throw new Error(`Tagged Windows installer isn't Authenticode-signed: ${signatureStatus || signature.stderr?.trim()}`);
    }
    return {
      package: basename(installers[0]),
      platformSignature: signatureStatus === "Valid" ? "authenticode" : "unsigned-or-untrusted",
      ...payload,
    };
  } finally {
    rmSync(extractDirectory, { recursive: true, force: true });
  }
}

export function verifyExtractedPayload(root, engineOptions = {}) {
  const entries = packageEntries(root);
  const engineDirectories = entries.filter((entry) => {
    if (!entry.details.isDirectory() || basename(entry.path).toLowerCase() !== "engine") return false;
    const names = new Set(readdirSync(entry.path));
    return ["bundle.json", "legal", "preflight.jar", "runtime", "scenarios"].every((name) => names.has(name));
  });
  if (engineDirectories.length !== 1) {
    throw new Error(`Extracted package must contain exactly one bounded engine; found ${engineDirectories.length}`);
  }
  const expectedFontLicenses = ["B612-OFL.txt", "IBM-Plex-Sans-OFL.txt", "Orbitron-OFL.txt"];
  const fontLicenses = entries.filter(
    (entry) => entry.details.isFile() && expectedFontLicenses.includes(basename(entry.path)),
  );
  const packagedFontLicenseNames = fontLicenses.map((entry) => basename(entry.path)).sort();
  if (JSON.stringify(packagedFontLicenseNames) !== JSON.stringify(expectedFontLicenses)) {
    throw new Error(`Extracted package font licenses differ: ${packagedFontLicenseNames.join(", ")}`);
  }
  for (const fontLicense of fontLicenses) {
    assertSameFile(
      fontLicense.path,
      join(desktopDirectory, "src-tauri", "licenses", basename(fontLicense.path)),
    );
  }
  const engine = verifyPackagedEngine(engineDirectories[0].path, engineOptions);
  return { extractedEntries: entries.length, engine };
}

export function assertTreesEqual(expected, actual) {
  const expectedDetails = lstatSync(expected, { throwIfNoEntry: false });
  const actualDetails = lstatSync(actual, { throwIfNoEntry: false });
  if (!expectedDetails || !actualDetails) throw new Error(`Package tree entry is missing: ${expected} or ${actual}`);
  if (expectedDetails.isSymbolicLink() || actualDetails.isSymbolicLink()) {
    if (!expectedDetails.isSymbolicLink() || !actualDetails.isSymbolicLink() ||
        readlinkSync(expected) !== readlinkSync(actual)) {
      throw new Error(`Package tree links differ: ${expected}, ${actual}`);
    }
    return;
  }
  if (expectedDetails.isDirectory() || actualDetails.isDirectory()) {
    if (!expectedDetails.isDirectory() || !actualDetails.isDirectory()) {
      throw new Error(`Package tree entry types differ: ${expected}, ${actual}`);
    }
    const expectedNames = readdirSync(expected).sort();
    const actualNames = readdirSync(actual).sort();
    if (JSON.stringify(expectedNames) !== JSON.stringify(actualNames)) {
      throw new Error(`Package tree entries differ: ${expected}, ${actual}`);
    }
    for (const name of expectedNames) assertTreesEqual(join(expected, name), join(actual, name));
    return;
  }
  if (!expectedDetails.isFile() || !actualDetails.isFile() ||
      !readFileSync(expected).equals(readFileSync(actual))) {
    throw new Error(`Package tree files differ: ${expected}, ${actual}`);
  }
}

export function classifyMacSignature(verifyStatus, details) {
  if (verifyStatus !== 0) return "unsigned-or-invalid";
  if (/^Authority=Developer ID Application:/m.test(details)) return "developer-id";
  if (/^Signature=adhoc$/m.test(details)) return "ad-hoc";
  return "unidentified";
}

function verifyMacApp(appDirectory, signatureRequired) {
  assertExactEntries(appDirectory, ["Contents"]);
  const contents = join(appDirectory, "Contents");
  const contentEntries = readdirSync(contents).sort();
  const required = ["Info.plist", "MacOS", "Resources"];
  const allowed = new Set([...required, "_CodeSignature"]);
  for (const name of required) {
    if (!contentEntries.includes(name)) throw new Error(`macOS app is missing ${name}`);
  }
  const extra = contentEntries.filter((name) => !allowed.has(name));
  if (extra.length) throw new Error(`Unexpected macOS app content: ${extra.join(", ")}`);
  assertExactEntries(join(contents, "MacOS"), ["starsector-preflight-desktop"]);
  assertExactEntries(join(contents, "Resources"), ["engine", "icon.icns", "licenses"]);
  assertExactEntries(join(contents, "Resources", "licenses"), [
    "B612-OFL.txt",
    "IBM-Plex-Sans-OFL.txt",
    "Orbitron-OFL.txt",
  ]);
  assertSameFile(join(contents, "Resources", "icon.icns"), join(desktopDirectory, "src-tauri", "icons", "icon.icns"));
  assertSameFile(
    join(contents, "Resources", "licenses", "B612-OFL.txt"),
    join(desktopDirectory, "src-tauri", "licenses", "B612-OFL.txt"),
  );
  assertSameFile(
    join(contents, "Resources", "licenses", "IBM-Plex-Sans-OFL.txt"),
    join(desktopDirectory, "src-tauri", "licenses", "IBM-Plex-Sans-OFL.txt"),
  );
  assertSameFile(
    join(contents, "Resources", "licenses", "Orbitron-OFL.txt"),
    join(desktopDirectory, "src-tauri", "licenses", "Orbitron-OFL.txt"),
  );
  const executable = join(contents, "MacOS", "starsector-preflight-desktop");
  if (!statSync(executable).isFile() || (statSync(executable).mode & 0o111) === 0) {
    throw new Error("macOS app launcher isn't an executable file");
  }
  const plist = JSON.parse(run("plutil", ["-convert", "json", "-o", "-", join(contents, "Info.plist")], true));
  const expectedPlist = {
    CFBundleDisplayName: "Preflight",
    CFBundleExecutable: "starsector-preflight-desktop",
    CFBundleIdentifier: "io.github.teamleaderleo.preflight",
    CFBundleName: "Preflight",
    CFBundlePackageType: "APPL",
    NSAppleEventsUsageDescription:
      "Preflight uses System Events only during an automated game test that you review and start.",
  };
  for (const [key, value] of Object.entries(expectedPlist)) {
    if (plist[key] !== value) throw new Error(`Unexpected macOS app ${key}: ${plist[key]}`);
  }
  const signature = spawnSync("codesign", ["--verify", "--deep", "--strict", appDirectory], {
    encoding: "utf8",
    stdio: "pipe",
  });
  if (signature.error) throw signature.error;
  const details = spawnSync("codesign", ["-dv", "--verbose=4", appDirectory], {
    encoding: "utf8",
    stdio: "pipe",
  });
  if (details.error) throw details.error;
  const platformSignature = classifyMacSignature(
    signature.status,
    `${details.stdout ?? ""}\n${details.stderr ?? ""}`,
  );
  if (signatureRequired && platformSignature !== "developer-id") {
    throw new Error(`Tagged macOS package isn't Developer ID-signed: ${platformSignature}`);
  }
  return platformSignature;
}

function verifyPackagedEngine(engineDirectory, options = {}) {
  const boundary = verifyEngineBoundary(engineDirectory, options);
  for (const [relativePath, expectedRpath] of Object.entries(options.expectedRuntimeRpaths ?? {})) {
    const path = join(engineDirectory, "runtime", ...relativePath.split("/"));
    const bytes = readFileSync(path);
    if (bytes.length < 4 || !bytes.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46]))) {
      throw new Error(`AppImage runtime change isn't an ELF file: ${relativePath}`);
    }
    const actualRpath = run("patchelf", ["--print-rpath", path], true).trim();
    if (actualRpath !== expectedRpath) {
      throw new Error(`AppImage runtime RPATH differs for ${relativePath}: ${actualRpath}`);
    }
  }
  const java = join(engineDirectory, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
  const result = spawnSync(java, ["-jar", join(engineDirectory, "preflight.jar"), "--help"], {
    cwd: engineDirectory,
    encoding: "utf8",
    stdio: "pipe",
  });
  if (result.error) throw result.error;
  if (result.status !== 0 || !(result.stdout ?? "").includes("Commands:")) {
    throw new Error(`Extracted engine smoke test failed: ${(result.stderr ?? "").trim()}`);
  }
  return { ...boundary, smoke: "passed" };
}

export function verifyNativeHostBoot(executable) {
  const result = spawnSync(executable, [], {
    encoding: "utf8",
    env: { ...process.env, PREFLIGHT_DESKTOP_BOOT_SMOKE: "1" },
    stdio: "pipe",
    timeout: 15_000,
  });
  if (result.error) throw result.error;
  if (result.status !== 0 || !(result.stdout ?? "").includes("PREFLIGHT_DESKTOP_BOOT_SMOKE_OK")) {
    throw new Error(
      `Native desktop host smoke test failed with ${result.status}: ` +
      `${(result.stderr ?? "").trim() || "success marker missing"}`,
    );
  }
  return "passed";
}

function filesWithSuffix(directory, suffix) {
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...filesWithSuffix(path, suffix));
    else if (entry.isFile() && entry.name.toLowerCase().endsWith(suffix)) result.push(path);
  }
  return result.sort();
}

function packageEntries(root) {
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    const details = lstatSync(path);
    rejectPackagePath(path, root);
    result.push({ path, details });
    if (details.isDirectory() && !details.isSymbolicLink()) result.push(...packageEntries(path));
  }
  return result;
}

function rejectPackagePath(path, root) {
  const relative = path.slice(root.length).replaceAll("\\", "/").replace(/^\/+/, "").toLowerCase();
  const segments = relative.split("/");
  const forbiddenSegments = new Set(["activation", "mods", "saves", "screenshots", "starsector.app", "starsector-core"]);
  const forbiddenFiles = new Set(["campaign.xml", "fs.common_obf.jar", "starfarer.api.jar", "starfarer_obf.jar", "starsector.exe", "starsector.log"]);
  const forbidden = segments.find((segment) => forbiddenSegments.has(segment));
  if (forbidden) throw new Error(`Forbidden extracted-package path segment: ${forbidden}`);
  if (forbiddenFiles.has(segments.at(-1))) throw new Error(`Forbidden extracted-package file: ${relative}`);
}

function assertExactEntries(directory, expected) {
  const actual = readdirSync(directory).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(`Package entries differ under ${directory}: expected ${wanted}; got ${actual}`);
  }
}

function assertEntries(directory, required, optional) {
  const actual = readdirSync(directory).sort();
  const allowed = new Set([...required, ...optional]);
  const missing = required.filter((name) => !actual.includes(name));
  const extra = actual.filter((name) => !allowed.has(name));
  if (missing.length || extra.length) {
    throw new Error(
      `Package entries differ under ${directory}: missing ${missing.join(", ") || "none"}; ` +
      `unexpected ${extra.join(", ") || "none"}`,
    );
  }
}

function assertSameFile(actual, expected) {
  if (!readFileSync(actual).equals(readFileSync(expected))) {
    throw new Error(`Packaged file differs from reviewed source: ${actual}`);
  }
}

function run(command, args, capture = false) {
  const result = spawnSync(command, args, { encoding: "utf8", stdio: capture ? "pipe" : "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} exited with ${result.status}: ${(result.stderr ?? "").trim()}`);
  }
  return result.stdout ?? "";
}

function readBooleanEnvironment(name, fallback) {
  const value = process.env[name];
  if (value === undefined) return fallback;
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`${name} must be true or false`);
}

function realPath(path) {
  return resolve(path);
}

function powershellLiteral(value) {
  return value.replaceAll("'", "''");
}

function main() {
  const report = {
    darwin: () => verifyMacPackage(),
    linux: () => verifyLinuxPackages(),
    win32: () => verifyWindowsPackage(),
  }[process.platform]?.();
  if (!report) throw new Error(`Native package verification isn't implemented for ${process.platform}`);
  console.log(JSON.stringify(report));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
