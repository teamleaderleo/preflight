import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { execFileSync } from "node:child_process";

function sourceRevision(): string {
  const supplied = process.env.PREFLIGHT_SOURCE_REVISION?.trim();
  if (supplied && /^[0-9a-f]{7,40}$/i.test(supplied)) return supplied.slice(0, 8).toLowerCase();
  try {
    return execFileSync("git", ["rev-parse", "--short=8", "HEAD"], { encoding: "utf8" }).trim();
  } catch {
    return "unknown";
  }
}

export default defineConfig({
  plugins: [react()],
  define: {
    __PREFLIGHT_SOURCE_REVISION__: JSON.stringify(sourceRevision()),
  },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: "127.0.0.1",
    watch: {
      ignored: ["**/src-tauri/target/**", "**/dist/**"],
    },
  },
  envPrefix: ["VITE_", "TAURI_"],
  build: {
    target: process.env.TAURI_ENV_PLATFORM === "windows" ? "chrome105" : "safari13",
    minify: process.env.TAURI_ENV_DEBUG ? false : "oxc",
    sourcemap: Boolean(process.env.TAURI_ENV_DEBUG),
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
    globals: true,
    // Both hosted runners and development machines over-report useful jsdom parallelism. Two
    // workers keep the App integration suites responsive without turning a test run into a CPU
    // stress test. The timeout is a harness hang ceiling; browser checks own product latency.
    maxWorkers: 2,
    testTimeout: 10_000,
  },
});
