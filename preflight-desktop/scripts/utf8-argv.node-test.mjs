import assert from "node:assert/strict";
import test from "node:test";

import { UTF8_ARGV_SENTINEL, decodeArgv, encodeArgv, isAsciiArgv } from "./utf8-argv.mjs";

const NON_ASCII = ["desktop", "snapshot", "--game", "C:\\Synthetic Game – path Ω", "--json"];

test("ascii vectors reach the engine unchanged", () => {
  const args = ["desktop", "snapshot", "--game", "C:\\Games\\Starsector", "--json"];
  assert.equal(isAsciiArgv(args), true);
  assert.deepEqual(encodeArgv(args), args);
});

test("a vector needing more than ascii is marked and round trips", () => {
  assert.equal(isAsciiArgv(NON_ASCII), false);
  const encoded = encodeArgv(NON_ASCII);
  assert.equal(encoded[0], UTF8_ARGV_SENTINEL);
  assert.equal(isAsciiArgv(encoded), true, "the encoded vector must survive an ANSI code page");
  assert.deepEqual(decodeArgv(encoded), NON_ASCII);
});

test("encoded arguments need no quoting", () => {
  for (const argument of encodeArgv(NON_ASCII).slice(1)) {
    assert.match(argument, /^[A-Za-z0-9_-]+$/u, `${argument} needs quoting`);
  }
});

test("scripts and astral characters round trip", () => {
  const args = ["--profile", "Пользователь", "--game", "游戏/日本語", "--note", "🚀"];
  assert.deepEqual(decodeArgv(encodeArgv(args)), args);
});

test("the sentinel only means something in the first position", () => {
  const args = ["desktop", UTF8_ARGV_SENTINEL, "--json"];
  assert.deepEqual(decodeArgv(args), args);
});

test("base64url output matches the engine and host encoders", () => {
  // The three implementations must agree byte for byte, so pin the shared vectors here too.
  assert.deepEqual(encodeArgv(["Ω"]), [UTF8_ARGV_SENTINEL, "zqk"]);
  assert.deepEqual(encodeArgv(["cache", "Ω"]), [UTF8_ARGV_SENTINEL, "Y2FjaGU", "zqk"]);
  assert.deepEqual(encodeArgv(["", "Ω"]), [UTF8_ARGV_SENTINEL, "", "zqk"]);
});
