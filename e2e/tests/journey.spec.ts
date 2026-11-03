import { expect, test } from '@playwright/test'
import { accessible, freshMobile, freshPan, lastCode, pinBureau } from './helpers'

type Persona = { id: string; scenario: string; income: string; obligation: string; status: RegExp }

// docs/channels/web-channel.md §3.5.2
const PERSONAS: Persona[] = [
  { id: 'P01 approval', scenario: 'HIGH_VALUE', income: '150000', obligation: '10000', status: /Approved/ },
  { id: 'P03 decline on debt-to-income', scenario: 'HIGH_VALUE', income: '100000', obligation: '60000', status: /Declined/ },
  { id: 'P05 thin-file referral', scenario: 'THIN_FILE', income: '150000', obligation: '10000', status: /Under review/ },
]

for (const p of PERSONAS) {
  test(`${p.id}: a customer applies on the web and sees the outcome`, async ({ page }) => {
    const pan = freshPan()
    const mobile = freshMobile()
    await pinBureau(pan, p.scenario)

    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Apply for a credit card')
    await accessible(page, 'start')
    await page.getByRole('button', { name: /Start now/ }).click()

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Enter your mobile number')
    await page.getByLabel('Mobile number').fill(mobile)
    await accessible(page, 'sign-in')
    await page.getByRole('button', { name: 'Send code' }).click()

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Check your phone')
    await expect(page.getByText(`ending ${mobile.slice(-4)}`)).toBeVisible()
    await page.getByLabel('Security code').fill(await lastCode(mobile))
    await accessible(page, 'code')
    await page.getByRole('button', { name: 'Continue' }).click()

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('How we use your information')
    await accessible(page, 'privacy notice')
    await page.getByLabel(/assess and process this application/).check()
    await page.getByLabel(/credit history/).check()
    await page.getByRole('button', { name: 'Continue' }).click()

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Your application')
    await accessible(page, 'task list')

    await page.getByRole('link', { name: 'Choose your card' }).click()
    await page.getByLabel('Gold').check()
    await page.getByLabel(/I have read the Key Fact Statement/).check()
    await accessible(page, 'card')
    await page.getByRole('button', { name: 'Save and continue' }).click()

    await page.getByRole('link', { name: 'About you' }).click()
    await page.getByLabel('Full name').fill('Asha Verma')
    await page.getByLabel('PAN').fill(pan)
    await page.getByLabel('Day').fill('12')
    await page.getByLabel('Month').fill('4')
    await page.getByLabel('Year').fill('1990')
    await page.getByLabel('Email address').fill('asha.verma@example.com')
    await accessible(page, 'about you')
    await page.getByRole('button', { name: 'Save and continue' }).click()

    await page.getByRole('link', { name: 'Your address' }).click()
    await page.getByLabel('Address line 1').fill('12 Residency Road')
    await page.getByLabel('Town or city').fill('Bengaluru')
    await page.getByLabel('State').fill('Karnataka')
    await page.getByLabel('PIN code').fill('560025')
    await page.getByRole('button', { name: 'Save and continue' }).click()

    await page.getByRole('link', { name: 'Your work and income' }).click()
    await page.getByLabel('Salaried').check()
    await page.getByLabel('Employer or business name').fill('Acme Logistics')
    await page.getByLabel(/Monthly income/).fill(p.income)
    await page.getByLabel(/Monthly loan and card payments/).fill(p.obligation)
    await accessible(page, 'work')
    await page.getByRole('button', { name: 'Save and continue' }).click()

    await page.getByRole('button', { name: 'Check your answers and submit' }).click()
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Check your answers before you apply')
    await expect(page.getByText(pan)).toBeVisible()
    await accessible(page, 'check answers')
    await page.getByRole('button', { name: 'Accept and apply' }).click()

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Application received')
    await expect(page.locator('.panel-ref')).toHaveText(/^CC-\d{4}-\d{4}-[A-Z0-9]{6}$/)
    await expect(page.getByRole('status').filter({ hasText: p.status })).toBeVisible({ timeout: 45_000 })
    await accessible(page, 'confirmation')
  })
}

test('validation errors are summarised and linked to the fields', async ({ page }) => {
  await page.goto('/sign-in')
  await page.getByLabel('Mobile number').fill('12345')
  await page.getByRole('button', { name: 'Send code' }).click()
  const summary = page.getByRole('alert')
  await expect(summary).toContainText('There is a problem')
  await summary.getByRole('link').click()
  await expect(page.getByLabel('Mobile number')).toBeFocused()
  await accessible(page, 'sign-in with an error')
})

test('the service works in Hindi', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'हिंदी' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('क्रेडिट कार्ड के लिए आवेदन करें')
  await expect(page.locator('html')).toHaveAttribute('lang', 'hi-IN')
  await accessible(page, 'start in Hindi')
  await page.getByRole('button', { name: 'English' }).click()
})
