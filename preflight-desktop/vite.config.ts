import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
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
    // GitHub's hosted runner can report enough parallelism for several jsdom workers while only
    // sustaining one App integration worker at full speed. The result is slower than a serialized
    // run and makes one-second readiness assertions depend on neighboring test files. Local runs
    // keep their normal parallelism; CI gives the integration suite the CPU it was promised.
    maxWorkers: process.env.CI ? 1 : undefined,
  },
});
