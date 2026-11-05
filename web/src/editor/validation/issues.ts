import { mapCssChecks, mapCssParse } from 'osm-review-shared'
import type { IdAdapter, IdIssue } from '../id/adapter'
import type { IdEntity, IdNode, IdWay, LngLatTuple } from '../types'
import { GraphMapCssHost } from './mapcssHost'
import { t } from '@/i18n'

export type IssueSeverity = 'error' | 'warning' | 'suggestion' | 'info'

export interface TagEditJs {
  set?: Record<string, string>
  remove?: string[]
  replaceAll?: Record<string, string> | null
}

export interface EditIssueFix {
  id: string
  title: string
  disabled: string | null
}

export interface EditIssue {
  id: string
  source: 'id' | 'mapcss'
  type: string
  severity: IssueSeverity
  message: string
  group: string | null
  file: string | null
  line: number | null
  entityIds: string[]
  loc: LngLatTuple | null
  fixes: EditIssueFix[]
  tagEdit: TagEditJs | null
  deleteObject: boolean
}

export interface CheckSheet {
  path: string
  hash: string
  stylesheet: unknown
}

interface CheckResultJs {
  severity: 'error' | 'warning' | 'info'
  message: string
  group: string | null
  fix: TagEditJs | null
  deleteObject: boolean
  file: string
  line: number
}

export const SEVERITY_ORDER: Record<IssueSeverity, number> = { error: 0, warning: 1, info: 2, suggestion: 3 }

export function parseCheckSheet(path: string, hash: string, source: string): CheckSheet | null {
  const r = mapCssParse(source, path)
  if (!r.ok || !r.stylesheet || !r.hasChecks) return null
  return { path, hash, stylesheet: r.stylesheet }
}

export function describeTagEdit(edit: TagEditJs): string {
  const parts: string[] = []
  if (edit.replaceAll) parts.push(t('validation.replaceTags', { tags: Object.entries(edit.replaceAll).map(([k, v]) => `${k}=${v}`).join(', ') }))
  const removed = edit.remove ?? []
  const set = Object.entries(edit.set ?? {})
  if (removed.length) parts.push(t('validation.removeTags', { keys: removed.join(', ') }))
  if (set.length) parts.push(t('validation.setTags', { tags: set.map(([k, v]) => `${k}=${v}`).join(', ') }))
  return parts.join('; ') || t('validation.noChange')
}

export function isEmptyTagEdit(edit: TagEditJs | null | undefined): boolean {
  return !edit || (!edit.replaceAll && !(edit.remove ?? []).length && !Object.keys(edit.set ?? {}).length)
}

function entityLoc(graph: ReturnType<IdAdapter['graph']>, e: IdEntity): LngLatTuple | null {
  if (e.type === 'node') return (e as IdNode).loc
  if (e.type === 'way') {
    const nodes = (e as IdWay).nodes.map((id) => graph.hasEntity(id) as IdNode | undefined).filter((n): n is IdNode => !!n)
    if (!nodes.length) return null
    if (nodes.length > 2 && nodes[0].id === nodes[nodes.length - 1].id) {
      const pts = nodes.slice(1)
      return [pts.reduce((s, n) => s + n.loc[0], 0) / pts.length, pts.reduce((s, n) => s + n.loc[1], 0) / pts.length]
    }
    const a = nodes[Math.floor((nodes.length - 1) / 2)]
    const b = nodes[Math.min(nodes.length - 1, Math.floor((nodes.length - 1) / 2) + 1)]
    return [(a.loc[0] + b.loc[0]) / 2, (a.loc[1] + b.loc[1]) / 2]
  }
  for (const m of e.members) {
    const c = graph.hasEntity(m.id)
    if (c && c.type !== 'relation') return entityLoc(graph, c)
  }
  return null
}

export function runMapCssChecks(adapter: IdAdapter, sheets: CheckSheet[], ids: string[], ignored: Set<string> = new Set()): EditIssue[] {
  if (!sheets.length || !ids.length) return []
  const graph = adapter.graph()
  const host = new GraphMapCssHost({ graph, intersects: (b) => adapter.intersects(b) })
  const out: EditIssue[] = []
  const seen = new Set<string>()
  for (const id of ids) {
    const e = graph.hasEntity(id)
    if (!e) continue
    const el = host.element(e)
    for (const sheet of sheets) {
      let results: CheckResultJs[] = []
      try {
        results = mapCssChecks(sheet.stylesheet, el, host) as CheckResultJs[]
      } catch (err) {
        console.warn('MapCSS check failed', sheet.path, id, err)
        continue
      }
      for (const r of results) {
        const issueId = `mapcss:${r.file || sheet.path}:${r.line}:${id}:${r.message}`
        if (seen.has(issueId) || ignored.has(issueId)) continue
        seen.add(issueId)
        const fixes: EditIssueFix[] = []
        const tagEdit = isEmptyTagEdit(r.fix) ? null : r.fix
        if (tagEdit) fixes.push({ id: 'tags', title: t('validation.fix', { edit: describeTagEdit(tagEdit) }), disabled: null })
        if (r.deleteObject) fixes.push({ id: 'delete', title: t('validation.deleteFeature'), disabled: null })
        if (r.severity !== 'error') fixes.push({ id: 'ignore', title: t('validation.ignore'), disabled: null })
        out.push({
          id: issueId,
          source: 'mapcss',
          type: `${r.file || sheet.path}:${r.line}`,
          severity: r.severity,
          message: r.message,
          group: r.group ?? null,
          file: r.file || sheet.path,
          line: r.line,
          entityIds: [id],
          loc: entityLoc(graph, e),
          fixes,
          tagEdit,
          deleteObject: r.deleteObject,
        })
      }
    }
  }
  return out
}

export function fromIdIssue(i: IdIssue): EditIssue {
  return {
    id: `id:${i.id}`,
    source: 'id',
    type: i.subtype ? `${i.type}/${i.subtype}` : i.type,
    severity: i.severity,
    message: i.message,
    group: null,
    file: null,
    line: null,
    entityIds: i.entityIds,
    loc: i.loc,
    fixes: i.fixes,
    tagEdit: null,
    deleteObject: false,
  }
}

export function sortIssues(list: EditIssue[]): EditIssue[] {
  return [...list].sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] || a.message.localeCompare(b.message))
}

export async function collectIssues(adapter: IdAdapter, sheets: CheckSheet[], ignored: Set<string>): Promise<EditIssue[]> {
  await adapter.validate()
  const fromId = adapter.idIssues().map(fromIdIssue)
  const fromMapCss = runMapCssChecks(adapter, sheets, adapter.touchedIds(), ignored)
  return sortIssues([...fromId, ...fromMapCss])
}

export function applyIssueFix(adapter: IdAdapter, issue: EditIssue, fixId: string, ignored: Set<string>): boolean {
  if (issue.source === 'id') return adapter.applyIdFix(issue.id.slice(3), fixId)
  if (fixId === 'ignore') {
    ignored.add(issue.id)
    return true
  }
  const target = issue.entityIds[0]
  if (fixId === 'tags' && issue.tagEdit) return adapter.applyTagEdit(target, issue.tagEdit, t('edit.ann.fixed', { message: issue.message }))
  if (fixId === 'delete' && issue.deleteObject) return adapter.deleteEntities([target], t('edit.ann.deleted', { message: issue.message }))
  return false
}
