import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import * as iD from '@openstreetmap/id/modules/index'
import { halfWidthMetres } from 'osm-review-shared'
import { IdAdapter, ID_SCHEMA_FILES, initIdData, primeIdData } from '../id/adapter'
import type { SmartPreset } from '../../generated/models'
import { defaultSmartParams, runSmartPreset, SmartPresetError, smartPresetAction, smartPresetApplies } from './execute'

const ID: any = iD
const root = resolve(import.meta.dirname, '../../../../node_modules') + '/'
const json = (path: string) => JSON.parse(readFileSync(root + path, 'utf8'))
const fixture = readFileSync(resolve(import.meta.dirname, '../id/fixtures/katerinky.osm'), 'utf8')
const presetFile = resolve(import.meta.dirname, '../../../../user-rules.example/presets/smart/footway-crossing.json')
const preset: SmartPreset = { description: null, ...JSON.parse(readFileSync(presetFile, 'utf8')) }

type Loc = [number, number]

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

function metres(a: Loc, b: Loc): number {
  const r = Math.PI / 180
  const x = (a[0] - b[0]) * r * 6378137 * Math.cos(((a[1] + b[1]) / 2) * r)
  const y = (a[1] - b[1]) * r * 6378137
  return Math.hypot(x, y)
}

function offset(loc: Loc, east: number, north: number): Loc {
  const r = Math.PI / 180
  return [loc[0] + east / (6378137 * r * Math.cos(loc[1] * r)), loc[1] + north / (6378137 * r)]
}

function addWay(graph: any, locs: Loc[], tags: Record<string, string>, nodeIds: (string | null)[] = []): { graph: any; way: any } {
  const ids: string[] = []
  locs.forEach((loc, i) => {
    if (nodeIds[i]) {
      ids.push(nodeIds[i]!)
      return
    }
    const n = new ID.osmNode({ loc })
    graph = graph.replace(n)
    ids.push(n.id)
  })
  const way = new ID.osmWay({ nodes: ids, tags })
  return { graph: graph.replace(way), way }
}

const FOOTWAY = { highway: 'footway', surface: 'asphalt' }
const ORIGIN: Loc = [14.0, 50.0]

function synthetic(roads = 1) {
  let graph = new ID.coreGraph()
  const roadIds: string[] = []
  for (let i = 0; i < roads; i++) {
    const r = addWay(graph, [offset(ORIGIN, -60, 20 * i), offset(ORIGIN, 60, 20 * i)], { highway: 'residential', name: `Road ${i}` })
    graph = r.graph
    roadIds.push(r.way.id)
  }
  const f = addWay(graph, [offset(ORIGIN, 0, -40), offset(ORIGIN, 1, -10), offset(ORIGIN, 0, 30 + 20 * (roads - 1)), offset(ORIGIN, 0, 60 + 20 * roads)], FOOTWAY)
  return { graph: f.graph, foot: f.way.id as string, roads: roadIds }
}

function pieces(graph: any, result: { created: string[] }, foot: string): any[] {
  return [foot, ...result.created].map((id) => graph.hasEntity(id)).filter((e: any) => e?.type === 'way' && e.tags.highway === 'footway')
}

