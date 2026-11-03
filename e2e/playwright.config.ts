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
    baseURL: process.env.WEB_URL ?? 'http://web.dev.localtest.me',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
})
