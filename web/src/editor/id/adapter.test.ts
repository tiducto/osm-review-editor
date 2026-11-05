import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { IdAdapter, ID_SCHEMA_FILES, initIdData, parseOsmChange, primeIdData } from './adapter'
import type { IdWay, ViewState } from '../types'

const root = resolve(import.meta.dirname, '../../../../node_modules') + '/'
const json = (path: string) => JSON.parse(readFileSync(root + path, 'utf8'))
const fixture = readFileSync(resolve(import.meta.dirname, 'fixtures/katerinky.osm'), 'utf8')

const view: ViewState = { center: [17.8805, 49.9405], zoom: 17, width: 1200, height: 800, bounds: [[17.876, 49.938], [17.885, 49.943]] }

beforeAll(async () => {
  const schema = '@openstreetmap/id-tagging-schema/dist/'
  const files: Record<string, unknown> = {
    languages: json('@openstreetmap/id/dist/data/languages.min.json'),
    locale_general_en: json('@openstreetmap/id/dist/locales/en.min.json'),
  }
  for (const [key, file] of Object.entries(ID_SCHEMA_FILES)) files[key] = json(schema + file.replace(/\.json$/, '.min.json'))
  primeIdData(files)
  await initIdData()
})

function fresh(): IdAdapter {
  const a = new IdAdapter(view)
  a.loadXml(fixture, [[17.878, 49.939], [17.883, 49.942]])
  return a
}

function ways(a: IdAdapter, pred: (w: any) => boolean): any[] {
  return a.allEntities().filter((e) => e.type === 'way' && pred(e))
}

function doc(xml: string) {
  return new DOMParser().parseFromString(xml, 'application/xml')
}

