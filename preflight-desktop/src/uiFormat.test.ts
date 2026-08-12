import { describe, expect, test } from "vitest";
import { cleanUserMessage, errorMessage, localDateStamp } from "./uiFormat";

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
