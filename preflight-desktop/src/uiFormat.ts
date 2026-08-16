import type { DesktopSnapshot, PlaytimeSnapshot } from "./types";

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit += 1;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[unit]}`;
}

export function formatMemory(memoryMiB: number): string {
  const gibibytes = memoryMiB / 1024;
  return `${Number.isInteger(gibibytes) ? gibibytes : gibibytes.toFixed(1)} GB`;
}

/** A compact cockpit-style total: precise enough to be useful, never falsely exact. */
export function formatPlaytime(totalMillis: number): string {
  const safeMillis = Math.max(0, totalMillis);
  const hours = safeMillis / 3_600_000;
  if (hours >= 10) return `${Math.round(hours).toLocaleString()}h`;
  if (hours >= 1) return `${hours.toFixed(1)}h`;
  return `${Math.round(safeMillis / 60_000)}m`;
}

/** Sessions that actually contribute a duration to the displayed total. */
export function countedPlaytimeSessions(
  playtime: Pick<PlaytimeSnapshot, "launches" | "sessionsWithoutDuration">,
): number {
  return Math.max(0, playtime.launches - playtime.sessionsWithoutDuration);
}

export function shortPath(path: string): string {
  const normalized = path.replaceAll("\\", "/");
  const parts = normalized.split("/").filter(Boolean);
  if (parts.length <= 3) return path;
  return `…/${parts.slice(-3).join("/")}`;
}

export function errorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message.replace(/^Error:\s*/i, "").trim() || "Something went wrong.";
}

export function cleanUserMessage(message: string): string {
  return message
    .replace(/\.\s+Error:\s*/gi, ": ")
    .replace(/(^|[!?]\s+)Error:\s*/gi, "$1")
    .trim();
}

export function localDateStamp(date = new Date()): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * Shortens text to a character budget without cutting a character in half.
 *
 * A plain `slice` counts UTF-16 units, so it can land between the two halves of a surrogate pair
 * and leave a lone surrogate that renders as a replacement box. Anything outside the basic
 * multilingual plane is a pair — emoji, and the rarer CJK ranges — and engine detail can carry a
 * path with the player's own name in it.
 */
function clampToCharacters(text: string, limit: number): string {
  const characters = Array.from(text);
  if (characters.length <= limit) return text;
  return `${characters.slice(0, limit - 1).join("")}…`;
}

export function failedRunSummary(detail?: string): string {
  const firstLine = detail
    ?.split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean);
  if (!firstLine) return "Starsector closed with an error. Support evidence was saved.";
  return `Starsector closed with an error: ${clampToCharacters(firstLine, 360)} The support evidence has full details.`;
}

const savedDateFormatter = new Intl.DateTimeFormat(undefined, {
  day: "numeric",
  month: "short",
  year: "numeric",
});

export function formatSavedAt(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : savedDateFormatter.format(date);
}

export function friendlyPlatform(platform: DesktopSnapshot["platform"]): string {
  return { mac: "macOS", windows: "Windows", linux: "Linux", other: "Desktop" }[platform];
}

export function maximumUiScale(resolution: string): number | null {
  const match = /^(\d+)x(\d+)$/.exec(resolution);
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (width <= 0 || height <= 0) return null;
  return Math.max(1, Math.floor(Math.min(height / 768, width / 1280) * 20) / 20);
}
