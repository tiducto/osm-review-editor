import type { Feature, Geometry, Position } from 'geojson'
import { areaStyle, hasInterestingTags, isAddressPoint, isWikidata, lineStyle, type Stroke } from '@/map/osm/classify'
import { areaPattern } from '@/map/osm/patterns'
import { isBuilding, onewayDir, strokeProps, type Props } from '@/map/osm/transform'
import type { EntityGeometry, IdEntity, IdGraph, IdNode, IdRelation, IdWay, LngLatTuple } from './types'

export const EDIT_LAYERS = ['area', 'line', 'vertex', 'point', 'label'] as const
export type EditLayer = (typeof EDIT_LAYERS)[number]

export type RenderOutput = Record<EditLayer, Feature[]>

export interface RenderInput {
  graph: IdGraph
  entities: IdEntity[]
  forcedVertices: Set<string>
  presetIcon: (entity: IdEntity, geometry: EntityGeometry) => { id: string; icon: string | null }
}

export interface RenderResult {
  layers: RenderOutput
  icons: Set<string>
  dashes: Set<string>
  hiddenWays: number[]
  hiddenNodes: number[]
}

const TYPE_CODE: Record<string, number> = { node: 0, way: 1, relation: 2 }
const NEW_OFFSET = 2 ** 46

export function featureId(id: string): number {
  const osm = Number(id.slice(1))
  const code = TYPE_CODE[{ n: 'node', w: 'way', r: 'relation' }[id[0]] ?? 'node']
  return osm < 0 ? NEW_OFFSET + -osm * 4 + code : osm * 4 + code
}

export function tileFeatureId(id: string): number | null {
  const osm = Number(id.slice(1))
  if (!(osm > 0)) return null
  return featureId(id)
}

function mercator(l: Position): [number, number] {
  const x = ((l[0] + 180) / 360) * 512
  const s = Math.sin((l[1] * Math.PI) / 180)
  const y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * 512
  return [x, y]
}

function ringArea(ring: Position[]): number {
  let a = 0
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) a += (ring[j][0] - ring[i][0]) * (ring[j][1] + ring[i][1])
  return a / 2
}

function orient(ring: Position[], ccw: boolean): Position[] {
  return ringArea(ring) > 0 === ccw ? ring : [...ring].reverse()
}

function fillZoom(rings: Position[][]): number {
  const outer = rings[0].map(mercator)
  let perimeter = 0
  for (let i = 1; i < outer.length; i++) perimeter += Math.hypot(outer[i][0] - outer[i - 1][0], outer[i][1] - outer[i - 1][1])
  const area = Math.abs(ringArea(outer))
  if (perimeter <= 0) return 30
  const width0 = (2 * area) / perimeter
  if (width0 <= 0) return 30
  return Math.floor(Math.log2(30 / width0))
}

function labelPoint(ring: Position[]): { at: Position; width0: number; area0: number } {
  let cx = 0
  let cy = 0
  let a = 0
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const f = ring[j][0] * ring[i][1] - ring[i][0] * ring[j][1]
    cx += (ring[j][0] + ring[i][0]) * f
    cy += (ring[j][1] + ring[i][1]) * f
    a += f
  }
  const at: Position = a ? [cx / (3 * a), cy / (3 * a)] : ring[0]
  const m = ring.map(mercator)
  const xs = m.map((p) => p[0])
  const ys = m.map((p) => p[1])
  const width0 = Math.min(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys))
  return { at, width0, area0: Math.abs(ringArea(m)) }
}

function feature(id: string, geometry: Geometry, props: Props): Feature {
  return { type: 'Feature', id: featureId(id), geometry, properties: { ...props, eid: id } }
}

const MP_STROKE = (color: string): Stroke => ({ color, w: 1, wl: 1, dash: null, dashl: null, cap: 'round', opacity: 1 })

function isMultipolygon(r: IdEntity): r is IdRelation {
  return r.type === 'relation' && r.isMultipolygon()
}

