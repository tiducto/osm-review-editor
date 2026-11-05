import { readonly, ref } from 'vue'
import { en } from './en'
import { cs, type Messages } from './cs'
import type { Locale, MessageValue, PluralForms } from './types'

export type { Locale, PluralForms } from './types'
export type { Messages } from './cs'

type En = typeof en
export type MessageKey = { [K in keyof En]: En[K] extends string ? K : never }[keyof En]
export type PluralKey = { [K in keyof En]: En[K] extends string ? never : K }[keyof En]
export type Vars = Record<string, string | number>

export const LOCALE_KEY = 'ore.locale'
const LEGACY_KEY = 'ore.presetLocale'
export const LOCALES: readonly Locale[] = ['cs', 'en']

const catalogs: Record<Locale, Messages> = { en, cs }

function isLocale(v: unknown): v is Locale {
  return v === 'cs' || v === 'en'
}

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function storageSet(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {}
}

function storageRemove(key: string) {
  try {
    localStorage.removeItem(key)
  } catch {}
}

export function detectLocale(): Locale {
  const stored = storageGet(LOCALE_KEY)
  if (isLocale(stored)) return stored
  const legacy = storageGet(LEGACY_KEY)
  if (isLocale(legacy)) {
    storageSet(LOCALE_KEY, legacy)
    storageRemove(LEGACY_KEY)
    return legacy
  }
  const nav = typeof navigator === 'undefined' ? '' : (navigator.language ?? '')
  return nav.toLowerCase().startsWith('cs') ? 'cs' : 'en'
}

const current = ref<Locale>(detectLocale())

function applyLang(l: Locale) {
  if (typeof document !== 'undefined') document.documentElement.lang = l
}
applyLang(current.value)

export const locale = readonly(current)

export function getLocale(): Locale {
  return current.value
}

export function setLocale(l: Locale) {
  if (!isLocale(l)) return
  current.value = l
  storageSet(LOCALE_KEY, l)
  applyLang(l)
}

function interpolate(s: string, vars?: Vars): string {
  if (!vars) return s
  return s.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m))
}

function lookup(l: Locale, key: string): MessageValue | undefined {
  return (catalogs[l] as Record<string, MessageValue | undefined>)[key] ?? (catalogs.en as Record<string, MessageValue | undefined>)[key]
}

const pluralRules = new Map<Locale, Intl.PluralRules>()
function pluralCategory(l: Locale, n: number): Intl.LDMLPluralRule {
  let r = pluralRules.get(l)
  if (!r) {
    r = new Intl.PluralRules(l)
    pluralRules.set(l, r)
  }
  return r.select(n)
}

function pickPlural(forms: PluralForms, l: Locale, n: number): string {
  return forms[pluralCategory(l, n)] ?? forms.other
}

export function formatNumber(n: number, options?: Intl.NumberFormatOptions, l: Locale = current.value): string {
  return n.toLocaleString(l, options)
}

export function translate(l: Locale, key: string, vars?: Vars): string {
  const v = lookup(l, key)
  if (v === undefined) return interpolate(key, vars)
  if (typeof v === 'string') return interpolate(v, vars)
  const n = typeof vars?.n === 'number' ? vars.n : Number(vars?.n ?? NaN)
  if (Number.isNaN(n)) return interpolate(v.other, vars)
  return interpolate(pickPlural(v, l, n), { ...vars, n: formatNumber(n, undefined, l) })
}

export function t(key: MessageKey, vars?: Vars): string {
  return translate(current.value, key, vars)
}

export function tn(key: PluralKey, n: number, vars?: Vars): string {
  return translate(current.value, key, { ...vars, n })
}

export function useI18n() {
  return { t, tn, locale, setLocale, formatNumber }
}
