import { defineConfig } from "@playwright/test";

export default defineConfig({
  testMatch:
    process.env.STUDIO_TEST_LANGFUSE === "true"
      ? "**/langfuse.spec.ts"
      : process.env.STUDIO_TEST_OIDC === "true"
      ? "**/oidc.spec.ts"
      : "**/workspace.spec.ts",
  testDir: "./tests",
  timeout: 60000,
  workers: 1,
  retries: 0,
  use: {
    baseURL: process.env.STUDIO_TEST_URL || "http://127.0.0.1:13000",
    browserName: "chromium",
  },
});
