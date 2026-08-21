import assert from "node:assert/strict";
import test from "node:test";
import { privilegedCommand } from "./privileged-command.mjs";

test("root package rehearsals invoke the package manager directly", () => {
  assert.deepEqual(privilegedCommand("dpkg", ["--install", "Preflight.deb"], 0), {
    command: "dpkg",
    args: ["--install", "Preflight.deb"],
  });
});

test("unprivileged package rehearsals elevate the same bounded command", () => {
  assert.deepEqual(privilegedCommand("dpkg", ["--remove", "preflight"], 501), {
    command: "sudo",
    args: ["dpkg", "--remove", "preflight"],
  });
});
