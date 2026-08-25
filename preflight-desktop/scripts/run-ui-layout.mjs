import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const desktopDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const requirementsPath = join(desktopDirectory, "scripts", "ui-layout-requirements.txt");
export const environmentDirectory = join(
  desktopDirectory,
  "node_modules",
  ".preflight-ui-layout",
);
export const environmentStamp = join(environmentDirectory, ".requirements-sha256");

export function environmentPython(platform = process.platform) {
  return platform === "win32"
    ? join(environmentDirectory, "Scripts", "python.exe")
    : join(environmentDirectory, "bin", "python");
}

export function requirementsDigest() {
  return createHash("sha256").update(readFileSync(requirementsPath)).digest("hex");
}

export function verifierArguments(arguments_) {
  const hasOutput = arguments_.some((argument) => (
    argument === "--output-dir" || argument.startsWith("--output-dir=")
  ));
  return [
    "scripts/verify-ui-layout.py",
    ...(hasOutput ? [] : ["--output-dir", ".ui-matrix"]),
    ...arguments_,
  ];
}

export function isolatedBrowserEnvironment(environment = process.env) {
  return { ...environment, PLAYWRIGHT_BROWSERS_PATH: "0" };
}

function runChecked(command, arguments_, options = {}) {
  const result = spawnSync(command, arguments_, {
    cwd: desktopDirectory,
    env: options.env ?? process.env,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} exited with status ${result.status}`);
  }
}

function findPython() {
  const candidates = [
    process.env.PREFLIGHT_PYTHON,
    "python3",
    "python",
  ].filter(Boolean);
  for (const candidate of candidates) {
    const result = spawnSync(candidate, [
      "-c",
      "import sys; raise SystemExit(0 if sys.version_info.major == 3 else 1)",
    ], { stdio: "ignore" });
    if (!result.error && result.status === 0) return candidate;
  }
  throw new Error(
    "Python 3 is required for the rendered UI matrix. Set PREFLIGHT_PYTHON to its executable.",
  );
}

function environmentIsReady(python, expected) {
  if (!existsSync(python) || !existsSync(environmentStamp)) return false;
  if (readFileSync(environmentStamp, "utf8").trim() !== expected) return false;

  const probe = spawnSync(python, [
    "-c",
    [
      "from pathlib import Path",
      "from playwright.sync_api import sync_playwright",
      "with sync_playwright() as playwright:",
      "    raise SystemExit(0 if Path(playwright.chromium.executable_path).is_file() else 1)",
    ].join("\n"),
  ], {
    cwd: desktopDirectory,
    env: isolatedBrowserEnvironment(),
    stdio: "ignore",
  });
  return !probe.error && probe.status === 0;
}

function ensureEnvironment(systemPython) {
  const python = environmentPython();
  const expected = requirementsDigest();
  if (environmentIsReady(python, expected)) return python;

  rmSync(environmentDirectory, { recursive: true, force: true });
  mkdirSync(dirname(environmentDirectory), { recursive: true });
  runChecked(systemPython, ["-m", "venv", environmentDirectory]);
  runChecked(python, [
    "-m",
    "pip",
    "install",
    "--disable-pip-version-check",
    "-r",
    requirementsPath,
  ]);
  const browserEnvironment = isolatedBrowserEnvironment();
  runChecked(python, ["-m", "playwright", "install", "chromium"], {
    env: browserEnvironment,
  });
  writeFileSync(environmentStamp, `${expected}\n`, "utf8");
  return python;
}

export function runUiMatrix(arguments_ = process.argv.slice(2)) {
  const python = ensureEnvironment(findPython());
  const npm = process.platform === "win32" ? "npm.cmd" : "npm";
  runChecked(npm, ["run", "build"]);
  runChecked(python, verifierArguments(arguments_), {
    env: isolatedBrowserEnvironment(),
  });
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  try {
    runUiMatrix();
  } catch (error) {
    console.error(`UI matrix failed: ${error instanceof Error ? error.message : error}`);
    process.exitCode = 1;
  }
}
