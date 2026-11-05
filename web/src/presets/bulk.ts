import type { BBox, HighlightEvaluation, OsmRef, PresetBulkApplyRequest, ScanIssue } from '@/generated/models'
import { formatNumber, t } from '@/i18n'

export const MAX_BULK_REFS = 5000
const CHUNK_W = 0.08
const CHUNK_H = 0.05
export const MAX_CHUNKS = 16

export function refKey(r: OsmRef): string {
  return `${r.type[0]}${r.id}`
}

export function uniqueRefs(refs: OsmRef[]): OsmRef[] {
  const seen = new Set<string>()
  const out: OsmRef[] = []
  for (const r of refs) {
    const k = refKey(r)
    if (seen.has(k)) continue
    seen.add(k)
    out.push({ type: r.type, id: r.id })
  }
  return out
}

export function bboxChunks(b: BBox): BBox[] | null {
  const nx = Math.max(1, Math.ceil((b.east - b.west) / CHUNK_W - 1e-9))
  const ny = Math.max(1, Math.ceil((b.north - b.south) / CHUNK_H - 1e-9))
  if (nx * ny > MAX_CHUNKS) return null
  const w = (b.east - b.west) / nx
  const h = (b.north - b.south) / ny
  const out: BBox[] = []
  for (let i = 0; i < nx; i++)
    for (let j = 0; j < ny; j++) out.push({ west: b.west + i * w, east: b.west + (i + 1) * w, south: b.south + j * h, north: b.south + (j + 1) * h })
  return out
}

export function refsFromEvaluations(evals: HighlightEvaluation[], ruleIndexes?: number[] | null): OsmRef[] {
  const want = ruleIndexes && ruleIndexes.length ? new Set(ruleIndexes) : null
  const refs: OsmRef[] = []
  for (const e of evals) for (const f of e.features) if (!want || f.style.rules.some((r) => want.has(r))) refs.push(f.ref)
  return uniqueRefs(refs)
}

export function refsFromIssues(issues: ScanIssue[], picked: Iterable<number>): OsmRef[] {
  const ids = new Set(picked)
  return uniqueRefs(issues.filter((i) => ids.has(i.id)).map((i) => i.ref))
}

export interface BulkInput {
  presetId: string
  refs: OsmRef[]
  dryRun: boolean
  title?: string | null
  area?: string | null
  locale?: string | null
  source?: string | null
}

export function buildBulkRequest(input: BulkInput): PresetBulkApplyRequest {
  const refs = uniqueRefs(input.refs)
  if (!input.presetId) throw new Error(t('bulk.pick'))
  if (!refs.length) throw new Error(t('bulk.noRefs'))
  if (refs.length > MAX_BULK_REFS) throw new Error(t('bulk.tooMany', { max: formatNumber(MAX_BULK_REFS), count: formatNumber(refs.length) }))
  const title = input.title?.trim() || null
  return {
    presetId: input.presetId,
    refs,
    dryRun: input.dryRun,
    title: input.dryRun ? null : title,
    reason: input.source ? t('bulk.reason', { source: input.source }) : null,
    area: input.area ?? null,
    locale: input.locale ?? null,
  }
}
