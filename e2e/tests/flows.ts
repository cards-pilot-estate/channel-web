import { expect, type Page } from '@playwright/test'
import { accessible, lastCode } from './helpers'

export async function signIn(page: Page, mobile: string) {
  await page.goto('/sign-in')
  await page.getByLabel('Mobile number').fill(mobile)
  await page.getByRole('button', { name: 'Send code' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Check your phone')
  await page.getByLabel('Security code').fill(await lastCode(mobile))
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('How we use your information')
}

export async function completeApplication(page: Page, pan: string, income = '150000', obligation = '10000') {
  await page.getByLabel(/assess and process this application/).check()
  await page.getByLabel(/credit history/).check()
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.getByRole('link', { name: 'Choose your card' }).click()
  await page.getByLabel('Gold').check()
  await page.getByLabel(/I have read the Key Fact Statement/).check()
  await page.getByRole('button', { name: 'Save and continue' }).click()
  await page.getByRole('link', { name: 'About you' }).click()
  await page.getByLabel('Full name').fill('Ravi Kumar')
  await page.getByLabel('PAN').fill(pan)
  await page.getByLabel('Day').fill('2')
  await page.getByLabel('Month').fill('11')
  await page.getByLabel('Year').fill('1988')
  await page.getByLabel('Email address').fill('ravi.kumar@example.com')
  await page.getByRole('button', { name: 'Save and continue' }).click()
  await page.getByRole('link', { name: 'Your address' }).click()
  await page.getByLabel('Address line 1').fill('4 MG Road')
  await page.getByLabel('Town or city').fill('Pune')
  await page.getByLabel('State').fill('Maharashtra')
  await page.getByLabel('PIN code').fill('411001')
  await page.getByRole('button', { name: 'Save and continue' }).click()
  await page.getByRole('link', { name: 'Your work and income' }).click()
  await page.getByLabel('Salaried').check()
  await page.getByLabel('Employer or business name').fill('Acme Logistics')
  await page.getByLabel(/Monthly income/).fill(income)
  await page.getByLabel(/Monthly loan and card payments/).fill(obligation)
  await page.getByRole('button', { name: 'Save and continue' }).click()
  await page.getByRole('button', { name: 'Check your answers and submit' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Check your answers before you apply')
  void accessible
}
