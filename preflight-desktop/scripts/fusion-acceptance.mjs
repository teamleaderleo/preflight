import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { arch, platform, release } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  verifyLinuxPackages,
  verifyWindowsPackage,
} from "./verify-native-package.mjs";
import {
  exerciseDebianInstall,
  exerciseNsisInstall,
} from "./exercise-native-install.mjs";

const scriptsDirectory = dirname(fileURLToPath(import.meta.url));
const desktopDirectory = resolve(scriptsDirectory, "..");
const repositoryRoot = resolve(desktopDirectory, "..");
const ACCEPTANCE_MODES = new Set([
  "windows-x64-package-under-arm64-emulation",
  "windows-x64-package-native-architecture",
  "linux-arm64-portable-source-contracts",
  "linux-x64-portable-source-contracts",
  "linux-x64-package-native-architecture",
]);

export function acceptanceMode(hostPlatform, hostArch, portableOnly = false) {
  if (hostPlatform === "win32") {
    if (portableOnly) throw new Error("Windows Fusion acceptance uses the published package");
    if (hostArch === "arm64") return "windows-x64-package-under-arm64-emulation";
    if (hostArch === "x64") return "windows-x64-package-native-architecture";
    throw new Error(`The Windows package isn't supported on ${hostArch}`);
  }
  if (hostPlatform === "linux") {
    if (hostArch === "arm64") {
      if (!portableOnly) {
        throw new Error(
          "Fusion's ARM Ubuntu guest can't validate the published x86-64 Linux package; use --portable-only",
        );
      }
      return "linux-arm64-portable-source-contracts";
    }
    if (hostArch === "x64") {
      if (portableOnly) return "linux-x64-portable-source-contracts";
      return "linux-x64-package-native-architecture";
    }
    throw new Error(`Linux acceptance isn't supported on ${hostArch}`);
  }
  throw new Error("Fusion acceptance is limited to Windows and Linux guests");
}

export function detectedHostArchitecture(
  hostPlatform = platform(),
  processArchitecture = arch(),
  environment = process.env,
  probe = probeWindowsArchitecture,
) {
  if (hostPlatform !== "win32") return processArchitecture;
  const probed = normalizeArchitecture(probe());
  if (probed) return probed;
  const emulatedHost = normalizeArchitecture(environment.PROCESSOR_ARCHITEW6432);
  if (emulatedHost) return emulatedHost;
  return normalizeArchitecture(environment.PROCESSOR_ARCHITECTURE) ?? processArchitecture;
}

export function claimsForMode(mode) {
  if (!ACCEPTANCE_MODES.has(mode)) throw new Error(`Unknown Fusion acceptance mode: ${mode}`);
  const packageEvidence = mode.includes("package");
  return {
    packageLifecycle: packageEvidence,
    portableJvmAndUiContracts: true,
    x64BehaviorUnderWindowsArmEmulation: mode === "windows-x64-package-under-arm64-emulation",
    nativeX64LinuxPackage: mode === "linux-x64-package-native-architecture",
    licensedGameIntegration: false,
    nativePerformance: false,
    gpuAudioOrDriverBehavior: false,
  };
}

export function validateAcceptanceEvidence(evidence) {
  const allowedStatuses = new Set(["passed", "failed", "skipped"]);
  const expectedClaims = ACCEPTANCE_MODES.has(evidence?.mode) ? claimsForMode(evidence.mode) : null;
  if (evidence?.format !== "preflight-fusion-acceptance-v1"
      || evidence?.hypervisor !== "vmware-fusion"
      || !expectedClaims
      || !evidence?.environment || !/^[a-f0-9]{40}$/.test(evidence.environment.sourceRevision ?? "")
      || typeof evidence.environment.sourceDirty !== "boolean"
      || !Number.isSafeInteger(evidence.environment.sourceStatusBytes)
      || !/^[a-f0-9]{64}$/.test(evidence.environment.sourceStatusSha256 ?? "")
      || !evidence?.claims
      || Object.keys(expectedClaims).some((key) => evidence.claims[key] !== expectedClaims[key])
      || !Array.isArray(evidence.checks) || evidence.checks.length === 0
      || evidence.checks.some((check) => typeof check?.id !== "string" || !allowedStatuses.has(check.status))
      || !Array.isArray(evidence.deferred)
      || evidence.deferred.some((item) => typeof item?.id !== "string" || typeof item?.reason !== "string")
      || evidence.claims.licensedGameIntegration !== false
      || evidence.claims.nativePerformance !== false
      || evidence.claims.gpuAudioOrDriverBehavior !== false) {
    throw new Error(`Malformed Fusion acceptance evidence: ${JSON.stringify(evidence)}`);
  }
  return evidence;
}

