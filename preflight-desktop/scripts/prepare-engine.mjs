import {
  chmodSync,
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  realpathSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { runtimeInventory, verifyEngineBoundary } from "./engine-boundary.mjs";
import { engineInputIdentity } from "./engine-input-identity.mjs";
import { writeCapabilityReceipt } from "./capability-receipt.mjs";
import { mavenInvocation } from "./maven-command.mjs";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(desktopDirectory, "..");
const sourceJar = join(repositoryRoot, "preflight-cli", "target", "preflight.jar");
const engineDirectory = join(desktopDirectory, "src-tauri", "target", "engine");
const runtimeDirectory = join(engineDirectory, "runtime");
const smokeScenario = join(repositoryRoot, "scripts", "scenarios", "campaign-roam.json");
const measurementScenario = join(repositoryRoot, "scripts", "scenarios", "campaign-roam-measurement-only.json");
const startupScenario = join(repositoryRoot, "scripts", "scenarios", "startup.json");
const startupMeasurementScenario = join(repositoryRoot, "scripts", "scenarios", "startup-measurement-only.json");
const legalSources = [
  [join(repositoryRoot, "LICENSE"), "LICENSE"],
  [join(repositoryRoot, "THIRD_PARTY_NOTICES.md"), "THIRD_PARTY_NOTICES.md"],
  [join(repositoryRoot, "docs", "privacy.md"), "PRIVACY.md"],
  [join(repositoryRoot, "docs", "known-limitations.md"), "KNOWN_LIMITATIONS.md"],
];
const argumentsSet = new Set(process.argv.slice(2));
const useVerifiedJar = argumentsSet.delete("--use-verified-jar");
const reuseIfCurrent = argumentsSet.delete("--reuse-if-current");
if (argumentsSet.size > 0) {
  throw new Error(`Unknown prepare-engine option: ${[...argumentsSet][0]}`);
}
if (useVerifiedJar && reuseIfCurrent) {
  throw new Error("Verified artifact preparation cannot reuse a developer engine");
}
const javaHome = findJavaHome();
const inputIdentity = currentEngineInputIdentity(javaHome);
const identityPath = join(desktopDirectory, "src-tauri", "target", "engine-input.json");
if (reuseIfCurrent && reuseCurrentEngine(inputIdentity, identityPath)) process.exit(0);
if (!useVerifiedJar) {
  runMaven(["-pl", "preflight-cli", "-am", "-Dmaven.test.skip=true", "package"]);
}
if (!existsSync(sourceJar)) {
  const context = useVerifiedJar ? "The verified engine JAR is missing" : "Maven produced no engine JAR";
  throw new Error(`${context}: ${sourceJar}`);
}

const jlink = join(javaHome, "bin", process.platform === "win32" ? "jlink.exe" : "jlink");
const jdeps = join(javaHome, "bin", process.platform === "win32" ? "jdeps.exe" : "jdeps");
if (!existsSync(jlink)) {
  throw new Error(`jlink was not found under ${javaHome}. Install a full JDK 17 or newer.`);
}
if (!existsSync(jdeps)) {
  throw new Error(`jdeps was not found under ${javaHome}. Install a full JDK 17 or newer.`);
}
const modules = detectModules(jdeps);
const compression = selectCompression(jlink);

// This is a generated, task-specific bundle directory; never point it outside src-tauri/target.
rmSync(engineDirectory, { recursive: true, force: true });
mkdirSync(engineDirectory, { recursive: true });
cpSync(sourceJar, join(engineDirectory, "preflight.jar"));
mkdirSync(join(engineDirectory, "scenarios"), { recursive: true });
cpSync(smokeScenario, join(engineDirectory, "scenarios", "campaign-roam.json"));
cpSync(measurementScenario, join(engineDirectory, "scenarios", "campaign-roam-measurement-only.json"));
cpSync(startupScenario, join(engineDirectory, "scenarios", "startup.json"));
cpSync(startupMeasurementScenario, join(engineDirectory, "scenarios", "startup-measurement-only.json"));
mkdirSync(join(engineDirectory, "legal"), { recursive: true });
for (const [source, name] of legalSources) {
  if (!statSync(source, { throwIfNoEntry: false })?.isFile()) {
    throw new Error(`Required legal or release document is missing: ${source}`);
  }
  cpSync(source, join(engineDirectory, "legal", name));
}

rmSync(runtimeDirectory, { recursive: true, force: true });

run(
  jlink,
  [
    "--module-path",
    join(javaHome, "jmods"),
    "--add-modules",
    modules.join(","),
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    `--compress=${compression}`,
    "--output",
    runtimeDirectory,
  ],
  repositoryRoot,
);
materializeSymlinks(runtimeDirectory);
makeWritable(runtimeDirectory);
const bundledJava = join(
  runtimeDirectory,
  "bin",
  process.platform === "win32" ? "java.exe" : "java",
);
runQuiet(bundledJava, ["-jar", join(engineDirectory, "preflight.jar"), "help"], repositoryRoot);

const sourceVersion = readProjectVersion();
const capabilityReceiptPath = join(engineDirectory, "capability-receipt.json");
writeCapabilityReceipt(capabilityReceiptPath, {
  engineJarPath: join(engineDirectory, "preflight.jar"),
  productVersion: sourceVersion,
});
const capabilityReceipt = readFileSync(capabilityReceiptPath);
const manifest = {
  modules,
  compression,
  jarBytes: statSync(sourceJar).size,
  jarSha256: createHash("sha256").update(readFileSync(sourceJar)).digest("hex"),
  smokeScenarioBytes: statSync(smokeScenario).size,
  measurementScenarioBytes: statSync(measurementScenario).size,
  startupScenarioBytes: statSync(startupScenario).size,
  startupMeasurementScenarioBytes: statSync(startupMeasurementScenario).size,
  legalFiles: Object.fromEntries(
    legalSources.map(([source, name]) => [name, statSync(source).size]),
  ),
  capabilityReceiptBytes: capabilityReceipt.length,
  capabilityReceiptSha256: createHash("sha256").update(capabilityReceipt).digest("hex"),
  runtime: runtimeInventory(runtimeDirectory),
  sourceVersion,
};
writeFileSync(join(engineDirectory, "bundle.json"), `${JSON.stringify(manifest, null, 2)}\n`);

const boundary = verifyEngineBoundary(engineDirectory);
mkdirSync(dirname(identityPath), { recursive: true });
writeFileSync(identityPath, `${JSON.stringify(inputIdentity, null, 2)}\n`);

console.log(
  `Desktop engine ready at ${engineDirectory} (${boundary.runtimeFiles} runtime files; input ${inputIdentity.sha256})`,
);

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} exited with status ${result.status}`);
  }
}

function runQuiet(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, encoding: "utf8" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`.trim();
    throw new Error(
      `${command} exited with status ${result.status}${output ? `:\n${output}` : ""}`,
    );
  }
}