function checkCrossing(graph: any, foot: string, road: string, crossing = 'uncontrolled', params: Record<string, string> = {}) {
  const before = graph.entity(foot)
  const beforeKeys = Object.keys(graph.entities).length
  const result = runSmartPreset(graph, preset, foot, params)
  expect(graph.entity(foot)).toBe(before)
  expect(Object.keys(graph.entities).length).toBe(beforeKeys)
  const g = result.graph
  const x = result.bindings.x as string
  const piece = g.entity(result.bindings.piece as string)
  const all = pieces(g, result, foot)
  const middle = all.filter((w) => w.tags.footway === 'crossing')
  expect(middle.map((w) => w.id)).toEqual([piece.id])
  expect(piece.tags).toEqual({ ...FOOTWAY, footway: 'crossing', crossing })
  for (const w of all) if (w !== piece) expect(w.tags).toEqual(before.tags)
  expect(piece.nodes).toContain(x)
  expect(g.entity(road).nodes).toContain(x)
  expect(g.entity(x).tags).toMatchObject({ highway: 'crossing', crossing })
  const hw = halfWidthMetres(g.entity(road).tags) as number
  const xLoc = g.entity(x).loc
  for (const end of [piece.nodes[0], piece.nodes[piece.nodes.length - 1]]) {
    if (end === x) continue
    const ends = [before.nodes[0], before.nodes[before.nodes.length - 1]]
    if (ends.includes(end)) continue
    const i = piece.nodes.indexOf(end)
    const path = i === 0 ? piece.nodes.slice(0, piece.nodes.indexOf(x) + 1) : piece.nodes.slice(piece.nodes.indexOf(x))
    let along = 0
    for (let k = 0; k < path.length - 1; k++) along += metres(g.entity(path[k]).loc, g.entity(path[k + 1]).loc)
    expect(Math.abs(along - hw)).toBeLessThanOrEqual(0.5)
    expect(metres(g.entity(end).loc, xLoc)).toBeLessThanOrEqual(hw + 0.5)
  }
  expect(result.annotation).toContain(preset.name)
  expect(result.annotation).toContain(`split ${foot} into ${all.length}`)
  expect(result.modified).toContain(foot)
  if (result.created.includes(x)) expect(result.modified).toContain(road)
  else expect(result.modified).not.toContain(road)
  return { result, all, piece }
}

describe('runSmartPreset footway crossing', () => {
  it('fills in default parameters', () => {
    expect(defaultSmartParams(preset)).toEqual({ crossing: 'uncontrolled' })
  })

  it('splits a synthetic footway around a new crossing node', () => {
    const { graph, foot, roads } = synthetic()
    expect(smartPresetApplies(preset, graph.entity(foot), graph)).toBe(true)
    expect(smartPresetApplies(preset, graph.entity(roads[0]), graph)).toBe(false)
    const { result, all } = checkCrossing(graph, foot, roads[0])
    expect(all.length).toBe(3)
    expect(result.created).toContain(result.bindings.x)
    expect(result.bindings.road).toBe(roads[0])
    expect(result.annotation).toMatch(/^Footway → crossing: .*split .* into 3.*crossing node n-\d+/)
  })

  it('substitutes parameters', () => {
    const { graph, foot, roads } = synthetic()
    checkCrossing(graph, foot, roads[0], 'traffic_signals', { crossing: 'traffic_signals' })
  })

  it('reuses an existing shared node and splits only one side near the end', () => {
    let graph = new ID.coreGraph()
    const r = addWay(graph, [offset(ORIGIN, -60, 0), ORIGIN, offset(ORIGIN, 60, 0)], { highway: 'residential' })
    graph = r.graph
    const shared = r.way.nodes[1]
    const f = addWay(graph, [ORIGIN, offset(ORIGIN, 0, 40)], FOOTWAY, [shared])
    const { all, piece, result } = checkCrossing(f.graph, f.way.id, r.way.id)
    expect(all.length).toBe(2)
    expect(result.bindings.x).toBe(shared)
    expect(piece.nodes[0]).toBe(shared)
  })

  it('reuses a vertex close to the target distance', () => {
    const hw = halfWidthMetres({ highway: 'residential' }) as number
    let graph = new ID.coreGraph()
    const r = addWay(graph, [offset(ORIGIN, -60, 0), offset(ORIGIN, 60, 0)], { highway: 'residential' })
    graph = r.graph
    const f = addWay(graph, [offset(ORIGIN, 0, -30), offset(ORIGIN, 0, -hw - 0.2), offset(ORIGIN, 0, 30)], FOOTWAY)
    const near = f.way.nodes[1]
    const { piece } = checkCrossing(f.graph, f.way.id, r.way.id)
    expect(piece.nodes).toContain(near)
    expect(piece.nodes.length).toBe(3)
  })

  it('reports no crossing and two crossings', () => {
    let graph = new ID.coreGraph()
    const r = addWay(graph, [offset(ORIGIN, -60, 0), offset(ORIGIN, 60, 0)], { highway: 'residential' })
    const f = addWay(r.graph, [offset(ORIGIN, 0, 10), offset(ORIGIN, 0, 40)], FOOTWAY)
    expect(() => runSmartPreset(f.graph, preset, f.way.id, {})).toThrow('No road crosses this footway')
    try {
      runSmartPreset(f.graph, preset, f.way.id, {})
    } catch (e) {
      expect(e).toBeInstanceOf(SmartPresetError)
      expect((e as SmartPresetError).code).toBe('none_found')
      expect((e as SmartPresetError).step).toBe(0)
      expect((e as SmartPresetError).op).toBe('findCrossing')
    }
    const two = synthetic(2)
    expect(() => runSmartPreset(two.graph, preset, two.foot, {})).toThrow(
      `2 ways cross this footway (${two.roads.join(', ')}); select a footway that crosses exactly one road`,
    )
    expect(smartPresetAction(preset, two.foot, {}).disabled(two.graph)).toBe('too_many')
  })

  it('reports an unknown road width', () => {
    let graph = new ID.coreGraph()
    const r = addWay(graph, [offset(ORIGIN, -60, 0), offset(ORIGIN, 60, 0)], { highway: 'made_up_class' })
    const f = addWay(r.graph, [offset(ORIGIN, 0, -30), offset(ORIGIN, 0, 30)], FOOTWAY)
    expect(() => runSmartPreset(f.graph, preset, f.way.id, {})).toThrow(`Cannot tell the width of ${r.way.id} (no width, lanes or known highway class)`)
  })
})

