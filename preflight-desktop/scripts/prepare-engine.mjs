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
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(desktopDirectory, "..");
const sourceJar = join(repositoryRoot, "preflight-cli", "target", "preflight.jar");
const engineDirectory = join(desktopDirectory, "src-tauri", "target", "engine");
const runtimeDirectory = join(engineDirectory, "runtime");
const smokeScenario = join(repositoryRoot, "scripts", "scenarios", "campaign-roam.json");
runMaven(["-pl", "preflight-cli", "-am", "-DskipTests", "package"]);
if (!existsSync(sourceJar)) {
  throw new Error(`Maven completed without producing ${sourceJar}`);
}

const javaHome = findJavaHome();
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
run(bundledJava, ["-jar", join(engineDirectory, "preflight.jar"), "help"], repositoryRoot);

const manifest = {
  generatedAt: new Date().toISOString(),
  javaHome,
  modules,
  compression,
  jarBytes: statSync(sourceJar).size,
  smokeScenarioBytes: statSync(smokeScenario).size,
  sourceVersion: readProjectVersion(),
};
writeFileSync(join(engineDirectory, "bundle.json"), `${JSON.stringify(manifest, null, 2)}\n`);

console.log(`Desktop engine ready at ${engineDirectory}`);

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} exited with status ${result.status}`);
  }
}

function runMaven(args) {
  if (process.platform === "win32") {
    const commandInterpreter = process.env.ComSpec ?? "cmd.exe";
    run(commandInterpreter, ["/d", "/s", "/c", "mvn.cmd", ...args], repositoryRoot);
  } else {
    run("mvn", args, repositoryRoot);
  }
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