function runMaven(args) {
  const invocation = mavenInvocation({ repositoryRoot });
  run(invocation.command, [...invocation.argsPrefix, ...args], repositoryRoot);
}

function detectModules(jdeps) {
  const result = spawnSync(
    jdeps,
    ["--multi-release", "17", "--ignore-missing-deps", "--print-module-deps", sourceJar],
    { cwd: repositoryRoot, encoding: "utf8" },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`jdeps exited with status ${result.status}: ${result.stderr?.trim() ?? ""}`);
  }
  const modules = result.stdout.trim().split(",").filter(Boolean);
  if (modules.length === 0) {
    throw new Error("jdeps did not report any runtime modules.");
  }
  return modules;
}

function selectCompression(jlink) {
  const result = spawnSync(jlink, ["--help"], { encoding: "utf8" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`jlink --help exited with status ${result.status}`);
  }
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  return output.includes("zip-[0-9]") ? "zip-6" : "2";
}

function findJavaHome() {
  if (process.env.JAVA_HOME && existsSync(join(process.env.JAVA_HOME, "jmods"))) {
    return process.env.JAVA_HOME;
  }
  const result = spawnSync("java", ["-XshowSettings:properties", "-version"], {
    encoding: "utf8",
  });
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  const match = output.match(/^\s*java\.home\s*=\s*(.+)\s*$/m);
  if (!match) {
    throw new Error("Could not locate a full Java development kit.");
  }
  return match[1].trim();
}

