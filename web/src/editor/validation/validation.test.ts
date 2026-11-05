import { beforeAll, describe, expect, it } from 'vitest'
import { fixtureAdapter, primeId } from '@/test/idSetup'
import type { IdAdapter } from '../id/adapter'
import type { IdNode, IdWay, LngLatTuple } from '../types'
import { applyIssueFix, collectIssues, parseCheckSheet, type CheckSheet } from './issues'

beforeAll(primeId)

const M_PER_DEG_LAT = 111_320

function offset(p: LngLatTuple, east: number, north: number): LngLatTuple {
  return [p[0] + east / (M_PER_DEG_LAT * Math.cos((p[1] * Math.PI) / 180)), p[1] + north / M_PER_DEG_LAT]
}

function road(a: IdAdapter): { way: IdWay; a: IdNode; b: IdNode } {
  const g = a.graph()
  const ways = a.allEntities().filter((e): e is IdWay => e.type === 'way' && e.tags.highway === 'residential' && !e.isClosed())
  for (const w of ways) {
    for (let i = 1; i < w.nodes.length; i++) {
      const n1 = g.hasEntity(w.nodes[i - 1]) as IdNode | undefined
      const n2 = g.hasEntity(w.nodes[i]) as IdNode | undefined
      if (!n1 || !n2) continue
      const dx = (n2.loc[0] - n1.loc[0]) * M_PER_DEG_LAT * Math.cos((n1.loc[1] * Math.PI) / 180)
      const dy = (n2.loc[1] - n1.loc[1]) * M_PER_DEG_LAT
      if (Math.hypot(dx, dy) > 40 && a.isLoaded(n1.loc) && a.isLoaded(n2.loc)) return { way: w, a: n1, b: n2 }
    }
  }
  throw new Error('no road segment')
}

function across(seg: { a: IdNode; b: IdNode }, along: number, side: number): LngLatTuple {
  const c = Math.cos((seg.a.loc[1] * Math.PI) / 180)
  const dx = (seg.b.loc[0] - seg.a.loc[0]) * M_PER_DEG_LAT * c
  const dy = (seg.b.loc[1] - seg.a.loc[1]) * M_PER_DEG_LAT
  const len = Math.hypot(dx, dy)
  const mid = offset(seg.a.loc, (dx / len) * along, (dy / len) * along)
  return offset(mid, (-dy / len) * side, (dx / len) * side)
}

function drawLine(a: IdAdapter, from: LngLatTuple, to: LngLatTuple, tags: Record<string, string>): string {
  const id = a.startWay('line', { kind: 'none', loc: from })
  a.extendWay({ kind: 'none', loc: to })
  a.finishWay()
  a.setTags(id, tags)
  return id
}

