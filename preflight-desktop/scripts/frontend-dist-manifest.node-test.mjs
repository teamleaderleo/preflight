import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { buildManifest, verifyManifest, writeManifest } from "./frontend-dist-manifest.mjs";

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "preflight-frontend-dist-"));
  const dist = join(root, "dist");
  const manifest = join(root, "frontend-dist.json");
  await mkdir(join(dist, "assets"), { recursive: true });
  await writeFile(join(dist, "index.html"), "<main>preflight</main>\n", "utf8");
  await writeFile(join(dist, "assets", "app.js"), "console.log('preflight');\n", "utf8");
  return { root, dist, manifest };
}

test("frontend inventory is deterministic across file creation order", async () => {
  const first = await fixture();
  const secondRoot = await mkdtemp(join(tmpdir(), "preflight-frontend-dist-order-"));
  const second = join(secondRoot, "dist");
  try {
    await mkdir(join(second, "assets"), { recursive: true });
    await writeFile(join(second, "assets", "app.js"), "console.log('preflight');\n", "utf8");
    await writeFile(join(second, "index.html"), "<main>preflight</main>\n", "utf8");
    assert.deepEqual(await buildManifest(first.dist), await buildManifest(second));
  } finally {
    await rm(first.root, { recursive: true, force: true });
    await rm(secondRoot, { recursive: true, force: true });
  }
});

test("written manifest verifies the exact frontend bytes", async () => {
  const files = await fixture();
  try {
    const written = await writeManifest(files.dist, files.manifest);
    assert.equal(written.format, "preflight-frontend-dist-v1");
    assert.equal(written.fileCount, 2);
    assert.deepEqual(await verifyManifest(files.dist, files.manifest), written);
    const parsed = JSON.parse(await readFile(files.manifest, "utf8"));
    assert.deepEqual(parsed, written);
  } finally {
    await rm(files.root, { recursive: true, force: true });
  }
});

test("verification rejects changed, added and removed frontend files", async () => {
  const files = await fixture();
  try {
    await writeManifest(files.dist, files.manifest);
    await writeFile(join(files.dist, "assets", "app.js"), "console.log('changed');\n", "utf8");
    await assert.rejects(
      verifyManifest(files.dist, files.manifest),
      /changed=\["assets\/app\.js"\]/,
    );

    await writeFile(join(files.dist, "assets", "app.js"), "console.log('preflight');\n", "utf8");
    await writeFile(join(files.dist, "extra.txt"), "extra\n", "utf8");
    await assert.rejects(verifyManifest(files.dist, files.manifest), /added=\["extra\.txt"\]/);

    await rm(join(files.dist, "extra.txt"));
    await rm(join(files.dist, "index.html"));
    await assert.rejects(verifyManifest(files.dist, files.manifest), /missing=\["index\.html"\]/);
  } finally {
    await rm(files.root, { recursive: true, force: true });
  }
});

test("manifest creation refuses a missing or empty distribution", async () => {
  const root = await mkdtemp(join(tmpdir(), "preflight-frontend-dist-empty-"));
  try {
    await assert.rejects(buildManifest(join(root, "missing")), /does not exist/);
    const empty = join(root, "empty");
    await mkdir(empty);
    await assert.rejects(buildManifest(empty), /is empty/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
