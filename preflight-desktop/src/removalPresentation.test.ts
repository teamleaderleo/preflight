import { expect, test } from "vitest";
import { presentRemovalRefusal } from "./removalPresentation";

test("unsafe data-root refusal keeps the native path out of player-facing copy", () => {
  const detail = "Preflight home directory is a symlink or alias (/Users/player/.starsector-preflight). All-data removal is refused.";

  const presentation = presentRemovalRefusal(detail);

  expect(presentation.summary).toBe("Preflight can’t safely remove its data because the Preflight data folder isn’t a normal directory. Nothing was removed.");
  expect(presentation.summary).not.toContain("/Users/player");
  expect(presentation.detail).toBe(detail);
});

test("unowned launcher refusal keeps the label while hiding the path and proof vocabulary", () => {
  const detail = "Existing macOS launcher at /Applications/Preflight.app is not proven Preflight-owned and is preserved untouched.";

  const presentation = presentRemovalRefusal(detail);

  expect(presentation.summary).toBe("Preflight left macOS launcher alone because it couldn’t verify that it created this launcher entry.");
  expect(presentation.summary).not.toContain("/Applications/Preflight.app");
  expect(presentation.summary).not.toContain("proven");
  expect(presentation.detail).toBe(detail);
});

test("unknown native refusal gets bounded generic player-facing copy", () => {
  const detail = "Could not remove /tmp/private/path: launcher pathname generation changed.";

  const presentation = presentRemovalRefusal(detail);

  expect(presentation.summary).toBe("Preflight left part of this removal plan alone because it couldn’t verify that it was safe to remove.");
  expect(presentation.summary).not.toContain("/tmp/private/path");
  expect(presentation.detail).toBe(detail);
});
