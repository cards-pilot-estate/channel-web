import { defineConfig, devices } from '@playwright/test'

// End to end through the real chain on the laptop cluster: Traefik -> channel-web -> API gateway ->
// otp-service and onboarding-service. Serial: personas pin global simulator state.
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'report' }]],
  globalSetup: './global-setup.ts',
  use: {
    baseURL: process.env.WEB_URL ?? 'https://web.dev.localtest.me',
    // The laptop's local CA (make tls in the platform repo) is not in Playwright's browser store.
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
})
