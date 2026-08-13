import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          PUBLIC_ORIGIN: "https://intake.test",
          REPORT_SIGNING_KEY: "test-only-signing-key-with-at-least-thirty-two-bytes",
        },
      },
    }),
  ],
});
