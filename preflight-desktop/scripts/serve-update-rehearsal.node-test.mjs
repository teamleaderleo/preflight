import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  createRehearsalServer,
  rehearsalFiles,
  resolveRehearsalRequest,
} from "./serve-update-rehearsal.mjs";

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "preflight-rehearsal-feed-"));
  writeFileSync(join(directory, "latest.json"), "{}\n");
  writeFileSync(join(directory, "Preflight.app.tar.gz"), "signed-update");
  return directory;
}

test("serves only exact top-level rehearsal files", () => {
  const files = rehearsalFiles(fixture());
  assert.equal(resolveRehearsalRequest(files, "/latest.json?cache-bust=1", "GET").status, 200);
  assert.equal(resolveRehearsalRequest(files, "/Preflight.app.tar.gz", "HEAD").status, 200);
  assert.equal(resolveRehearsalRequest(files, "/missing", "GET").status, 404);
  assert.equal(resolveRehearsalRequest(files, "/../latest.json", "GET").status, 404);
  assert.equal(resolveRehearsalRequest(files, "/nested/latest.json", "GET").status, 404);
  assert.equal(resolveRehearsalRequest(files, "/latest.json", "POST").status, 405);
});

test("refuses directories and symbolic links in the feed root", () => {
  const withDirectory = fixture();
  mkdirSync(join(withDirectory, "nested"));
  assert.throws(() => rehearsalFiles(withDirectory), /non-file entry/);

  const withLink = fixture();
  symlinkSync(join(withLink, "latest.json"), join(withLink, "alias.json"));
  assert.throws(() => rehearsalFiles(withLink), /non-file entry|unsafe entry/);
});

test("HTTP responses are no-store and don't expose unlisted paths", async (context) => {
  const server = createRehearsalServer(fixture());
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;
  const response = await fetch(`${base}/latest.json`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(await response.text(), "{}\n");
  assert.equal((await fetch(`${base}/nested/latest.json`)).status, 404);
});
