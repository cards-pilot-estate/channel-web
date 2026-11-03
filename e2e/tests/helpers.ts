import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'
import { createHash, randomInt } from 'node:crypto'

const OTP = 'http://127.0.0.1:18095'
const SIM = 'http://127.0.0.1:18096'

export function freshPan(): string {
  const letters = () => String.fromCharCode(65 + randomInt(26))
  return letters() + letters() + letters() + 'P' + letters() + String(randomInt(10000)).padStart(4, '0') + letters()
}

export function freshMobile(): string {
  return '9' + String(randomInt(1_000_000_000)).padStart(9, '0')
}

/** Persona pin: the bureau simulator answers this scenario for this PAN (docs/channels/web-channel.md §3.5.3). */
export async function pinBureau(pan: string, scenario: string) {
  const hash = createHash('sha256').update(pan.toUpperCase().trim()).digest('hex')
  const res = await fetch(`${SIM}/api/v1/bureau/admin/scenarios/${hash}?scenario=${scenario}`, { method: 'POST' })
  expect(res.ok, 'pin ' + scenario).toBeTruthy()
}

export async function lastCode(mobile: string): Promise<string> {
  for (let i = 0; i < 20; i++) {
    const res = await fetch(`${OTP}/sim-admin/v1/test/last-code?msisdn=${encodeURIComponent('+91' + mobile)}`)
    if (res.ok) return (await res.json()).code
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error('no code arrived')
}

/** WCAG 2.2 AA automated checks on the current page: no violations allowed. */
export async function accessible(page: Page, name: string) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze()
  expect(results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`), 'axe on ' + name).toEqual([])
}
