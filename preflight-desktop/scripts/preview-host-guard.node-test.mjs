import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "..", "..");
const bridgeSource = readFileSync(
  join(repositoryRoot, "preflight-desktop", "src", "bridge.ts"),
  "utf8",
);

/*
 * Every preview fixture in bridge.ts sits behind a hand-written `isDesktopHost()` check, thirty-odd
 * times over. Miss one and the shipped app serves an invented answer to a real person: a game
 * installation that was never found, a benchmark that was never run, a cleanup plan measured from
 * nothing. Nothing in the type system or the runtime says that guard is required, and the failure
 * is silent in exactly the build where it matters, because the desktop host is the one place the
 * browser preview never runs.
 *
 * This reads source rather than behaviour, so it lives with the other source-derived boundary
 * checks rather than in the renderer suite, whose TypeScript project has no Node types by design.
 */
test("no preview fixture can be reached without first ruling out the desktop host", () => {
  // Top-level declarations only: nested helpers inherit their parent's guard.
  const declarations = [...bridgeSource.matchAll(/^export (?:async )?function (\w+)/gm)];
  assert.ok(declarations.length > 20, "bridge.ts exports far fewer functions than expected");

  const unguarded = [];
  for (const [index, declaration] of declarations.entries()) {
    const name = declaration[1];
    // `browserPreviewScenario` is the accessor itself, so it cannot be asked to guard against it.
    if (name === "browserPreviewScenario" || name === "isDesktopHost") continue;
    const body = bridgeSource.slice(
      declaration.index,
      declarations[index + 1]?.index ?? bridgeSource.length,
    );
    const scenario = body.indexOf("browserPreviewScenario(");
    if (scenario === -1) continue;
    const guard = body.indexOf("isDesktopHost()");
    if (guard === -1 || guard > scenario) unguarded.push(name);
  }

  assert.deepEqual(unguarded, [], "these reach a preview fixture without ruling out the desktop host");
});
