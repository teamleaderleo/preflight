import type { PlaytimeSnapshot } from "./types";
import { formatPlaytime } from "./uiFormat";

const MAX_PLAYTIME_SHARE_CHARACTERS = 1_024;

export function createPlaytimeShareText(playtime: PlaytimeSnapshot): string {
  if (!playtime.readable || playtime.launches <= 0 || playtime.totalMillis <= 0) {
    throw new Error("Recorded playtime is unavailable.");
  }
  const lines = [
    "Preflight playtime",
    `Recorded playtime: ${formatPlaytime(playtime.totalMillis)}`,
    `Recorded sessions: ${playtime.launches.toLocaleString("en-US")}`,
    `Longest session: ${formatPlaytime(playtime.longestSessionMillis)}`,
    `Average session: ${formatPlaytime(playtime.averageMillis)}`,
  ];
  if (playtime.first) lines.push(`First recorded session: ${dateOnly(playtime.first)}`);
  if (playtime.last) lines.push(`Latest recorded session: ${dateOnly(playtime.last)}`);
  if (playtime.sessionsWithoutDuration > 0) {
    lines.push(`Sessions with unknown duration: ${playtime.sessionsWithoutDuration.toLocaleString("en-US")}`);
  }
  lines.push("Observed locally by Preflight from Starsector sessions launched through Preflight.");
  const text = `${lines.join("\n")}\n`;
  if (text.length > MAX_PLAYTIME_SHARE_CHARACTERS) {
    throw new Error("Playtime share text exceeded its fixed size limit.");
  }
  return text;
}

function dateOnly(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "unknown";
  return date.toISOString().slice(0, 10);
}