export function runPackageAcceptance(packageDirectory, mode) {
  if (!mode.includes("package")) throw new Error(`Package acceptance doesn't match mode ${mode}`);
  const candidateAssets = candidateAssetIdentities(packageDirectory, platform());
  const verification = platform() === "win32"
    ? verifyWindowsPackage(packageDirectory)
    : verifyLinuxPackages(packageDirectory);
  const lifecycle = platform() === "win32"
    ? exerciseNsisInstall(packageDirectory)
    : exerciseDebianInstall(packageDirectory);
  return {
    checks: [
      { id: "package-boundary", status: "passed", detail: summarizePackageVerification(verification) },
      { id: "clean-install-and-engine", status: "passed", detail: lifecycle.package },
      { id: "synthetic-discovery-paths-and-preparation", status: "passed", detail: lifecycle.syntheticContract },
      { id: "launch-plan-without-execution", status: "passed", detail: lifecycle.syntheticContract.launchPlan },
      { id: "no-game-smoke-evidence", status: "passed", detail: lifecycle.desktopSmokeContract },
      { id: "platform-driver-probe", status: "passed", detail: lifecycle.desktopSmokeProbe },
      { id: "bounded-diagnostics-export", status: "passed", detail: lifecycle.syntheticContract.diagnostics },
      { id: "all-data-boundary", status: "passed", detail: lifecycle.allDataRemoval },
      { id: "native-uninstall", status: "passed", detail: { removed: lifecycle.removed === true } },
    ],
    package: { candidateAssets, verification, lifecycle },
  };
}

export function runPortableAcceptance(run = runCommand) {
  const maven = run("mvn", ["verify"], { cwd: repositoryRoot });
  const desktop = run("npm", ["run", "verify"], { cwd: desktopDirectory });
  return {
    checks: [
      { id: "portable-maven-reactor", status: "passed", detail: commandDetail(maven) },
      { id: "portable-desktop-contracts", status: "passed", detail: commandDetail(desktop) },
      {
        id: "published-linux-x64-package",
        status: "skipped",
        detail: "An ARM64 Ubuntu guest can't execute or install the published x86-64 package.",
      },
    ],
    portable: { maven: commandDetail(maven), desktop: commandDetail(desktop) },
  };
}

export function buildEvidence({ mode, startedAt, completedAt, result }) {
  const source = sourceIdentity();
  const hostArch = detectedHostArchitecture();
  return validateAcceptanceEvidence({
    format: "preflight-fusion-acceptance-v1",
    hypervisor: "vmware-fusion",
    mode,
    startedAt,
    completedAt,
    environment: {
      platform: platform(),
      architecture: hostArch,
      processArchitecture: arch(),
      release: release(),
      node: process.version,
      sourceRevision: source.revision,
      sourceDirty: source.dirty,
      sourceStatusBytes: source.statusBytes,
      sourceStatusSha256: source.statusSha256,
    },
    claims: claimsForMode(mode),
    checks: result.checks,
    package: result.package ?? null,
    portable: result.portable ?? null,
    deferred: [
      {
        id: "signed-update-and-rollback",
        reason: "Requires two differently versioned update-signed candidates and an operator-confirmed UI restart.",
      },
      {
        id: "licensed-game-launch-ownership",
        reason: "Requires the operator's legitimate Starsector installation; synthetic launch planning never starts it.",
      },
      {
        id: "graphics-audio-and-gameplay",
        reason: "Requires licensed content and representative native hardware or a clearly labelled emulated compatibility run.",
      },
      {
        id: "performance",
        reason: "Virtual-machine timings aren't release performance evidence.",
      },
    ],
  });
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const mode = acceptanceMode(platform(), detectedHostArchitecture(), options.portableOnly);
  const startedAt = new Date().toISOString();
  const result = options.portableOnly
    ? runPortableAcceptance()
    : runPackageAcceptance(options.packageDirectory, mode);
  const evidence = buildEvidence({ mode, startedAt, completedAt: new Date().toISOString(), result });
  writeEvidence(options.output, evidence);
  console.log(JSON.stringify({ output: options.output, mode, checks: evidence.checks.length }));
}

