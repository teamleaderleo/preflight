import assert from "node:assert/strict";
import test from "node:test";
import { join } from "node:path";
import { mavenInvocation } from "./maven-command.mjs";

test("Windows engine preparation prefers the repository Maven wrapper", () => {
  const repositoryRoot = "C:\\work\\preflight";
  const invocation = mavenInvocation({
    platform: "win32",
    environment: { ComSpec: "C:\\Windows\\System32\\cmd.exe" },
    repositoryRoot,
    fileExists: (path) => path === join(repositoryRoot, "mvnw.cmd"),
  });

  assert.deepEqual(invocation, {
    command: "C:\\Windows\\System32\\cmd.exe",
    argsPrefix: ["/d", "/s", "/c", "mvnw.cmd"],
  });
});

test("Windows engine preparation falls back to a system Maven command", () => {
  assert.deepEqual(
    mavenInvocation({
      platform: "win32",
      environment: {},
      repositoryRoot: "C:\\work\\preflight",
      fileExists: () => false,
    }),
    { command: "cmd.exe", argsPrefix: ["/d", "/s", "/c", "mvn.cmd"] },
  );
});

test("Unix engine preparation prefers the executable repository wrapper", () => {
  const repositoryRoot = "/work/preflight";
  assert.deepEqual(
    mavenInvocation({
      platform: "linux",
      repositoryRoot,
      fileExists: (path) => path === join(repositoryRoot, "mvnw"),
    }),
    { command: join(repositoryRoot, "mvnw"), argsPrefix: [] },
  );
});