describe('footway crossing on the Kateřinky fixture', () => {
  const ROAD = 'w30999506'

  function adapter(): IdAdapter {
    const a = new IdAdapter({ center: [17.8805, 49.9405], zoom: 17, width: 1200, height: 800, bounds: [[17.876, 49.938], [17.885, 49.943]] })
    a.loadXml(fixture, [[17.878, 49.939], [17.883, 49.942]])
    return a
  }

  function footwayAcross(graph: any): { graph: any; foot: string } {
    const road = graph.entity(ROAD)
    const a = graph.entity(road.nodes[0]).loc as Loc
    const b = graph.entity(road.nodes[1]).loc as Loc
    const mid: Loc = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
    const len = metres(a, b)
    const ux = (metres([a[0], mid[1]], [b[0], mid[1]]) * Math.sign(b[0] - a[0])) / len
    const uy = (metres([mid[0], a[1]], [mid[0], b[1]]) * Math.sign(b[1] - a[1])) / len
    const f = addWay(graph, [offset(mid, -uy * 12, ux * 12), offset(mid, uy * 12, -ux * 12)], FOOTWAY)
    return { graph: f.graph, foot: f.way.id }
  }

  it('has no footways of its own, so a footway is drawn across a real residential road', () => {
    const a = adapter()
    const g = a.history.graph()
    expect(a.allEntities().some((e) => e.tags.highway === 'footway')).toBe(false)
    expect(g.entity(ROAD).tags.highway).toBe('residential')
    const { graph, foot } = footwayAcross(g)
    const { all } = checkCrossing(graph, foot, ROAD)
    expect(all.length).toBe(3)
  })

  it('is one history step with an annotation', () => {
    const a = adapter()
    const { graph, foot } = footwayAcross(a.history.graph())
    a.history.perform(() => graph, 'Draw footway')
    const drawn = a.history.graph()
    const expected = runSmartPreset(drawn, preset, foot, { crossing: 'traffic_signals' })
    a.history.perform(smartPresetAction(preset, foot, { crossing: 'traffic_signals' }), expected.annotation)
    expect(a.history.undoAnnotation()).toBe(expected.annotation)
    const after = a.history.graph()
    const x = after.entity(ROAD).nodes.filter((n: string) => after.entity(n).tags.highway === 'crossing')
    expect(x.length).toBe(1)
    expect(after.entity(x[0]).tags.crossing).toBe('traffic_signals')
    const crossings = after.parentWays(after.entity(x[0])).filter((w: any) => w.tags.footway === 'crossing')
    expect(crossings.length).toBe(1)
    expect(a.history.difference().summary().length).toBeGreaterThan(0)
    a.history.undo()
    expect(a.history.graph()).toBe(drawn)
    expect(a.history.undoAnnotation()).toBe('Draw footway')
  })
})
