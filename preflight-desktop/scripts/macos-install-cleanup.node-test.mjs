import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMacInstall, detachMacVolume } from "./macos-install-cleanup.mjs";

test("busy detach retries only the owned mount without forcing it", () => {
  const calls = [];
  const delays = [];
  let attempts = 0;
  detachMacVolume("/temporary/owned", {
    spawn: (...args) => { calls.push(args); return { status: ++attempts < 3 ? 16 : 0, stderr: "Resource busy" }; },
    wait: (delay) => delays.push(delay), warn: () => {},
  });
  assert.equal(calls.length, 3);
  for (const [command, args, options] of calls) {
    assert.equal(command, "hdiutil");
    assert.deepEqual(args, ["detach", "/temporary/owned"]);
    assert.equal(options.timeout, 10_000);
  }
  assert.deepEqual(delays, [250, 500]);
});

test("persistent busy status is bounded and other failures are not retried", () => {
  for (const [status, expected] of [[16, 4], [1, 1], [null, 1]]) {
    let attempts = 0;
    assert.throws(() => detachMacVolume("/temporary/owned", {
      spawn: () => { attempts++; return { status, stderr: "detach failed" }; },
      wait: () => {}, warn: () => {},
    }), /Could not detach temporary DMG/);
    assert.equal(attempts, expected);
  }
});

test("failed detach preserves the exercise error and mounted tree but cleans independent copies", () => {
  const originalError = new Error("exercise failed");
  const detachError = new Error("busy");
  const removed = [];
  assert.throws(() => cleanupMacInstall({
    mounted: true, mountDirectory: "/mount", installDirectory: "/install", dataDirectory: "/data", originalError,
  }, {
    detach: () => { throw detachError; },
    removeMount: () => assert.fail("must not remove attached mount"),
    removeTree: (path) => removed.push(path),
  }), (error) => {
    assert.deepEqual(error.errors, [originalError, detachError]);
    assert.equal(error.cause, originalError);
    return true;
  });
  assert.deepEqual(removed, ["/install", "/data"]);
});

test("successful detach precedes mount removal and remaining cleanup survives an error", () => {
  const calls = [];
  assert.throws(() => cleanupMacInstall({
    mounted: true, mountDirectory: "/mount", installDirectory: "/install", dataDirectory: "/data",
  }, {
    detach: (path) => calls.push(`detach ${path}`),
    removeMount: (path) => calls.push(`mount ${path}`),
    removeTree: (path) => { calls.push(path); if (path === "/install") throw new Error("remove failed"); },
  }), AggregateError);
  assert.deepEqual(calls, ["detach /mount", "mount /mount", "/install", "/data"]);
});
