// Playwright e2e configuration. The tests are pure API tests (request fixture)
// against the running compose stack: mediator on :9070 (self-signed TLS) and
// DHIS2 behind the reverse proxy on :8080.
const { defineConfig } = require("@playwright/test");
const fs = require("fs");
const path = require("path");

// Load the repo's .env (the same file docker compose reads) so the tests see
// the API keys and credentials without the shell having to export them.
// Real environment variables still win over the file.
const envFile = path.join(__dirname, ".env");
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, "utf8").split("\n")) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
    if (match && !line.trim().startsWith("#") && process.env[match[1]] === undefined) {
      process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, "");
    }
  }
}

module.exports = defineConfig({
  testDir: "./tests",
  // Optional cleanup of the women the suite creates: `yarn test:e2e:wipe`.
  globalTeardown: require.resolve("./tests/global-teardown"),
  // The suite shares one DHIS2 state on purpose (idempotency is part of what we
  // test), so run serially and don't retry — a retry could mask a duplicate.
  workers: 1,
  retries: 0,
  timeout: 120000,
  expect: { timeout: 30000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    ignoreHTTPSErrors: true, // demo mediator cert is self-signed
  },
});
