import type { Change, ChangeStatus, Review } from '@/generated/models'
import type { ChangeKind } from './colors'
import { t } from '@/i18n'

export function changeKind(c: Change): ChangeKind {
  if (c.action === 'create') return 'created'
  if (c.action === 'delete') return 'deleted'
  if (c.newGeom) return 'geometry'
  return 'tags'
}

export function lastReview(c: Change, decision?: Review['decision']): Review | null {
  const list = decision ? c.reviews.filter((r) => r.decision === decision) : c.reviews
  return list.length ? list[list.length - 1] : null
}

export const statusLabel: Record<ChangeStatus, string> = {
  get pending() {
    return t('status.pending')
  },
  get approved() {
    return t('status.approved')
  },
  get rejected() {
    return t('status.rejected')
  },
  get conflict() {
    return t('status.conflict')
  },
  get uploaded() {
    return t('status.uploaded')
  },
}

export function isDecided(c: Change): boolean {
  return c.status !== 'pending' && c.status !== 'conflict'
}
