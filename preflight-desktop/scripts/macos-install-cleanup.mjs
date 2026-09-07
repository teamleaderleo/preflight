import { spawnSync } from "node:child_process";
import { rmdirSync, rmSync } from "node:fs";

const pause = (milliseconds) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);

export function detachMacVolume(mountDirectory, { spawn = spawnSync, wait = pause, warn = console.warn } = {}) {
  const delays = [250, 500, 1000];
  for (let attempt = 0; ; attempt++) {
    const result = spawn("hdiutil", ["detach", mountDirectory], { encoding: "utf8", timeout: 10_000 });
    if (!result.error && result.status === 0) return;
    const detail = String(result.stderr ?? "").trim().slice(0, 1024);
    if (!result.error && result.status === 16 && attempt < delays.length) {
      warn(`Temporary DMG is busy; retrying detach (${attempt + 1}/${delays.length}): ${detail}`);
      wait(delays[attempt]);
      continue;
    }
    throw new Error(`Could not detach temporary DMG ${mountDirectory}: ${detail || result.error?.message || result.status}`,
      { cause: result.error });
  }
}

export function cleanupMacInstall({ mounted, mountDirectory, installDirectory, dataDirectory, originalError }, {
  detach = detachMacVolume,
  removeMount = rmdirSync,
  removeTree = (path) => rmSync(path, { recursive: true, force: true }),
} = {}) {
  const failures = [];
  let detached = !mounted;
  if (mounted) {
    try {
      detach(mountDirectory);
      detached = true;
    } catch (error) {
      failures.push(error);
    }
  }
  // Never recursively traverse a volume whose detach failed.
  if (detached) {
    try { removeMount(mountDirectory); } catch (error) {
      if (error.code !== "ENOENT") failures.push(error);
    }
  }
  for (const directory of [installDirectory, dataDirectory]) {
    try { removeTree(directory); } catch (error) { failures.push(error); }
  }
  if (failures.length) {
    throw new AggregateError(originalError ? [originalError, ...failures] : failures,
      "Native macOS installation cleanup failed", { cause: originalError ?? failures[0] });
  }
}
