import type { DesktopSnapshot } from "./types";

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

export function failedRunSummary(detail?: string): string {
  const firstLine = detail
    ?.split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean);
  if (!firstLine) return "Starsector closed with an error. Support evidence was saved.";
  const summary = firstLine.length > 360 ? `${firstLine.slice(0, 357)}…` : firstLine;
  return `Starsector closed with an error: ${summary} The support evidence has full details.`;
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
