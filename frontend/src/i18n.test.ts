import { describe, expect, it } from 'vitest'
import { catalogues } from './i18n'

describe('message catalogues', () => {
  it('Hindi has every English key, and no empty UI string except the legal note', () => {
    const en = Object.keys(catalogues['en-IN']).sort()
    const hi = Object.keys(catalogues['hi-IN']).sort()
    expect(hi).toEqual(en)
    for (const [k, v] of Object.entries(catalogues['hi-IN'])) {
      if (k !== 'privacy.legalNote') expect(v.trim(), k).not.toBe('')
    }
  })
  it('placeholders match between languages', () => {
    const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join()
    for (const [k, v] of Object.entries(catalogues['en-IN'])) {
      expect(vars((catalogues['hi-IN'] as Record<string, string>)[k]), k).toBe(vars(v))
    }
  })
})
