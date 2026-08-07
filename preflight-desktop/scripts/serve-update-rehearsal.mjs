import { createReadStream, lstatSync, readdirSync } from "node:fs";
import { createServer } from "node:http";
import { basename, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const contentTypes = new Map([
  [".json", "application/json; charset=utf-8"],
  [".sha256", "text/plain; charset=utf-8"],
  [".sig", "text/plain; charset=utf-8"],
]);

export function rehearsalFiles(root) {
  const directory = resolve(root);
  const entries = readdirSync(directory, { withFileTypes: true });
  const files = new Map();
  for (const entry of entries) {
    if (!entry.isFile()) throw new Error(`Rehearsal feed contains a non-file entry: ${entry.name}`);
    const path = resolve(directory, entry.name);
    const details = lstatSync(path);
    if (details.isSymbolicLink() || !details.isFile()) {
      throw new Error(`Rehearsal feed contains an unsafe entry: ${entry.name}`);
    }
    files.set(`/${entry.name}`, { path, size: details.size });
  }
  if (!files.has("/latest.json")) throw new Error("Rehearsal feed is missing latest.json");
  return files;
}

export function resolveRehearsalRequest(files, rawUrl, method) {
  if (method !== "GET" && method !== "HEAD") return { status: 405 };
  let pathname;
  try {
    pathname = decodeURIComponent(rawUrl.split(/[?#]/, 1)[0]);
  } catch {
    return { status: 400 };
  }
  if (!pathname.startsWith("/") || pathname !== `/${basename(pathname)}` ||
      pathname.includes("\\") || pathname.includes("\0")) {
    return { status: 404 };
  }
  const file = files.get(pathname);
  return file ? { status: 200, ...file } : { status: 404 };
}

export function createRehearsalServer(root) {
  const files = rehearsalFiles(root);
  return createServer((request, response) => {
    const result = resolveRehearsalRequest(files, request.url ?? "/", request.method ?? "GET");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (result.status !== 200) {
      if (result.status === 405) response.setHeader("Allow", "GET, HEAD");
      response.writeHead(result.status);
      response.end();
      return;
    }
    const extension = [...contentTypes.keys()].find((suffix) => result.path.endsWith(suffix));
    response.setHeader("Content-Type", contentTypes.get(extension) ?? "application/octet-stream");
    response.setHeader("Content-Length", result.size);
    response.writeHead(200);
    if (request.method === "HEAD") response.end();
    else createReadStream(result.path).pipe(response);
  });
}

function main() {
  const root = process.argv[2];
  const requestedPort = process.argv[3] ?? "8788";
  if (!root || !/^\d+$/.test(requestedPort)) {
    throw new Error("Usage: node serve-update-rehearsal.mjs <feed-directory> [port]");
  }
  const port = Number(requestedPort);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new Error(`Invalid rehearsal port: ${requestedPort}`);
  }
  const server = createRehearsalServer(root);
  server.listen(port, "127.0.0.1", () => {
    console.log(`Serving the isolated update rehearsal at http://127.0.0.1:${port}`);
  });
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) main();
