import { afterEach, describe, expect, it } from 'vitest'
import { en } from './en'
import { cs } from './cs'
import { getLocale, LOCALE_KEY, setLocale, t, tn, translate } from './index'
import { commonCs, commonEn } from './messages/common'
import { draftsCs, draftsEn } from './messages/drafts'
import { editorCs, editorEn } from './messages/editor'
import { mapCs, mapEn } from './messages/map'
import { presetsCs, presetsEn } from './messages/presets'
import { remoteCs, remoteEn } from './messages/remote'
import { reviewCs, reviewEn } from './messages/review'
import { rulesCs, rulesEn } from './messages/rules'
import { settingsCs, settingsEn } from './messages/settings'
import { shellCs, shellEn } from './messages/shell'
import { topCs, topEn } from './messages/top'
import { validateCs, validateEn } from './messages/validate'

const areas: [string, object, object][] = [
  ['common', commonEn, commonCs],
  ['drafts', draftsEn, draftsCs],
  ['editor', editorEn, editorCs],
  ['map', mapEn, mapCs],
  ['presets', presetsEn, presetsCs],
  ['remote', remoteEn, remoteCs],
  ['review', reviewEn, reviewCs],
  ['rules', rulesEn, rulesCs],
  ['settings', settingsEn, settingsCs],
  ['shell', shellEn, shellCs],
  ['top', topEn, topCs],
  ['validate', validateEn, validateCs],
]

function shape(o: object): string[] {
  return Object.entries(o)
    .map(([k, v]) => (typeof v === 'string' ? k : `${k}#plural`))
    .sort()
}

function placeholders(v: unknown): string[] {
  const all = typeof v === 'string' ? [v] : Object.values(v as Record<string, string>)
  return [...new Set(all.flatMap((s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]!)))].sort()
}

describe('i18n', () => {
  afterEach(() => setLocale('en'))

  it('has the same keys in cs and en', () => {
    expect(shape(cs)).toEqual(shape(en))
  })

  it('has no key defined in two message files', () => {
    const total = areas.reduce((sum, [, e]) => sum + Object.keys(e).length, 0)
    expect(total).toBe(Object.keys(en).length)
    for (const [name, e, c] of areas) expect(shape(c), name).toEqual(shape(e))
  })

  it('uses the same placeholders in cs and en', () => {
    const csMap = cs as Record<string, unknown>
    for (const [k, v] of Object.entries(en)) {
      const want = placeholders(v).filter((p) => p !== 'n')
      const got = placeholders(csMap[k]).filter((p) => p !== 'n')
      expect(got, k).toEqual(want)
    }
  })

  it('has every plural with an other form and no empty text', () => {
    for (const cat of [en, cs] as Record<string, unknown>[]) {
      for (const [k, v] of Object.entries(cat)) {
        if (typeof v === 'string') expect(v.length, k).toBeGreaterThan(0)
        else expect((v as { other?: string }).other, k).toBeTruthy()
      }
    }
  })

  it('interpolates variables', () => {
    setLocale('en')
    expect(t('library.imported', { path: 'presets/a.json' })).toBe('Imported as presets/a.json')
    expect(t('draw.hint', { name: 'Bench' })).toBe('Drawing a new “Bench”')
    expect(translate('en', 'missing.key.{x}', { x: 1 })).toBe('missing.key.1')
    expect(translate('cs', 'library.extends', {})).toBe('rozšiřuje {id}')
  })

  it('selects Czech plural forms', () => {
    setLocale('cs')
    expect(tn('common.changes', 1)).toBe('1 změna')
    expect(tn('common.changes', 2)).toBe('2 změny')
    expect(tn('common.changes', 4)).toBe('4 změny')
    expect(tn('common.changes', 5)).toBe('5 změn')
    expect(tn('common.changes', 0)).toBe('0 změn')
    expect(tn('common.changes', 1.5)).toBe('1,5 změny')
    expect(tn('common.changes', 12345)).toBe('12 345 změn')
  })

  it('selects English plural forms', () => {
    setLocale('en')
    expect(tn('common.changes', 1)).toBe('1 change')
    expect(tn('common.changes', 2)).toBe('2 changes')
    expect(tn('common.changes', 1234)).toBe('1,234 changes')
  })

  it('switches locale and persists it', () => {
    setLocale('cs')
    expect(getLocale()).toBe('cs')
    expect(t('common.cancel')).toBe('Zrušit')
    expect(document.documentElement.lang).toBe('cs')
    expect(localStorage.getItem(LOCALE_KEY)).toBe('cs')
    setLocale('en')
    expect(t('common.cancel')).toBe('Cancel')
  })
})
