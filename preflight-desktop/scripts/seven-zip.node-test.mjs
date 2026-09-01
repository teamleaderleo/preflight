import assert from "node:assert/strict";
import test from "node:test";
import { join } from "node:path";
import { sevenZipCommand } from "./seven-zip.mjs";

test("Windows package verification finds a standard 7-Zip installation outside PATH", () => {
  const programFiles = "C:\\Program Files";
  const expected = join(programFiles, "7-Zip", "7z.exe");
  assert.equal(
    sevenZipCommand({
      platform: "win32",
      environment: { ProgramFiles: programFiles },
      fileExists: (path) => path === expected,
    }),
    expected,
  );
});

test("Windows package verification falls back to PATH when no standard install exists", () => {
  assert.equal(
    sevenZipCommand({
      platform: "win32",
      environment: {},
      fileExists: () => false,
    }),
    "7z",
  );
});

test("non-Windows package verification keeps the PATH command", () => {
  assert.equal(sevenZipCommand({ platform: "linux" }), "7z");
});
