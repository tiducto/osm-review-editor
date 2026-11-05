import { formatNumber, getLocale, t, tn, type Locale, type PluralKey } from '@/i18n'

const rtfs = new Map<Locale, Intl.RelativeTimeFormat>()
function rtf(l: Locale): Intl.RelativeTimeFormat {
  let f = rtfs.get(l)
  if (!f) {
    f = new Intl.RelativeTimeFormat(l, { numeric: 'auto' })
    rtfs.set(l, f)
  }
  return f
}

const units: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 31_536_000],
  ['month', 2_592_000],
  ['week', 604_800],
  ['day', 86_400],
  ['hour', 3_600],
  ['minute', 60],
]

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return iso
  const secs = (ts - Date.now()) / 1000
  for (const [unit, s] of units) {
    if (Math.abs(secs) >= s) return rtf(getLocale()).format(Math.round(secs / s), unit)
  }
  return t('common.justNow')
}

export function dateTime(iso: string | null | undefined, options?: Intl.DateTimeFormatOptions): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  return Number.isNaN(ts) ? iso : new Date(ts).toLocaleString(getLocale(), options)
}

export function number(n: number, options?: Intl.NumberFormatOptions): string {
  return formatNumber(n, options)
}

export function plural(n: number, key: PluralKey, vars?: Record<string, string | number>): string {
  return tn(key, n, vars)
}
