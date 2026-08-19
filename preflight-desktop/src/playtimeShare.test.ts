import { expect, test } from "vitest";
import { createPlaytimeShareText } from "./playtimeShare";
import type { PlaytimeSnapshot } from "./types";

function snapshot(overrides: Partial<PlaytimeSnapshot> = {}): PlaytimeSnapshot {
  return {
    readable: true,
    totalMillis: 10 * 60 * 60 * 1_000,
    longestSessionMillis: 3 * 60 * 60 * 1_000,
    averageMillis: 2 * 60 * 60 * 1_000,
    launches: 5,
    sessionsWithoutDuration: 0,
    first: "2026-08-01T12:00:00Z",
    last: "2026-08-19T18:30:00Z",
    ...overrides,
  };
}

test("creates a bounded public playtime summary from the local ledger projection", () => {
  const text = createPlaytimeShareText(snapshot({ sessionsWithoutDuration: 1 }));

  expect(text).toContain("Recorded playtime: 10h");
  expect(text).toContain("Recorded sessions: 5");
  expect(text).toContain("Longest session: 3.0h");
  expect(text).toContain("Average session: 2.0h");
  expect(text).toContain("First recorded session: 2026-08-01");
  expect(text).toContain("Latest recorded session: 2026-08-19");
  expect(text).toContain("Sessions with unknown duration: 1");
  expect(text).toContain("Observed locally by Preflight");
  expect(text.length).toBeLessThan(1_024);
});

test("rejects an unavailable or empty playtime snapshot", () => {
  expect(() => createPlaytimeShareText(snapshot({ readable: false }))).toThrow(/unavailable/);
  expect(() => createPlaytimeShareText(snapshot({ totalMillis: 0 }))).toThrow(/unavailable/);
});