export function render(input: RenderInput): RenderResult {
  const { graph, entities, forcedVertices, presetIcon } = input
  const layers: RenderOutput = { area: [], line: [], vertex: [], point: [], label: [] }
  const icons = new Set<string>()
  const dashes = new Set<string>()
  const hiddenWays: number[] = []
  const hiddenNodes: number[] = []
  const seen = new Set<string>()

  const addArea = (e: IdWay | IdRelation, polys: Position[][][], mp: boolean) => {
    const t = e.tags
    const st = areaStyle(t)
    const pattern = areaPattern(t)
    const pat = pattern ? `pat:${pattern}` : ''
    const oriented = polys.map((rings) => rings.map((r, i) => orient(r, i === 0)))
    const fz = fillZoom(oriented[0])
    const geometry: Geometry = oriented.length === 1 ? { type: 'Polygon', coordinates: oriented[0] } : { type: 'MultiPolygon', coordinates: oriented }
    layers.area.push(
      feature(e.id, geometry, {
        '@fc': st.fill,
        '@sc': mp ? 'rgba(0,0,0,0)' : st.stroke,
        '@pat': pat,
        '@sort': fz,
        '@fz': fz,
        '@bld': isBuilding(t),
        '@mp': mp,
      }),
    )
    const geom: EntityGeometry = 'area'
    const preset = presetIcon(e, geom)
    const icon = !preset.icon || /building|landuse|natural/.test(preset.id) ? null : preset.icon
    const labelKeys = ['aeroway', 'amenity', 'building', 'historic', 'leisure', 'man_made', 'natural', 'shop', 'craft', 'tourism']
    const name = t.name && labelKeys.some((k) => k in t) ? t.name : ''
    if (icon || name) {
      const lp = labelPoint(oriented[0][0])
      if (icon) icons.add(`aicon:${icon}`)
      layers.label.push(
        feature(e.id, { type: 'Point', coordinates: lp.at }, {
          '@icon': icon ? `aicon:${icon}` : '',
          '@hasIcon': !!icon,
          '@name': name,
          '@w0': lp.width0,
          '@lw': name.length * 7 + 20,
          '@sort': -lp.area0,
          '@bld': isBuilding(t),
        }),
      )
    }
  }

  const addLine = (w: IdWay, coords: Position[], mpStroke: string | null) => {
    const t = w.tags
    if (mpStroke && !hasInterestingTags(t)) {
      layers.line.push(
        feature(w.id, { type: 'LineString', coordinates: coords }, {
          '@sort': 0,
          '@name': '',
          '@ow': 0,
          '@owimg': 'oneway-black',
          ...strokeProps('@c', null, dashes),
          ...strokeProps('@s', MP_STROKE(mpStroke), dashes),
        }),
      )
      return
    }
    const ls = lineStyle(t)
    const lvl = Number.parseInt(t.layer ?? '0', 10) || 0
    const bridge = t.bridge && t.bridge !== 'no' ? 1 : 0
    const dark = !!t.railway || (!!t.bridge && t.bridge !== 'no' && !!t.construction)
    layers.line.push(
      feature(w.id, { type: 'LineString', coordinates: coords }, {
        '@sort': (lvl + bridge) * 100 + ls.rank,
        '@name': t.highway || t.railway || t.waterway ? (t.name ?? '') : '',
        '@ow': onewayDir(t),
        '@owimg': dark ? 'oneway-white' : 'oneway-black',
        ...strokeProps('@c', ls.casing, dashes),
        ...strokeProps('@s', ls.stroke, dashes),
      }),
    )
  }

  const vertexCandidates = new Set<string>()

  for (const e of entities) {
    if (seen.has(e.id)) continue
    seen.add(e.id)
    if (e.type === 'node') {
      const tid = tileFeatureId(e.id)
      if (tid != null) hiddenNodes.push(tid)
      vertexCandidates.add(e.id)
      continue
    }
    if (e.type === 'way') {
      const tid = tileFeatureId(e.id)
      if (tid != null) hiddenWays.push(tid)
      const nodes = e.nodes.map((n) => graph.hasEntity(n) as IdNode | undefined)
      if (nodes.some((n) => !n)) continue
      const coords = (nodes as IdNode[]).map((n) => n.loc as Position)
      for (const n of e.nodes) vertexCandidates.add(n)
      if (coords.length < 2) continue
      const mps = graph.parentRelations(e).filter(isMultipolygon)
      const completeMp = mps.find((r) => r.isComplete(graph))
      if (e.isClosed() && e.isArea() && coords.length >= 4) {
        addArea(e, [[coords]], false)
      } else {
        addLine(e, coords, completeMp ? areaStyle(completeMp.tags).stroke : mps.length ? '#aaa' : null)
      }
      continue
    }
    if (isMultipolygon(e) && e.isComplete(graph)) {
      let polys: LngLatTuple[][][] = []
      try {
        polys = e.multipolygon(graph)
      } catch {
        polys = []
      }
      const valid = polys.filter((p) => p.length && p[0].length >= 4) as Position[][][]
      if (!valid.length) continue
      const tid = tileFeatureId(e.id)
      if (tid != null) hiddenWays.push(tid)
      addArea(e, valid, true)
    }
  }

  for (const id of vertexCandidates) {
    const n = graph.hasEntity(id) as IdNode | undefined
    if (!n || n.type !== 'node') continue
    const parents = graph.parentWays(n)
    const t = n.tags
    if (!parents.length) {
      if (isAddressPoint(t)) {
        layers.point.push(feature(n.id, { type: 'Point', coordinates: n.loc }, { '@addr': true, '@hn': t['addr:housenumber'] ?? t['addr:housename'] ?? '', '@name': '' }))
        continue
      }
      const icon = presetIcon(n, 'point').icon
      const pin = `pin:${icon ?? ''}:${isWikidata(t) ? 'w' : ''}`
      icons.add(pin)
      layers.point.push(feature(n.id, { type: 'Point', coordinates: n.loc }, { '@addr': false, '@pin': pin, '@name': t.name ?? '' }))
      continue
    }
    const tagged = hasInterestingTags(t)
    const shared = parents.length > 1
    const end = parents.some((w) => !w.isClosed() && (w.first() === id || w.last() === id))
    const taggedLine = parents.some((w) => hasInterestingTags(w.tags) && !w.isArea())
    const show = tagged || forcedVertices.has(id) || ((shared || end) && taggedLine)
    if (!show) continue
    const icon = tagged ? presetIcon(n, 'vertex').icon : null
    if (icon) icons.add(`vicon:${icon}`)
    layers.vertex.push(
      feature(n.id, { type: 'Point', coordinates: n.loc }, {
        '@tagged': tagged,
        '@icon': icon ? `vicon:${icon}` : '',
        '@hasIcon': !!icon,
        '@shared': shared,
        '@loose': end && parents.length === 1,
        '@name': t.name ?? '',
        '@forced': forcedVertices.has(id),
      }),
    )
  }

  return { layers, icons, dashes, hiddenWays, hiddenNodes }
}

export function midpoints(
  graph: IdGraph,
  wayIds: string[],
  project: (loc: LngLatTuple) => [number, number],
  minPx = 40,
): Feature[] {
  const out: Feature[] = []
  for (const wid of wayIds) {
    const w = graph.hasEntity(wid) as IdWay | undefined
    if (!w || w.type !== 'way') continue
    for (let i = 0; i < w.nodes.length - 1; i++) {
      const a = graph.hasEntity(w.nodes[i]) as IdNode | undefined
      const b = graph.hasEntity(w.nodes[i + 1]) as IdNode | undefined
      if (!a || !b || a.id === b.id) continue
      const pa = project(a.loc)
      const pb = project(b.loc)
      if (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) < minPx) continue
      const loc: Position = [(a.loc[0] + b.loc[0]) / 2, (a.loc[1] + b.loc[1]) / 2]
      const angle = (Math.atan2(pb[1] - pa[1], pb[0] - pa[0]) * 180) / Math.PI
      out.push({ type: 'Feature', geometry: { type: 'Point', coordinates: loc }, properties: { a: a.id, b: b.id, way: wid, angle } })
    }
  }
  return out
}
