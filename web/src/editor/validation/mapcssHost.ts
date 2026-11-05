import type { IdEntity, IdGraph, IdNode, IdRelation, IdWay, LngLatTuple } from '../types'

export const ISLAND_CAP = 100
export const MAX_SPATIAL = 500
export const MAX_LINKS = 2000
const EARTH_RADIUS = 6378137

export interface MapCssLinkJs {
  element: MapCssElementJs
  role: string
  index: number
}

export interface MapCssElementJs {
  type: 'node' | 'way' | 'relation'
  id: number
  eid: string
  tags: Record<string, string>
  closed: boolean
  parents: () => MapCssLinkJs[]
  children: () => MapCssLinkJs[]
}

export interface GraphHostOptions {
  graph: IdGraph
  intersects: (extent: [LngLatTuple, LngLatTuple]) => IdEntity[]
  rightHandTraffic?: (loc: LngLatTuple) => boolean
}

const DEFAULT_WIDTH: Record<string, number> = {
  motorway: 11.0, motorway_link: 6.0, trunk: 9.0, trunk_link: 6.0,
  primary: 8.0, primary_link: 5.5, secondary: 7.0, secondary_link: 5.0,
  tertiary: 6.5, tertiary_link: 5.0, unclassified: 5.5, residential: 5.5,
  living_street: 5.0, service: 4.0, pedestrian: 5.0, track: 3.0, busway: 6.5,
  road: 5.5, footway: 2.0, cycleway: 2.0, path: 1.5, steps: 2.0, bridleway: 2.0,
  corridor: 2.0, platform: 3.0,
}
const LANE_WIDTH: Record<string, number> = { motorway: 3.5, trunk: 3.5, primary: 3.25, secondary: 3.25 }
const LENGTH = /^\s*(\d+(?:[.,]\d+)?)\s*(m|km|cm|ft|')?\s*$/

export function parseLength(s: string): number | null {
  const m = LENGTH.exec(s)
  if (!m) return null
  const v = Number(m[1].replace(',', '.'))
  if (!Number.isFinite(v)) return null
  const unit = m[2] ?? ''
  const metres = unit === 'km' ? v * 1000 : unit === 'cm' ? v / 100 : unit === 'ft' || unit === "'" ? v * 0.3048 : v
  return metres > 0 && metres < 200 ? metres : null
}

export function roadWidth(tags: Record<string, string>): number | null {
  const w = tags.width != null ? parseLength(tags.width) : null
  if (w != null) return w
  const highway = tags.highway
  const lanesText = tags.lanes?.trim()
  const lanes = lanesText && /^-?\d+$/.test(lanesText) ? Number(lanesText) : NaN
  if (lanes >= 1 && lanes <= 20) {
    const base = highway?.replace(/_link$/, '')
    return lanes * ((base && LANE_WIDTH[base]) || 3.0)
  }
  return highway ? (DEFAULT_WIDTH[highway] ?? null) : null
}

export function halfWidth(tags: Record<string, string>): number | null {
  const w = roadWidth(tags)
  return w == null ? null : w / 2
}

export function distanceMetres(a: LngLatTuple, b: LngLatTuple): number {
  const rad = Math.PI / 180
  const lat1 = a[1] * rad
  const lat2 = b[1] * rad
  const dLat = lat2 - lat1
  const dLon = (b[0] - a[0]) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(h)))
}

function ringAreaMetres(ring: LngLatTuple[]): number {
  if (ring.length < 3) return 0
  const lat0 = (ring.reduce((s, p) => s + p[1], 0) / ring.length) * (Math.PI / 180)
  const k = (Math.PI / 180) * EARTH_RADIUS
  let a = 0
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i][0] * k * Math.cos(lat0)
    const yi = ring[i][1] * k
    const xj = ring[j][0] * k * Math.cos(lat0)
    const yj = ring[j][1] * k
    a += xj * yi - xi * yj
  }
  return Math.abs(a / 2)
}

