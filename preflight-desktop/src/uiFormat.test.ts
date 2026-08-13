import { describe, expect, test } from "vitest";
import { cleanUserMessage, errorMessage, failedRunSummary, localDateStamp } from "./uiFormat";

describe("errorMessage", () => {
  test("keeps user-facing failures free of JavaScript's Error prefix", () => {
    expect(errorMessage(new Error("settings write refused"))).toBe("settings write refused");
    expect(errorMessage("Error: update feed unavailable")).toBe("update feed unavailable");
  });

  test("provides a bounded fallback for an empty failure", () => {
    expect(errorMessage("Error:   ")).toBe("Something went wrong.");
  });

  test("removes an embedded JavaScript error label from a complete notice", () => {
    expect(cleanUserMessage("Couldn’t use this folder. Error: launcher missing"))
      .toBe("Couldn’t use this folder: launcher missing");
  });
});

describe("localDateStamp", () => {
  test("uses the operator’s calendar date instead of UTC", () => {
    expect(localDateStamp(new Date(2026, 7, 12, 0, 5))).toBe("2026-08-12");
  });
});

describe("failedRunSummary", () => {
  test("keeps a short detail intact", () => {
    expect(failedRunSummary("could not open the launcher")).toContain("could not open the launcher");
  });

  test("falls back when there is no detail", () => {
    expect(failedRunSummary(undefined)).toBe("Starsector closed with an error. Support evidence was saved.");
  });

  test("never cuts a character in half", () => {
    // A plain slice counts UTF-16 units and would land inside a surrogate pair, leaving a lone
    // surrogate that renders as a replacement box.
    const detail = `${"a".repeat(359)}\u{1F680}tail`;
    const summary = failedRunSummary(detail);
    expect(summary).toContain("…");
    expect(summary).not.toMatch(/[\uD800-\uDFFF]/u);
    expect([...summary].every((character) => character.codePointAt(0) !== 0xfffd)).toBe(true);
  });

  test("counts characters rather than code units when deciding to shorten", () => {
    // 200 rockets are 400 UTF-16 units but only 200 characters, so they fit the 360 budget.
    // Measuring by `length` would have shortened this needlessly.
    expect(failedRunSummary("\u{1F680}".repeat(200))).not.toContain("…");
    expect(failedRunSummary("\u{1F680}".repeat(400))).toContain("…");
  });
});
