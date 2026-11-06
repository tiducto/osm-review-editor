import type { Change, ChangeLink, ChangeStatus, Review } from '@/generated/models'
import type { ChangeKind } from './colors'
import { t, tn } from '@/i18n'

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

export function isUntaggedNewNode(c: Change, tags: Record<string, string> = c.newTags): boolean {
  return c.action === 'create' && c.ref.type === 'node' && Object.keys(tags).length === 0
}

export interface OrphanRisk {
  pending: ChangeLink[]
  blocked: ChangeLink[]
}

export function orphanRisk(c: Change, tags: Record<string, string> = c.newTags): OrphanRisk | null {
  if (!isUntaggedNewNode(c, tags)) return null
  const owners = c.requiredBy.filter((l) => l.action !== 'delete')
  if (owners.some((l) => l.status === 'approved' || l.status === 'uploaded')) return null
  return { pending: owners.filter((l) => l.status === 'pending'), blocked: owners.filter((l) => l.status !== 'pending') }
}

export function isDecided(c: Change): boolean {
  return c.status !== 'pending' && c.status !== 'conflict'
}

export function geometrySummaryText(s: string | null | undefined): string {
  if (!s) return ''
  if (s === 'deleted') return t('shell.geom.deleted')
  if (s === 'deleted if unused') return t('shell.geom.deletedIfUnused')
  if (s === 'new node') return t('shell.geom.newNode')
  let m = /^new way, (\d+) nodes$/.exec(s)
  if (m) return tn('shell.geom.newWay', Number(m[1]))
  m = /^new relation, (\d+) members$/.exec(s)
  if (m) return tn('shell.geom.newRelation', Number(m[1]))
  return s
    .replace(/moved (\d+) m/g, (_, d: string) => t('shell.geom.movedBy', { d }))
    .replace(/\bmoved\b/g, () => t('shell.geom.moved'))
    .replace(/nodes (\d+) → (\d+)/g, (_, a: string, b: string) => t('shell.geom.nodes', { a, b }))
    .replace(/members (\d+) → (\d+)/g, (_, a: string, b: string) => t('shell.geom.members', { a, b }))
    .replace(/(\d+) role changes?/g, (_, n: string) => tn('shell.geom.roleChanges', Number(n)))
}