function parseArguments(args) {
  let packageDirectory = null;
  let output = null;
  let portableOnly = false;
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--package-dir" && index + 1 < args.length) packageDirectory = resolve(args[++index]);
    else if (args[index] === "--output" && index + 1 < args.length) output = resolve(args[++index]);
    else if (args[index] === "--portable-only") portableOnly = true;
    else throw new Error(`Unknown Fusion acceptance option: ${args[index]}`);
  }
  if (!output) throw new Error("Fusion acceptance requires --output <new-evidence.json>");
  if (!portableOnly && !packageDirectory) {
    throw new Error("Package acceptance requires --package-dir <candidate-directory>");
  }
  if (portableOnly && packageDirectory) {
    throw new Error("--package-dir isn't used by --portable-only acceptance");
  }
  return { packageDirectory, output, portableOnly };
}

function writeEvidence(output, evidence) {
  const destination = resolve(output);
  if (existsSync(destination)) throw new Error(`Refusing to replace existing evidence: ${destination}`);
  mkdirSync(dirname(destination), { recursive: true });
  const parent = lstatSync(dirname(destination));
  if (!parent.isDirectory() || parent.isSymbolicLink()) {
    throw new Error(`Evidence parent isn't a real directory: ${dirname(destination)}`);
  }
  const temporary = `${destination}.tmp-${process.pid}`;
  try {
    writeFileSync(temporary, `${JSON.stringify(evidence, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    renameSync(temporary, destination);
  } finally {
    rmSync(temporary, { force: true });
  }
}

function runCommand(command, args, options) {
  const started = Date.now();
  const result = spawnSync(command, args, { ...options, encoding: "utf8", stdio: "pipe" });
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} exited with ${result.status}: ${output.slice(-4000)}`);
  }
  return {
    command: [command, ...args],
    durationMs: Date.now() - started,
    outputSha256: createHash("sha256").update(output).digest("hex"),
    outputBytes: Buffer.byteLength(output),
  };
}

function commandDetail(result) {
  return {
    command: result.command,
    durationMs: result.durationMs,
    outputBytes: result.outputBytes,
    outputSha256: result.outputSha256,
  };
}

function summarizePackageVerification(value) {
  if (Array.isArray(value)) return value.map((entry) => entry.package);
  return value.package;
}

export function candidateAssetIdentities(directory, hostPlatform) {
  const root = resolve(directory);
  const suffixes = hostPlatform === "win32"
    ? [".exe"]
    : hostPlatform === "linux" ? [".deb", ".appimage"] : [];
  if (suffixes.length === 0) throw new Error(`Candidate assets aren't defined for ${hostPlatform}`);
  const files = readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isFile() && !entry.isSymbolicLink())
    .map((entry) => resolve(root, entry.name))
    .filter((path) => suffixes.some((suffix) => path.toLowerCase().endsWith(suffix)))
    .sort();
  if (files.length !== suffixes.length
      || suffixes.some((suffix) => !files.some((path) => path.toLowerCase().endsWith(suffix)))) {
    throw new Error(`Candidate directory doesn't contain the exact ${hostPlatform} package set`);
  }
  return files.map((path) => {
    const details = lstatSync(path);
    const bytes = readFileSync(path);
    return {
      filename: path.split(/[\\/]/).at(-1),
      bytes: details.size,
      sha256: createHash("sha256").update(bytes).digest("hex"),
    };
  });
}

function sourceIdentity() {
  const revisionResult = spawnSync("git", ["rev-parse", "HEAD"], {
    cwd: repositoryRoot,
    encoding: "utf8",
    stdio: "pipe",
  });
  const revision = revisionResult.status === 0 && /^[a-f0-9]{40}$/.test(revisionResult.stdout.trim())
    ? revisionResult.stdout.trim()
    : null;
  const statusResult = spawnSync(
    "git",
    ["status", "--porcelain=v1", "--untracked-files=all", "-z"],
    { cwd: repositoryRoot, encoding: null, stdio: "pipe" },
  );
  if (statusResult.error || statusResult.status !== 0) {
    return { revision, dirty: null, statusBytes: null, statusSha256: null };
  }
  const status = Buffer.from(statusResult.stdout ?? Buffer.alloc(0));
  return {
    revision,
    dirty: status.length > 0,
    statusBytes: status.length,
    statusSha256: createHash("sha256").update(status).digest("hex"),
  };
}

function probeWindowsArchitecture() {
  const result = spawnSync(
    "powershell",
    [
      "-NoProfile",
      "-NonInteractive",
      "-Command",
      "[System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()",
    ],
    { encoding: "utf8", stdio: "pipe" },
  );
  return result.status === 0 ? result.stdout.trim() : null;
}

function normalizeArchitecture(value) {
  const normalized = String(value ?? "").trim().toLowerCase();
  if (normalized === "arm64" || normalized === "aarch64") return "arm64";
  if (normalized === "x64" || normalized === "amd64" || normalized === "x86_64") return "x64";
  return null;
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) main();
