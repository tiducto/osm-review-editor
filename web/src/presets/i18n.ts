import { translate } from '@/i18n'
import type { Locale } from './types'

export function tr(locale: Locale, key: string, vars?: Record<string, string | number>): string {
  return translate(locale, key, vars)
}