function pointInRing(p: LngLatTuple, ring: LngLatTuple[]): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    if (yi > p[1] !== yj > p[1] && p[0] < ((xj - xi) * (p[1] - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

function orient(a: LngLatTuple, b: LngLatTuple, c: LngLatTuple): number {
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
}

function segmentsCross(a: LngLatTuple, b: LngLatTuple, c: LngLatTuple, d: LngLatTuple): boolean {
  const o1 = orient(a, b, c)
  const o2 = orient(a, b, d)
  const o3 = orient(c, d, a)
  const o4 = orient(c, d, b)
  return ((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))
}

function bboxOf(locs: LngLatTuple[]): [LngLatTuple, LngLatTuple] {
  let w = Infinity
  let s = Infinity
  let e = -Infinity
  let n = -Infinity
  for (const [x, y] of locs) {
    w = Math.min(w, x)
    e = Math.max(e, x)
    s = Math.min(s, y)
    n = Math.max(n, y)
  }
  return [
    [w, s],
    [e, n],
  ]
}

function isLinear(w: IdWay): boolean {
  return !(w.isClosed() && w.isArea())
}

function kindOf(e: IdEntity): 'node' | 'way' | 'relation' {
  return e.type
}

export class GraphMapCssHost {
  private readonly graph: IdGraph
  private readonly islands = new Map<string, number>()
  private readonly elements = new Map<string, MapCssElementJs>()

  constructor(private readonly opts: GraphHostOptions) {
    this.graph = opts.graph
  }

  element(entity: IdEntity): MapCssElementJs {
    const hit = this.elements.get(entity.id)
    if (hit) return hit
    const self = this
    let parents: MapCssLinkJs[] | null = null
    let children: MapCssLinkJs[] | null = null
    const el: MapCssElementJs = {
      type: kindOf(entity),
      id: Number(entity.id.slice(1)),
      eid: entity.id,
      tags: entity.tags,
      closed: this.isClosed(entity),
      parents: () => (parents ??= self.parentLinks(entity)),
      children: () => (children ??= self.childLinks(entity)),
    }
    this.elements.set(entity.id, el)
    return el
  }

  private isClosed(e: IdEntity): boolean {
    if (e.type === 'way') {
      const w = e as IdWay
      if (w.nodes.length < 4) return false
      const a = this.graph.hasEntity(w.nodes[0]) as IdNode | undefined
      const b = this.graph.hasEntity(w.nodes[w.nodes.length - 1]) as IdNode | undefined
      if (w.nodes[0] === w.nodes[w.nodes.length - 1]) return true
      return !!a && !!b && a.loc[0] === b.loc[0] && a.loc[1] === b.loc[1]
    }
    if (e.type === 'relation') {
      const t = e.tags.type
      return t === 'multipolygon' || t === 'boundary'
    }
    return false
  }

  private parentLinks(e: IdEntity): MapCssLinkJs[] {
    const g = this.graph
    const out: MapCssLinkJs[] = []
    if (e.type === 'node') {
      for (const w of g.parentWays(e)) {
        if (out.length >= MAX_LINKS) break
        out.push({ element: this.element(w), role: '', index: Math.max(0, w.nodes.indexOf(e.id)) })
      }
    }
    for (const r of g.parentRelations(e)) {
      if (out.length >= MAX_LINKS) break
      const i = r.members.findIndex((m) => m.id === e.id)
      out.push({ element: this.element(r), role: i >= 0 ? r.members[i].role : '', index: Math.max(0, i) })
    }
    return out
  }

  private childLinks(e: IdEntity): MapCssLinkJs[] {
    const g = this.graph
    const out: MapCssLinkJs[] = []
    if (e.type === 'way') {
      for (const id of (e as IdWay).nodes) {
        if (out.length >= MAX_LINKS) break
        const n = g.hasEntity(id)
        if (n) out.push({ element: this.element(n), role: '', index: out.length })
      }
    } else if (e.type === 'relation') {
      for (const m of (e as IdRelation).members) {
        if (out.length >= MAX_LINKS) break
        const c = g.hasEntity(m.id)
        if (c) out.push({ element: this.element(c), role: m.role ?? '', index: out.length })
      }
    }
    return out
  }

  private entityOf(el: MapCssElementJs | null | undefined): IdEntity | undefined {
    return el?.eid ? this.graph.hasEntity(el.eid) : undefined
  }

  call(name: string, el: MapCssElementJs, args: unknown[]): unknown {
    const e = this.entityOf(el)
    switch (name) {
      case 'waylength':
        return e && e.type !== 'node' ? this.length(e) : undefined
      case 'areasize':
        return e && this.isArea(e) ? this.area(e) : undefined
      case 'odd_segment':
        return e ? this.oddSegment(e, String(args[0] ?? '')) : undefined
      case 'network_island_size':
        return e && e.type === 'way' ? this.islandSize(e as IdWay) : undefined
      case 'half_width':
        return halfWidth(el.tags) ?? undefined
      case 'connected_value':
        return e ? this.connectedValue(e, String(args[0] ?? ''), args[1] == null ? null : String(args[1])) : undefined
      case 'is_right_hand_traffic':
        return this.rightHand(e)
      default:
        return undefined
    }
  }

  pseudoClass(name: string, el: MapCssElementJs): boolean | undefined {
    const e = this.entityOf(el)
    switch (name) {
      case 'righthandtraffic':
        return this.rightHand(e)
      case 'in-downloaded-area':
        return true
      case 'unconnected':
        return !!e && e.type === 'node' && this.graph.parentWays(e).length === 0
      case 'connection':
        return !!e && e.type === 'node' && new Set(this.graph.parentWays(e).map((w) => w.id)).size >= 2
      default:
        return undefined
    }
  }

  inside(el: MapCssElementJs): MapCssElementJs[] {
    const e = this.entityOf(el)
    if (!e || e.type === 'node' || (!el.closed && !this.isArea(e))) return []
    const rings = this.rings(e)
    if (!rings.length) return []
    const covered = (p: LngLatTuple) => {
      let inside = false
      for (const r of rings) if (pointInRing(p, r)) inside = !inside
      return inside
    }
    const out: MapCssElementJs[] = []
    for (const c of this.opts.intersects(bboxOf(rings.flat()))) {
      if (out.length >= MAX_SPATIAL) break
      if (c.id === e.id) continue
      const locs = this.locs(c)
      if (locs.length && locs.every(covered)) out.push(this.element(c))
    }
    return out
  }

  crossing(el: MapCssElementJs): MapCssElementJs[] {
    const e = this.entityOf(el)
    if (!e || e.type !== 'way') return []
    const w = e as IdWay
    const nodes = w.nodes.map((id) => this.graph.hasEntity(id) as IdNode | undefined).filter((n): n is IdNode => !!n)
    if (nodes.length < 2) return []
    const out: MapCssElementJs[] = []
    for (const c of this.opts.intersects(bboxOf(nodes.map((n) => n.loc)))) {
      if (out.length >= MAX_SPATIAL) break
      if (c.type !== 'way' || c.id === e.id) continue
      const other = (c as IdWay).nodes.map((id) => this.graph.hasEntity(id) as IdNode | undefined).filter((n): n is IdNode => !!n)
      if (this.waysCross(nodes, other)) out.push(this.element(c))
    }
    return out
  }

  private waysCross(a: IdNode[], b: IdNode[]): boolean {
    for (let i = 1; i < a.length; i++) {
      for (let j = 1; j < b.length; j++) {
        const shared = a[i - 1].id === b[j - 1].id || a[i - 1].id === b[j].id || a[i].id === b[j - 1].id || a[i].id === b[j].id
        if (shared) continue
        if (segmentsCross(a[i - 1].loc, a[i].loc, b[j - 1].loc, b[j].loc)) return true
      }
    }
    return false
  }

  private rightHand(e: IdEntity | undefined): boolean {
    if (!e || !this.opts.rightHandTraffic) return true
    const locs = this.locs(e)
    if (!locs.length) return true
    const [[w, s], [ea, n]] = bboxOf(locs)
    return this.opts.rightHandTraffic([(w + ea) / 2, (s + n) / 2])
  }

  private locs(e: IdEntity): LngLatTuple[] {
    if (e.type === 'node') return [(e as IdNode).loc]
    if (e.type === 'way') return (e as IdWay).nodes.map((id) => this.graph.hasEntity(id) as IdNode | undefined).filter((n): n is IdNode => !!n).map((n) => n.loc)
    const out: LngLatTuple[] = []
    for (const m of (e as IdRelation).members) {
      const c = this.graph.hasEntity(m.id)
      if (c && c.type !== 'relation') out.push(...this.locs(c))
    }
    return out
  }

  isArea(e: IdEntity): boolean {
    if (e.type === 'way') return (e as IdWay).isClosed() && (e as IdWay).isArea()
    if (e.type === 'relation') return (e as IdRelation).isMultipolygon() || e.tags.type === 'boundary'
    return false
  }

  private rings(e: IdEntity): LngLatTuple[][] {
    if (e.type === 'way') {
      const locs = this.locs(e)
      return locs.length >= 4 ? [locs] : []
    }
    if (e.type === 'relation' && (e as IdRelation).isMultipolygon()) {
      try {
        return (e as IdRelation).multipolygon(this.graph).flat()
      } catch {
        return []
      }
    }
    return []
  }

  length(e: IdEntity): number {
    if (e.type === 'way') {
      const l = this.locs(e)
      let sum = 0
      for (let i = 1; i < l.length; i++) sum += distanceMetres(l[i - 1], l[i])
      return sum
    }
    if (e.type === 'relation') {
      let sum = 0
      for (const m of (e as IdRelation).members) {
        const c = this.graph.hasEntity(m.id)
        if (c && c.type === 'way') sum += this.length(c)
      }
      return sum
    }
    return 0
  }

  area(e: IdEntity): number {
    if (e.type === 'way') return ringAreaMetres(this.locs(e))
    if (e.type === 'relation') {
      let polys: LngLatTuple[][][] = []
      try {
        polys = (e as IdRelation).multipolygon(this.graph)
      } catch {
        polys = []
      }
      let sum = 0
      for (const p of polys) p.forEach((ring, i) => (sum += i === 0 ? ringAreaMetres(ring) : -ringAreaMetres(ring)))
      return Math.max(0, sum)
    }
    return 0
  }

  private connectedHighways(e: IdEntity): IdWay[] {
    const g = this.graph
    const out = new Map<string, IdWay>()
    const nodes = e.type === 'node' ? [e as IdNode] : e.type === 'way' ? (e as IdWay).nodes.map((id) => g.hasEntity(id) as IdNode | undefined) : []
    for (const n of nodes) {
      if (!n) continue
      for (const w of g.parentWays(n)) if (w.id !== e.id && w.tags.highway && isLinear(w)) out.set(w.id, w)
    }
    return [...out.values()]
  }

  islandSize(way: IdWay): number {
    const cached = this.islands.get(way.id)
    if (cached != null) return cached
    const seen = new Set<string>([way.id])
    const queue: IdWay[] = [way]
    while (queue.length && seen.size < ISLAND_CAP) {
      const w = queue.shift()!
      for (const n of this.connectedHighways(w)) {
        if (!seen.has(n.id)) {
          seen.add(n.id)
          queue.push(n)
        }
        if (seen.size >= ISLAND_CAP) break
      }
    }
    const size = !queue.length && seen.size < ISLAND_CAP ? seen.size : ISLAND_CAP
    for (const id of seen) this.islands.set(id, Math.max(this.islands.get(id) ?? 0, size))
    return size
  }

  oddSegment(e: IdEntity, keys: string): string {
    if (e.type !== 'way') return ''
    const way = e as IdWay
    const name = way.tags.name ?? ''
    if (!name) return ''
    if (way.nodes.length < 2) return ''
    const first = this.graph.hasEntity(way.nodes[0]) as IdNode | undefined
    const last = this.graph.hasEntity(way.nodes[way.nodes.length - 1]) as IdNode | undefined
    if (!first || !last) return ''
    if (first.loc[0] === last.loc[0] && first.loc[1] === last.loc[1]) return ''
    const neighbours = (n: IdNode) => this.graph.parentWays(n).filter((w) => w.id !== way.id && isLinear(w) && (w.tags.name ?? '') === name)
    const a = neighbours(first)
    if (!a.length) return ''
    const b = neighbours(last)
    if (!b.length) return ''
    const all = [...new Map([...a, ...b].map((w) => [w.id, w])).values()]
    return keys
      .split(';')
      .map((k) => k.trim())
      .filter((k) => k)
      .filter((k) => {
        const values = new Set(all.map((w) => w.tags[k] ?? ''))
        return values.size === 1 && [...values][0] !== (way.tags[k] ?? '')
      })
      .join(';')
  }

  connectedValue(e: IdEntity, key: string, highwayList: string | null): string {
    if (!key || e.type === 'relation') return ''
    const classes = highwayList
      ? new Set(
          highwayList
            .split(';')
            .map((s) => s.trim())
            .filter((s) => s),
        )
      : null
    const values = new Set<string>()
    for (const w of this.connectedHighways(e)) {
      if (classes && !classes.has(w.tags.highway)) continue
      const v = w.tags[key]
      if (v) values.add(v)
    }
    return values.size === 1 ? [...values][0] : ''
  }
}
