import { existsSync } from "node:fs";
import { join } from "node:path";

export function sevenZipCommand({
  platform = process.platform,
  environment = process.env,
  fileExists = existsSync,
} = {}) {
  if (platform !== "win32") return "7z";

  const roots = [environment.ProgramW6432, environment.ProgramFiles, environment["ProgramFiles(x86)"]]
    .filter((value, index, values) => value && values.indexOf(value) === index);
  for (const root of roots) {
    const candidate = join(root, "7-Zip", "7z.exe");
    if (fileExists(candidate)) return candidate;
  }
  return "7z";
}
