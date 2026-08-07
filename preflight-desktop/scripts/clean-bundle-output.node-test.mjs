import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { bundleDirectory, cleanBundleOutput } from "./clean-bundle-output.mjs";

test("bundle cleanup is pinned to the reviewed derived-output directory", () => {
  assert.match(bundleDirectory, /preflight-desktop[/\\]src-tauri[/\\]target[/\\]release[/\\]bundle$/);
  assert.throws(
    () => cleanBundleOutput(join(tmpdir(), "preflight-unrelated")),
    /Refusing to clean anything except/,
  );
});