describe('IdAdapter', () => {
  let a: IdAdapter
  beforeEach(() => {
    a = fresh()
  })

  it('loads live data into the base graph and matches presets', () => {
    expect(a.loadedBaseIds().length).toBeGreaterThan(1000)
    expect(a.editCount()).toBe(0)
    const building = ways(a, (w) => !!w.tags.building)[0]
    const snap = a.snapshot(building.id)!
    expect(snap.preset.id).toMatch(/^building/)
    expect(snap.geometry).toBe('area')
    expect(snap.preset.name.length).toBeGreaterThan(0)
    expect(a.load(a.allEntities().slice(0, 10))).toBe(0)
  })

  it('runs every operation through iD with available/disabled', () => {
    const g = () => a.graph() as any
    const highways = ways(a, (w) => !!w.tags.highway && w.nodes.length > 3 && !w.isClosed())
    const buildings = ways(a, (w) => !!w.tags.building && w.isClosed() && w.nodes.length >= 5)

    const splitWay = highways[0]
    const mid = splitWay.nodes[Math.floor(splitWay.nodes.length / 2)]
    a.selected = [mid]
    expect(a.operations().map((o) => o.id)).toContain('split')
    expect(a.runOperation('split').ok).toBe(true)
    expect(g().parentWays(g().entity(mid)).length).toBeGreaterThanOrEqual(2)

    const square = buildings.find((b) => {
      a.selected = [b.id]
      return a.operations().find((o) => o.id === 'orthogonalize')?.disabled === false
    })!
    const before = square.nodes.map((id: string) => g().entity(id).loc)
    expect(a.runOperation('orthogonalize', [square.id]).ok).toBe(true)
    expect(square.nodes.map((id: string) => g().entity(id).loc)).not.toEqual(before)
    const again = a.operations([square.id]).find((o) => o.id === 'orthogonalize')!
    expect(again.disabled).toBe('square_enough')
    expect(again.tooltip.length).toBeGreaterThan(0)

    expect(a.runOperation('reverse', [highways[2].id]).ok).toBe(true)
    expect(g().entity(highways[2].id).nodes[0]).toBe(highways[2].nodes[highways[2].nodes.length - 1])

    const circle = buildings.find((b) => b.id !== square.id && a.operations([b.id]).find((o) => o.id === 'circularize')?.disabled === false)!
    expect(a.runOperation('circularize', [circle.id]).ok).toBe(true)

    const bent = highways.find((w) => a.operations([w.id]).find((o) => o.id === 'straighten')?.disabled === false)
    if (bent) expect(a.runOperation('straighten', [bent.id]).ok).toBe(true)

    const shared = highways.flatMap((w) => w.nodes).find((n: string) => g().parentWays(g().entity(n)).length >= 2 && !g().entity(n).tags.highway)
    if (shared) {
      const op = a.operations([shared]).find((o) => o.id === 'disconnect')
      expect(op).toBeTruthy()
      if (op && !op.disabled) expect(a.runOperation('disconnect', [shared]).ok).toBe(true)
    }

    const [w1, w2] = (() => {
      for (const w of highways) {
        const partner = highways.find((x) => x.id !== w.id && x.tags.highway === w.tags.highway && (x.first() === w.last() || x.last() === w.first()))
        if (partner && g().hasEntity(w.id) && g().hasEntity(partner.id)) return [w, partner]
      }
      return [null, null]
    })()
    if (w1 && w2) {
      const merge = a.operations([w1.id, w2.id]).find((o) => o.id === 'merge')
      expect(merge).toBeTruthy()
    }

    const count = a.editCount()
    a.beginTransform('move', [buildings[8].id], [17.88, 49.94])
    a.transformTo([17.8801, 49.9401])
    a.transformTo([17.8802, 49.9402])
    a.endTransform()
    expect(a.undoLabel()).toMatch(/Moved/)
    a.beginTransform('rotate', [buildings[9].id], [17.88, 49.94])
    a.transformTo([17.8805, 49.9395])
    a.endTransform()
    expect(a.undoLabel()).toMatch(/Rotated/)
    expect(a.editCount()).toBeGreaterThan(count)

    const victim = buildings[10]
    expect(a.runOperation('delete', [victim.id]).ok).toBe(true)
    expect(g().hasEntity(victim.id)).toBeUndefined()
    a.undo()
    expect(g().hasEntity(victim.id)).toBeTruthy()
    a.redo()
    expect(g().hasEntity(victim.id)).toBeUndefined()

    const moveOp = a.operations([buildings[11].id]).find((o) => o.id === 'move')!
    expect(moveOp.disabled).toBe(false)
    const entered: string[] = []
    a.onEnter = (m) => entered.push(m.id)
    a.runOperation('move', [buildings[11].id])
    expect(entered).toContain('move')
  })

  it('draws a footway snapped to two ways, adds a point and a vertex, drags and connects', () => {
    const g = () => a.graph() as any
    const highways = ways(a, (w) => !!w.tags.highway && w.nodes.length > 2 && !w.isClosed())
    const wa = highways[0]
    const wb = highways.find((w) => !w.nodes.some((n: string) => wa.nodes.includes(n)))!
    const pa = a.project(g().entity(wa.nodes[1]).loc)
    const pb = a.project(g().entity(wb.nodes[1]).loc)
    const ea = a.chooseEdge(wa.id, [pa[0] + 3, pa[1] + 3])!
    const eb = a.chooseEdge(wb.id, [pb[0] + 3, pb[1] + 3])!
    const wayId = a.startWay('line', { kind: 'way', id: wa.id, loc: ea.loc, edge: ea.edge })
    expect(a.extendWay({ kind: 'none', loc: [17.8801, 49.9407] })).toBe('added')
    expect(a.extendWay({ kind: 'way', id: wb.id, loc: eb.loc, edge: eb.edge })).toBe('added')
    const tip = a.drawingTip()!
    expect(a.extendWay({ kind: 'node', id: tip, loc: g().entity(tip).loc })).toBe('finished')
    a.setTags(wayId, { highway: 'footway' })
    a.setTags(wayId, { highway: 'footway', surface: 'asphalt' })
    expect(a.undoLabel()).toBe('Changed tags.')
    const way = g().entity(wayId) as IdWay
    expect(way.nodes.length).toBe(3)
    expect(g().parentWays(g().entity(way.first())).map((w: any) => w.id)).toContain(wa.id)
    expect(g().parentWays(g().entity(way.last())).map((w: any) => w.id)).toContain(wb.id)
    a.undo()
    expect(g().entity(wayId).tags.surface).toBeUndefined()
    a.redo()

    const point = a.addPoint({ kind: 'none', loc: [17.8803, 49.9403] }, { amenity: 'bench' })
    expect(g().entity(point).geometry(g())).toBe('point')

    const target = highways[3]
    const n0 = g().entity(target.nodes[0]).loc
    const n1 = g().entity(target.nodes[1]).loc
    const vertex = a.beginMidpointDrag([target.nodes[0], target.nodes[1]], [(n0[0] + n1[0]) / 2, (n0[1] + n1[1]) / 2])
    a.dragTo([(n0[0] + n1[0]) / 2 + 0.00001, (n0[1] + n1[1]) / 2])
    a.endDrag(null)
    expect(g().entity(target.id).nodes).toContain(vertex)
    expect(a.undoLabel()).toBe('Added a node to a way.')

    const loose = a.addPoint({ kind: 'none', loc: [17.8809, 49.9409] })
    a.beginDrag(loose)
    a.dragTo(g().entity(target.nodes[2]).loc)
    const survivor = a.endDrag({ kind: 'node', id: target.nodes[2], loc: g().entity(target.nodes[2]).loc })
    expect(g().entity(target.id).nodes).toContain(survivor)
    expect(a.undoLabel()).toMatch(/Merged|Connected/)

    const area = a.startWay('area', { kind: 'none', loc: [17.8790, 49.9400] })
    a.extendWay({ kind: 'none', loc: [17.8792, 49.9400] })
    a.extendWay({ kind: 'none', loc: [17.8792, 49.9402] })
    const first = (g().entity(area) as IdWay).first()
    expect(a.extendWay({ kind: 'node', id: first, loc: g().entity(first).loc })).toBe('finished')
    expect((g().entity(area) as IdWay).isClosed()).toBe(true)
    expect(g().entity(area).tags.area).toBe('yes')

    const short = a.startWay('line', { kind: 'none', loc: [17.8795, 49.9395] })
    expect(a.finishWay()).toBeNull()
    expect(g().hasEntity(short)).toBeUndefined()

    const xml = a.osmChange()
    const d = doc(xml)
    expect(d.documentElement.nodeName).toBe('osmChange')
    const creates = Array.from(d.querySelectorAll('create > *')).map((e) => e.nodeName + e.getAttribute('id'))
    expect(creates).toContain('way' + wayId.slice(1))
    expect(creates).toContain('node' + point.slice(1))
    const mods = Array.from(d.querySelectorAll('modify > way')).map((e) => 'w' + e.getAttribute('id'))
    expect(mods).toEqual(expect.arrayContaining([wa.id, wb.id, target.id]))
    for (const nd of Array.from(d.querySelectorAll('nd'))) {
      const ref = Number(nd.getAttribute('ref'))
      if (ref < 0) expect(creates).toContain('node' + ref)
    }
  })

  it('reuses server ids after save and never reuses negative ids of a rehydrated draft', () => {
    const g = () => a.graph() as any
    const n = a.addPoint({ kind: 'none', loc: [17.8803, 49.9403] }, { amenity: 'bench' })
    const highway = ways(a, (w) => !!w.tags.highway && w.nodes.length > 2)[0]
    a.setTags(highway.id, { ...highway.tags, surface: 'asphalt' })
    a.remapIds([{ from: n, to: 'n-5000' }])
    expect(g().hasEntity(n)).toBeUndefined()
    expect(g().entity('n-5000').tags.amenity).toBe('bench')
    expect(g().entity(highway.id).tags.surface).toBe('asphalt')
    expect(a.editCount()).toBe(2)
    const next = a.addPoint({ kind: 'none', loc: [17.8804, 49.9404] })
    expect(Number(next.slice(1))).toBeLessThan(-5000)

    const saved = a.osmChange()
    const b = fresh()
    const report = b.applyOsmChange(saved)
    expect(report.missing).toEqual([])
    expect(b.editCount()).toBe(a.editCount())
    expect((b.graph() as any).entity('n-5000').tags.amenity).toBe('bench')
    const another = b.addPoint({ kind: 'none', loc: [17.881, 49.941] })
    for (const id of [n, next, 'n-5000']) expect(another).not.toBe(id)
    expect(Number(another.slice(1))).toBeLessThan(Math.min(-5000, Number(next.slice(1))))
    const exported = parseOsmChange(b.osmChange())
    expect(exported.create.map((e) => e.id)).toEqual(expect.arrayContaining(['n-5000', another]))
    expect(exported.modify.map((e) => e.id)).toContain(highway.id)
  })

  it('keeps the draft version of a stale modify on rehydrate', () => {
    const highway = ways(a, (w) => !!w.tags.highway && w.nodes.length > 2)[0]
    const live = Number(highway.version)
    const xml = `<osmChange version="0.6"><modify><way id="${highway.id.slice(1)}" version="${live - 1}">${highway.nodes
      .map((r: string) => `<nd ref="${r.slice(1)}"/>`)
      .join('')}<tag k="highway" v="${highway.tags.highway}"/><tag k="surface" v="gravel"/></way></modify></osmChange>`
    const report = a.applyOsmChange(xml)
    expect(report.stale).toEqual([highway.id])
    const out = doc(a.osmChange())
    const way = out.querySelector(`modify > way[id="${highway.id.slice(1)}"]`)!
    expect(way.getAttribute('version')).toBe(String(live - 1))
  })
})
