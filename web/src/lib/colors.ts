import type { Severity } from '@/generated/models'
import { t } from '@/i18n'

export const rulePalette = [
  '#3b82f6', '#f59e0b', '#a855f7', '#14b8a6', '#ec4899', '#84cc16', '#f97316', '#06b6d4', '#8b5cf6', '#eab308',
  '#0ea5e9', '#d946ef',
]

export function ruleColor(index: number): string {
  return rulePalette[((index % rulePalette.length) + rulePalette.length) % rulePalette.length]
}

export const severityColor: Record<Severity, string> = {
  info: '#0ea5e9',
  warning: '#f59e0b',
  error: '#ef4444',
}

export const severityRank: Record<Severity, number> = { info: 0, warning: 1, error: 2 }

export type ChangeKind = 'created' | 'deleted' | 'geometry' | 'tags'

export const changeKindColor: Record<ChangeKind, string> = {
  created: '#16a34a',
  deleted: '#dc2626',
  geometry: '#9333ea',
  tags: '#d97706',
}

export const changeKindLabel: Record<ChangeKind, string> = {
  get created() {
    return t('kind.created')
  },
  get deleted() {
    return t('kind.deleted')
  },
  get geometry() {
    return t('kind.geometry')
  },
  get tags() {
    return t('kind.tags')
  },
}

export const accent = '#2563eb'
export const contextColor = '#64748b'