describe('editor validation', () => {
  it('reports crossing_ways for a new road and fixes it by connecting', async () => {
    const a = fixtureAdapter()
    const seg = road(a)
    const id = drawLine(a, across(seg, 20, -6), across(seg, 20, 6), { highway: 'residential' })
    const issues = await collectIssues(a, [], new Set())
    const crossing = issues.find((i) => i.type.startsWith('crossing_ways') && i.entityIds.includes(id) && i.entityIds.includes(seg.way.id))
    expect(crossing).toBeTruthy()
    expect(crossing!.severity).toBe('warning')
    expect(crossing!.loc).not.toBeNull()
    const connect = crossing!.fixes.find((f) => /connect/i.test(f.title))
    expect(connect).toBeTruthy()
    const before = a.editCount()
    expect(applyIssueFix(a, crossing!, connect!.id, new Set())).toBe(true)
    const way = a.graph().entity(id) as IdWay
    expect(way.nodes.length).toBe(3)
    expect(seg.way.id && (a.graph().entity(seg.way.id) as IdWay).nodes).toContain(way.nodes[1])
    expect(a.editCount()).toBeGreaterThanOrEqual(before)
    const after = await collectIssues(a, [], new Set())
    expect(after.some((i) => i.id === crossing!.id)).toBe(false)
    a.undo()
    expect((a.graph().entity(id) as IdWay).nodes.length).toBe(2)
  })

  it('reports almost_junction for a road ending just short of another and extends it', async () => {
    const a = fixtureAdapter()
    const seg = road(a)
    const id = drawLine(a, across(seg, 20, 40), across(seg, 20, 2.5), { highway: 'residential' })
    const issues = await collectIssues(a, [], new Set())
    const almost = issues.find((i) => i.type.startsWith('almost_junction') && i.entityIds.includes(id))
    expect(almost).toBeTruthy()
    const fix = almost!.fixes.find((f) => f.id !== 'ignore' && !/ignore/i.test(f.title))
    expect(fix).toBeTruthy()
    expect(applyIssueFix(a, almost!, fix!.id, new Set())).toBe(true)
    const way = a.graph().entity(id) as IdWay
    const g = a.graph()
    const end = g.entity(way.nodes[way.nodes.length - 1]) as IdNode
    expect(g.parentWays(end).map((w) => w.id)).toContain(seg.way.id)
  })

  it('runs MapCSS checks on edited features with a tag fix that can be undone', async () => {
    const sheet = parseCheckSheet(
      'checks/buildings.validator.mapcss',
      'h',
      'way:closed[building][area=yes] { throwWarning: "Building with area=yes"; group: "Buildings"; fixRemove: "area"; }\nway[highway][!name] { throwOther: "Unnamed"; }',
    ) as CheckSheet
    expect(sheet).not.toBeNull()
    const a = fixtureAdapter()
    const building = a.allEntities().find((e): e is IdWay => e.type === 'way' && !!e.tags.building && e.isClosed() && !e.tags.area)!
    a.setTags(building.id, { ...building.tags, area: 'yes' })
    const issues = await collectIssues(a, [sheet], new Set())
    const issue = issues.find((i) => i.source === 'mapcss' && i.entityIds[0] === building.id)!
    expect(issue.message).toBe('Building with area=yes')
    expect(issue.group).toBe('Buildings')
    expect(issue.file).toBe('checks/buildings.validator.mapcss')
    expect(issue.line).toBe(1)
    expect(issue.fixes.map((f) => f.id)).toEqual(['tags', 'ignore'])
    expect(issue.fixes[0].title).toBe('Fix: remove area')
    expect(issues.some((i) => i.message === 'Unnamed')).toBe(false)

    expect(applyIssueFix(a, issue, 'tags', new Set())).toBe(true)
    expect(a.graph().entity(building.id).tags.area).toBeUndefined()
    expect(a.undoLabel()).toBe('Fixed: Building with area=yes')
    expect((await collectIssues(a, [sheet], new Set())).some((i) => i.id === issue.id)).toBe(false)
    a.undo()
    expect(a.graph().entity(building.id).tags.area).toBe('yes')

    const ignored = new Set<string>()
    expect(applyIssueFix(a, issue, 'ignore', ignored)).toBe(true)
    expect((await collectIssues(a, [sheet], ignored)).some((i) => i.id === issue.id)).toBe(false)
  })

  it('deletes a feature for a fixDeleteObject check', async () => {
    const sheet = parseCheckSheet('checks/del.validator.mapcss', 'h', 'node[amenity=bench][disused=yes] { throwWarning: "Disused bench"; fixDeleteObject: this; }')!
    const a = fixtureAdapter()
    const id = a.addPoint({ kind: 'none', loc: fixtureAdapterCenter() }, { amenity: 'bench', disused: 'yes' })
    const issue = (await collectIssues(a, [sheet], new Set())).find((i) => i.entityIds[0] === id)!
    expect(issue.fixes.map((f) => f.id)).toEqual(['delete', 'ignore'])
    expect(applyIssueFix(a, issue, 'delete', new Set())).toBe(true)
    expect(a.graph().hasEntity(id)).toBeUndefined()
  })
})

function fixtureAdapterCenter(): LngLatTuple {
  return [17.8805, 49.9405]
}
