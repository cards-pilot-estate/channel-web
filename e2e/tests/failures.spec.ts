import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { accessible, freshMobile, freshPan, lastCode, pinBureau } from './helpers'
import { completeApplication, signIn } from './flows'

// WG.10: what the customer sees when things go wrong (docs/channels/web-channel.md §3.8).

test('OTP lockout: wrong codes count down, the third voids the code', async ({ page }) => {
  const mobile = freshMobile()
  await page.goto('/sign-in')
  await page.getByLabel('Mobile number').fill(mobile)
  await page.getByRole('button', { name: 'Send code' }).click()
  const right = await lastCode(mobile)
  const wrong = right === '000000' ? '111111' : '000000'
  const code = page.getByLabel('Security code')
  for (const remaining of [2, 1]) {
    await code.fill(wrong)
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByRole('alert')).toContainText(`You can try ${remaining} more times`)
  }
  await code.fill(wrong)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByRole('alert')).toContainText('Send a new code')
  await code.fill(right)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByRole('alert')).toContainText('Send a new code')
  await accessible(page, 'locked code')
})

test('429: a new code too soon says when to try again', async ({ page }) => {
  const mobile = freshMobile()
  await page.goto('/sign-in')
  await page.getByLabel('Mobile number').fill(mobile)
  await page.getByRole('button', { name: 'Send code' }).click()
  await page.getByText('Not received a text message?').click()
  await page.getByRole('button', { name: 'Send a new code' }).click()
  await expect(page.getByRole('alert')).toContainText(/Too many attempts\. You can try again in \d+ seconds/)
  await accessible(page, 'resend too soon')
})

test('duplicate: a second application for a PAN in flight is refused politely', async ({ page, browser }) => {
  const pan = freshPan()
  await pinBureau(pan, 'THIN_FILE')   // stays under review, so the PAN stays in flight
  await signIn(page, freshMobile())
  await completeApplication(page, pan)
  await page.getByRole('button', { name: 'Accept and apply' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Application received')

  const second = await browser.newContext({ ...test.info().project.use })
  const other = await second.newPage()
  await signIn(other, freshMobile())
  await completeApplication(other, pan)
  await other.getByRole('button', { name: 'Accept and apply' }).click()
  await expect(other.getByRole('alert')).toContainText('You already have an application in progress')
  await accessible(other, 'duplicate')
  await second.close()
})

test('timeout: a warning at 13 minutes, then signed out with answers kept', async ({ page }) => {
  await page.clock.install()
  await signIn(page, freshMobile())
  await page.clock.fastForward('13:01')
  const dialog = page.getByRole('dialog', { name: 'You will be signed out soon' })
  await expect(dialog).toBeVisible()
  await accessible(page, 'timeout warning')
  await dialog.getByRole('button', { name: 'Stay signed in' }).click()
  await expect(dialog).toBeHidden()
  await page.clock.fastForward('15:01')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('You have been signed out')
  await expect(page.getByText('Your answers have been saved for 30 days')).toBeVisible()
  await page.goto('/apply')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Enter your mobile number')
})

// Disruptive: scales the platform down for a minute. Run only where that is acceptable (dev):
//   E2E_DISRUPTIVE=1 npx playwright test failures
test('platform down: submitting says the service is busy and keeps the answers', async ({ page }) => {
  test.skip(!process.env.E2E_DISRUPTIVE, 'set E2E_DISRUPTIVE=1 to scale the platform down')
  test.setTimeout(300_000)
  const env = process.env.E2E_ENV ?? 'dev'
  const pan = freshPan()
  await pinBureau(pan, 'HIGH_VALUE')
  await signIn(page, freshMobile())
  await completeApplication(page, pan)
  execFileSync('kubectl', ['-n', `cards-${env}`, 'scale', 'deploy/onboarding-service', '--replicas=0'])
  try {
    execFileSync('kubectl', ['-n', `cards-${env}`, 'wait', '--for=delete', 'pod', '-l', 'app.kubernetes.io/name=onboarding-service', '--timeout=90s'])
    await page.getByRole('button', { name: 'Accept and apply' }).click()
    await expect(page.getByRole('alert')).toContainText(/busy right now|not available/)
    await accessible(page, 'platform down')
  } finally {
    execFileSync('kubectl', ['-n', `cards-${env}`, 'scale', 'deploy/onboarding-service', '--replicas=1'])
    execFileSync('kubectl', ['-n', `cards-${env}`, 'rollout', 'status', 'deploy/onboarding-service', '--timeout=240s'])
  }
  await page.getByRole('button', { name: 'Accept and apply' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Application received', { timeout: 30_000 })
})
