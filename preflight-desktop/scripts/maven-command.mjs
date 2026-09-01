import { existsSync } from "node:fs";
import { join } from "node:path";

export function mavenInvocation({
  platform = process.platform,
  environment = process.env,
  repositoryRoot,
  fileExists = existsSync,
}) {
  if (platform === "win32") {
    const wrapper = join(repositoryRoot, "mvnw.cmd");
    return {
      command: environment.ComSpec ?? "cmd.exe",
      argsPrefix: ["/d", "/s", "/c", fileExists(wrapper) ? "mvnw.cmd" : "mvn.cmd"],
    };
  }

  const wrapper = join(repositoryRoot, "mvnw");
  return fileExists(wrapper)
    ? { command: wrapper, argsPrefix: [] }
    : { command: "mvn", argsPrefix: [] };
}
