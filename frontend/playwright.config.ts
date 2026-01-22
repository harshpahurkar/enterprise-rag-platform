import { defineConfig, devices } from '@playwright/test'

// Runs against the full stack (Spring Boot serving the built SPA), so there is no webServer block.
export default defineConfig({
  testDir: './tests',
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
