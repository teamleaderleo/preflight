import { createHash } from "node:crypto";
import { lstat, readdir, readFile, writeFile } from "node:fs/promises";
import { resolve, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function portablePath(root, path) {
  return relative(root, path).split(sep).join("/");
}

async function inventory(rootName) {
  const root = resolve(rootName);
  let rootStat;
  try {
    rootStat = await lstat(root);
  } catch {
    throw new Error(`Frontend distribution directory does not exist: ${root}`);
  }
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
    throw new Error(`Frontend distribution root must be a real directory: ${root}`);
  }

  const files = [];
  async function walk(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = resolve(directory, entry.name);
      const stat = await lstat(path);
      if (stat.isSymbolicLink()) {
        throw new Error(`Frontend distribution contains a symbolic link: ${portablePath(root, path)}`);
      }
      if (stat.isDirectory()) {
        await walk(path);
        continue;
      }
      if (!stat.isFile()) {
        throw new Error(`Frontend distribution contains a non-file entry: ${portablePath(root, path)}`);
      }
      const bytes = await readFile(path);
      files.push({
        path: portablePath(root, path),
        bytes: bytes.length,
        sha256: sha256(bytes),
      });
    }
  }
  await walk(root);
  if (files.length === 0) {
    throw new Error(`Frontend distribution directory is empty: ${root}`);
  }
  files.sort((left, right) => left.path.localeCompare(right.path));
  return files;
}

export async function buildManifest(rootName) {
  const files = await inventory(rootName);
  return {
    format: "preflight-frontend-dist-v1",
    files,
    fileCount: files.length,
    totalBytes: files.reduce((total, file) => total + file.bytes, 0),
  };
}

export async function writeManifest(rootName, manifestName) {
  const manifest = await buildManifest(rootName);
  await writeFile(resolve(manifestName), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  return manifest;
}

export async function verifyManifest(rootName, manifestName) {
  const expected = JSON.parse(await readFile(resolve(manifestName), "utf8"));
  if (!expected || expected.format !== "preflight-frontend-dist-v1" || !Array.isArray(expected.files)) {
    throw new Error("Unsupported or malformed frontend distribution manifest");
  }
  const actual = await buildManifest(rootName);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    const expectedFiles = new Map(expected.files.map((file) => [file.path, file]));
    const actualFiles = new Map(actual.files.map((file) => [file.path, file]));
    const missing = [...expectedFiles.keys()].filter((path) => !actualFiles.has(path)).sort();
    const added = [...actualFiles.keys()].filter((path) => !expectedFiles.has(path)).sort();
    const changed = [...expectedFiles.keys()]
      .filter((path) => actualFiles.has(path) && JSON.stringify(expectedFiles.get(path)) !== JSON.stringify(actualFiles.get(path)))
      .sort();
    throw new Error(
      `Frontend distribution differs from verified artifact: missing=${JSON.stringify(missing)}, added=${JSON.stringify(added)}, changed=${JSON.stringify(changed)}`,
    );
  }
  return actual;
}

async function main(argv) {
  const [operation, rootName, manifestName] = argv;
  if (!operation || !rootName || !manifestName || !["write", "verify"].includes(operation)) {
    throw new Error("Usage: frontend-dist-manifest.mjs <write|verify> <dist-directory> <manifest.json>");
  }
  const manifest = operation === "write"
    ? await writeManifest(rootName, manifestName)
    : await verifyManifest(rootName, manifestName);
  process.stdout.write(`Verified frontend distribution: ${manifest.fileCount} files, ${manifest.totalBytes} bytes\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