function currentEngineInputIdentity(javaHome) {
  const executableName = (name) => process.platform === "win32" ? `bin/${name}.exe` : `bin/${name}`;
  const jdkIdentity = engineInputIdentity(javaHome, [
    executableName("java"),
    executableName("jdeps"),
    executableName("jlink"),
    "jmods",
    "release",
  ]);
  return engineInputIdentity(repositoryRoot, [
    ".mvn",
    "LICENSE",
    "THIRD_PARTY_NOTICES.md",
    "docs/known-limitations.md",
    "docs/privacy.md",
    "mvnw",
    "mvnw.cmd",
    "pom.xml",
    "preflight-agent/pom.xml",
    "preflight-agent/src/main",
    "preflight-cli/pom.xml",
    "preflight-cli/src/main",
    "preflight-core/pom.xml",
    "preflight-core/src/main",
    "preflight-desktop/capabilities",
    "preflight-desktop/scripts/capability-receipt.mjs",
    "preflight-desktop/scripts/engine-boundary.mjs",
    "preflight-desktop/scripts/engine-input-identity.mjs",
    "preflight-desktop/scripts/prepare-engine.mjs",
    "preflight-desktop/src-tauri/capabilities/default.json",
    "preflight-desktop/src-tauri/src/lib.rs",
    "preflight-desktop/src-tauri/src/updates.rs",
    "preflight-desktop/src-tauri/tauri.conf.json",
    "scripts/scenarios/campaign-roam-measurement-only.json",
    "scripts/scenarios/campaign-roam.json",
    "scripts/scenarios/startup-measurement-only.json",
    "scripts/scenarios/startup.json",
  ], {
    architecture: process.arch,
    jdk: jdkIdentity.sha256,
    platform: process.platform,
    reportIntakeOrigin: process.env.PREFLIGHT_REPORT_INTAKE_ORIGIN ?? "",
    updateEndpoint: process.env.PREFLIGHT_UPDATER_ENDPOINT ?? "",
    updateKeyConfigured: Boolean(process.env.PREFLIGHT_UPDATER_PUBLIC_KEY?.trim()),
  });
}

function reuseCurrentEngine(expected, path) {
  try {
    const retained = JSON.parse(readFileSync(path, "utf8"));
    if (retained.format !== expected.format || retained.sha256 !== expected.sha256) {
      console.log(`Desktop engine input changed; rebuilding (${expected.sha256})`);
      return false;
    }
    refreshCapabilityReceipt();
    const boundary = verifyEngineBoundary(engineDirectory);
    console.log(
      `Desktop engine reused at ${engineDirectory} (${boundary.runtimeFiles} runtime files; input ${expected.sha256})`,
    );
    return true;
  } catch (error) {
    const reason = error instanceof Error ? error.message.split("\n", 1)[0] : String(error);
    console.log(`Desktop engine reuse unavailable; rebuilding (${reason})`);
    return false;
  }
}

function refreshCapabilityReceipt() {
  const receiptPath = join(engineDirectory, "capability-receipt.json");
  rmSync(receiptPath, { force: true });
  writeCapabilityReceipt(receiptPath, {
    engineJarPath: join(engineDirectory, "preflight.jar"),
    productVersion: readProjectVersion(),
  });
  const receipt = readFileSync(receiptPath);
  const manifestPath = join(engineDirectory, "bundle.json");
  const retainedManifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  retainedManifest.capabilityReceiptBytes = receipt.length;
  retainedManifest.capabilityReceiptSha256 = createHash("sha256").update(receipt).digest("hex");
  writeFileSync(manifestPath, `${JSON.stringify(retainedManifest, null, 2)}\n`);
}

function readProjectVersion() {
  const pom = readFileSync(join(repositoryRoot, "pom.xml"), "utf8");
  const withoutParent = pom.replace(/<parent>[\s\S]*?<\/parent>/, "");
  return withoutParent.match(/<version>([^<]+)<\/version>/)?.[1] ?? "unknown";
}

function materializeSymlinks(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      materializeSymlinks(path);
    } else if (entry.isSymbolicLink()) {
      const target = realpathSync(path);
      const mode = statSync(target).mode;
      rmSync(path);
      cpSync(target, path);
      chmodSync(path, mode);
    }
  }
}

function makeWritable(directory) {
  const directoryMode = statSync(directory).mode;
  chmodSync(directory, directoryMode | 0o700);
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      makeWritable(path);
    } else {
      const mode = statSync(path).mode;
      chmodSync(path, mode | 0o200);
    }
  }
}
