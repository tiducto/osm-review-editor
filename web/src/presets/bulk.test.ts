import { describe, expect, it } from 'vitest'
import type { HighlightEvaluation, ScanIssue } from '@/generated/models'
import { bboxChunks, buildBulkRequest, MAX_BULK_REFS, refsFromEvaluations, refsFromIssues } from './bulk'

const ev = (features: [string, number, number[]][]): HighlightEvaluation =>
  ({
    path: 'highlights/x.mapcss',
    hash: 'h',
    bbox: { west: 0, south: 0, east: 1, north: 1 },
    tile: null,
    truncated: false,
    candidates: 0,
    tookMs: 1,
    cached: false,
    features: features.map(([type, id, rules]) => ({ ref: { type, id }, style: { properties: {}, classes: [], rules } })),
  }) as unknown as HighlightEvaluation

describe('bulk apply requests', () => {
  it('collects unique refs from highlight evaluations, optionally per rule', () => {
    const evals = [ev([['way', 1, [0]], ['way', 2, [1]], ['node', 1, [0, 1]]]), ev([['way', 1, [0]]])]
    expect(refsFromEvaluations(evals)).toEqual([
      { type: 'way', id: 1 },
      { type: 'way', id: 2 },
      { type: 'node', id: 1 },
    ])
    expect(refsFromEvaluations(evals, [1])).toEqual([
      { type: 'way', id: 2 },
      { type: 'node', id: 1 },
    ])
  })

  it('collects refs of picked scan issues', () => {
    const issues = [
      { id: 1, ref: { type: 'way', id: 5 } },
      { id: 2, ref: { type: 'way', id: 5 } },
      { id: 3, ref: { type: 'node', id: 7 } },
    ] as unknown as ScanIssue[]
    expect(refsFromIssues(issues, [1, 2])).toEqual([{ type: 'way', id: 5 }])
  })

  it('builds dry runs and draft requests', () => {
    const refs = [
      { type: 'way' as const, id: 1 },
      { type: 'way' as const, id: 1 },
      { type: 'way' as const, id: 2 },
    ]
    expect(buildBulkRequest({ presetId: 'highway/footway', refs, dryRun: true, title: 'x', area: 'Opava', locale: 'cs' })).toEqual({
      presetId: 'highway/footway',
      refs: [
        { type: 'way', id: 1 },
        { type: 'way', id: 2 },
      ],
      dryRun: true,
      title: null,
      reason: null,
      area: 'Opava',
      locale: 'cs',
    })
    const draft = buildBulkRequest({ presetId: 'p', refs, dryRun: false, title: '  Chodníky  ', source: 'highlights/sidewalks.mapcss' })
    expect(draft.title).toBe('Chodníky')
    expect(draft.dryRun).toBe(false)
    expect(draft.reason).toBe('Preset applied to highlights/sidewalks.mapcss')
    expect(() => buildBulkRequest({ presetId: 'p', refs: [], dryRun: true })).toThrow(/No features/)
    expect(() => buildBulkRequest({ presetId: '', refs, dryRun: true })).toThrow(/preset/)
    const many = Array.from({ length: MAX_BULK_REFS + 1 }, (_, i) => ({ type: 'node' as const, id: i + 1 }))
    expect(() => buildBulkRequest({ presetId: 'p', refs: many, dryRun: true })).toThrow(/At most/)
  })

  it('splits large areas into highlight-sized chunks', () => {
    expect(bboxChunks({ west: 17.87, south: 49.93, east: 17.9, north: 49.95 })).toHaveLength(1)
    const c = bboxChunks({ west: 17.8, south: 49.9, east: 18.0, north: 50.0 })!
    expect(c).toHaveLength(6)
    expect(c[5].east).toBeCloseTo(18.0)
    expect(bboxChunks({ west: 12, south: 48, east: 19, north: 51 })).toBeNull()
  })
})
