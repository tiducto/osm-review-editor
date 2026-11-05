import * as iD from '@openstreetmap/id/modules/index'
import { halfWidthMetres, roadWidthMetres } from 'osm-review-shared'
import type { JsonValue, SmartPreset } from '../../generated/models'
import { t, tn } from '@/i18n'
import { selectorMatches, selectorValid } from './selector'


type Loc = [number, number]
type Binding = string | string[] | null
type Step = Record<string, JsonValue>

export interface SmartRunResult {
  graph: any
  annotation: string
  bindings: Record<string, string | string[]>
  created: string[]
  modified: string[]
  summary: string[]
}

export class SmartPresetError extends Error {
  step: number
  op: string
  code: string

  constructor(message: string, step: number, op: string, code: string) {
    super(message)
    this.name = 'SmartPresetError'
    this.step = step
    this.op = op
    this.code = code
  }
}

const EARTH_RADIUS = 6378137
const RAD = Math.PI / 180
const SAME_POINT_M = 0.05

function metres(a: Loc, b: Loc): number {
  const x = (a[0] - b[0]) * RAD * EARTH_RADIUS * Math.cos(((a[1] + b[1]) / 2) * RAD)
  const y = (a[1] - b[1]) * RAD * EARTH_RADIUS
  return Math.sqrt(x * x + y * y)
}

function interp(a: Loc, b: Loc, t: number): Loc {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
}

function cross(a: Loc, b: Loc): number {
  return a[0] * b[1] - a[1] * b[0]
}

function segmentIntersection(p: Loc, p2: Loc, q: Loc, q2: Loc): Loc | null {
  const r: Loc = [p2[0] - p[0], p2[1] - p[1]]
  const s: Loc = [q2[0] - q[0], q2[1] - q[1]]
  const d = cross(r, s)
  if (d === 0) return null
  const qp: Loc = [q[0] - p[0], q[1] - p[1]]
  const t = cross(qp, s) / d
  const u = cross(qp, r) / d
  if (t < 0 || t > 1 || u < 0 || u > 1) return null
  return interp(p, p2, t)
}

interface Projector {
  (loc: Loc): Loc
}

function projector(lat0: number): Projector {
  const kx = RAD * EARTH_RADIUS * Math.cos(lat0 * RAD)
  const ky = RAD * EARTH_RADIUS
  return (loc) => [loc[0] * kx, loc[1] * ky]
}

function pointSegment(p: Loc, a: Loc, b: Loc): number {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  const len = dx * dx + dy * dy
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len))
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)
}

function polylineDistance(a: Loc[], b: Loc[]): number {
  if (a.length === 1 && b.length === 1) return Math.hypot(a[0][0] - b[0][0], a[0][1] - b[0][1])
  if (a.length === 1) return Math.min(...segments(b).map(([p, q]) => pointSegment(a[0], p, q)))
  if (b.length === 1) return polylineDistance(b, a)
  let best = Infinity
  for (const [p, p2] of segments(a)) {
    for (const [q, q2] of segments(b)) {
      if (segmentIntersection(p, p2, q, q2)) return 0
      best = Math.min(best, pointSegment(p, q, q2), pointSegment(p2, q, q2), pointSegment(q, p, p2), pointSegment(q2, p, p2))
    }
  }
  return best
}

function segments(locs: Loc[]): [Loc, Loc][] {
  const out: [Loc, Loc][] = []
  for (let i = 0; i < locs.length - 1; i++) out.push([locs[i], locs[i + 1]])
  return out
}

interface Box {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

function boxOf(locs: Loc[]): Box {
  const b = { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity }
  for (const [x, y] of locs) {
    if (x < b.minX) b.minX = x
    if (y < b.minY) b.minY = y
    if (x > b.maxX) b.maxX = x
    if (y > b.maxY) b.maxY = y
  }
  return b
}

function grow(b: Box, m: number): Box {
  const dy = m / (RAD * EARTH_RADIUS)
  const dx = dy / Math.max(0.01, Math.cos(((b.minY + b.maxY) / 2) * RAD))
  return { minX: b.minX - dx, minY: b.minY - dy, maxX: b.maxX + dx, maxY: b.maxY + dy }
}

function overlaps(a: Box, b: Box): boolean {
  return a.minX <= b.maxX && b.minX <= a.maxX && a.minY <= b.maxY && b.minY <= a.maxY
}

function allEntities(graph: any): any[] {
  const ids = new Set<string>(Object.keys(graph.base().entities))
  for (const id of Object.keys(graph.entities)) ids.add(id)
  const out: any[] = []
  for (const id of ids) {
    const e = graph.hasEntity(id)
    if (e) out.push(e)
  }
  return out
}

function wayLocs(graph: any, way: any): Loc[] {
  return way.nodes.map((n: string) => graph.entity(n).loc as Loc)
}

function entityLocs(graph: any, e: any): Loc[] | null {
  if (e.type === 'node') return [e.loc]
  if (e.type === 'way') return wayLocs(graph, e)
  return null
}

interface CrossPoint {
  loc: Loc
  nodeId: string | null
  aSegment: number
  bSegment: number
}

function crossPoints(graph: any, a: any, b: any, includeShared: boolean): CrossPoint[] {
  const out: CrossPoint[] = []
  const aEnds = new Set([a.nodes[0], a.nodes[a.nodes.length - 1]])
  const bEnds = new Set([b.nodes[0], b.nodes[b.nodes.length - 1]])
  const bNodes = new Set<string>(b.nodes)
  if (includeShared) {
    for (const n of new Set<string>(a.nodes)) {
      if (!bNodes.has(n)) continue
      if (aEnds.has(n) && bEnds.has(n) && !a.isClosed() && !b.isClosed()) continue
      out.push({ loc: graph.entity(n).loc, nodeId: n, aSegment: -1, bSegment: -1 })
    }
  }
  const aLocs = wayLocs(graph, a)
  const bLocs = wayLocs(graph, b)
  for (let i = 0; i < aLocs.length - 1; i++) {
    for (let j = 0; j < bLocs.length - 1; j++) {
      const ids = [a.nodes[i], a.nodes[i + 1], b.nodes[j], b.nodes[j + 1]]
      if (new Set(ids).size < 4) continue
      const hit = segmentIntersection(aLocs[i], aLocs[i + 1], bLocs[j], bLocs[j + 1])
      if (!hit) continue
      if (out.some((o) => metres(o.loc, hit) < SAME_POINT_M)) continue
      out.push({ loc: hit, nodeId: null, aSegment: i, bSegment: j })
    }
  }
  return out
}

function describe(e: any): string {
  const kind = e.tags?.highway ?? e.tags?.railway ?? e.tags?.waterway
  return kind ? t('smart.this', { kind: String(kind).replace(/_/g, ' ') }) : e.id
}

export function defaultSmartParams(preset: SmartPreset): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [name, p] of Object.entries(preset.params ?? {})) {
    out[name] = p.default ?? (p.type === 'combo' ? (p.options[0] ?? '') : p.type === 'check' ? 'no' : '')
  }
  return out
}

export function smartPresetApplies(preset: SmartPreset, entity: any, graph: any): boolean {
  if (!entity) return false
  if (!(preset.geometry as string[]).includes(entity.geometry(graph))) return false
  if (!selectorValid(preset.match)) return false
  return selectorMatches(preset.match, graph, entity)
}

class Runner {
  graph: any
  readonly bindings: Record<string, Binding> = {}
  readonly summary: string[] = []
  readonly edits: { kind: 'connected' | 'added' | 'kept' | 'split' | 'crossing' | 'tagged'; id: string; text: string }[] = []
  stepIndex = -1
  op = 'match'
  private entities: any[] | null = null

  constructor(
    graph: any,
    readonly preset: SmartPreset,
    readonly params: Record<string, string>,
  ) {
    this.graph = graph
  }

  fail(code: string, message: string): never {
    throw new SmartPresetError(message, this.stepIndex, this.op, code)
  }

  all(): any[] {
    this.entities ??= allEntities(this.graph)
    return this.entities
  }

  touched() {
    this.entities = null
  }

  str(step: Step, key: string, fallback?: string): string {
    const v = step[key]
    if (typeof v === 'string') return v
    if (typeof v === 'number') return String(v)
    if (fallback !== undefined) return fallback
    return this.fail('bad_step', t('smart.err.needs', { step: this.stepIndex + 1, op: this.op, key }))
  }

  selector(step: Step): string {
    const sel = this.str(step, 'with')
    if (!selectorValid(sel)) this.fail('bad_selector', t('smart.err.badSelector', { sel }))
    return sel
  }

  ref(name: string): Binding | undefined {
    if (!(name in this.bindings)) this.fail('not_bound', t('smart.err.notBound', { name, step: this.stepIndex + 1 }))
    return this.bindings[name]
  }

  single(name: string, type?: 'way' | 'node'): any | null {
    const b = this.ref(name)
    if (b == null) return null
    if (Array.isArray(b)) {
      if (b.length !== 1) this.fail('not_single', tn('smart.err.notSingle', b.length, { name, ids: b.join(', ') }))
      return this.single1(b[0], name, type)
    }
    return this.single1(b, name, type)
  }

  private single1(id: string, name: string, type?: 'way' | 'node'): any {
    const e = this.graph.hasEntity(id)
    if (!e) this.fail('missing', t('smart.err.missingNamed', { id, name }))
    if (type && e.type !== type) this.fail('wrong_type', t('smart.err.wrongType', { name, id: e.id, type }))
    return e
  }

  bind(step: Step, value: Binding) {
    const as = step.as
    if (typeof as === 'string') this.bindings[as] = value
  }

  require(step: Step, found: any[], none: () => string, many: (n: number, ids: string) => string): Binding {
    const req = this.str(step, 'require', 'one')
    const ids = found.map((e) => e.id)
    if (req === 'optional') return ids.length === 0 ? null : ids.length === 1 ? ids[0] : ids
    if (ids.length === 0) this.fail('none_found', none())
    if (req === 'any') return ids
    if (ids.length > 1) this.fail('too_many', many(ids.length, ids.join(', ')))
    return ids[0]
  }

  distance(v: JsonValue | undefined): number | null {
    if (typeof v === 'number') return v
    if (typeof v !== 'string') return this.fail('bad_distance', t('smart.err.needsDistance', { step: this.stepIndex + 1, op: this.op }))
    const s = v.trim()
    const plain = /^(\d+(?:\.\d+)?)\s*m?$/.exec(s)
    if (plain) return Number(plain[1])
    const fn = /^(half_width|width)\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)$/.exec(s)
    if (!fn) return this.fail('bad_distance', t('smart.err.badDistance', { value: v }))
    const way = this.single(fn[2], 'way')
    if (way == null) return null
    const w = fn[1] === 'width' ? roadWidthMetres(way.tags) : halfWidthMetres(way.tags)
    if (w == null || !Number.isFinite(w)) this.fail('unknown_width', t('smart.err.unknownWidth', { id: way.id }))
    return w as number
  }

  run(selfId: string) {
    const self = this.graph.hasEntity(selfId)
    if (!self) this.fail('missing', t('smart.err.missing', { id: selfId }))
    if (!smartPresetApplies(this.preset, self, this.graph)) this.fail('not_applicable', t('smart.err.notApplicable', { name: this.preset.name, id: selfId }))
    this.bindings.self = selfId
    this.preset.steps.forEach((step, i) => {
      this.stepIndex = i
      this.op = typeof step.op === 'string' ? step.op : '?'
      switch (this.op) {
        case 'findCrossing':
          return this.findCrossing(step)
        case 'findConnected':
          return this.findConnected(step)
        case 'findNearby':
          return this.findNearby(step)
        case 'ensureSharedNode':
          return this.ensureSharedNode(step)
        case 'splitAround':
          return this.splitAround(step)
        case 'tag':
          return this.tag(step)
        default:
          return this.fail('unknown_op', t('smart.err.unknownOp', { op: this.op }))
      }
    })
  }

  findCrossing(step: Step) {
    const sel = this.selector(step)
    const as = this.str(step, 'as')
    const self = this.single('self', 'way')
    if (self == null) return this.bind(step, null)
    const box = boxOf(wayLocs(this.graph, self))
    const found = this.all().filter((e) => {
      if (e.type !== 'way' || e.id === self.id) return false
      if (!overlaps(box, boxOf(wayLocs(this.graph, e)))) return false
      if (!selectorMatches(sel, this.graph, e)) return false
      return crossPoints(this.graph, self, e, true).length > 0
    })
    const what = describe(self)
    const value = this.require(
      step,
      found,
      () => t('smart.err.noCrossing', { as, what }),
      (n, ids) => tn('smart.err.manyCrossing', n, { what, ids, kind: self.tags.highway ?? 'way', as }),
    )
    this.summary.push(`${as}: ${value == null ? t('smart.none') : [value].flat().join(', ')}`)
    this.bind(step, value)
  }

  findConnected(step: Step) {
    const sel = this.selector(step)
    const as = this.str(step, 'as')
    const self = this.single('self')
    if (self == null) return this.bind(step, null)
    const nodeIds: string[] = self.type === 'way' ? self.nodes : self.type === 'node' ? [self.id] : []
    const seen = new Map<string, any>()
    for (const n of new Set(nodeIds)) {
      const node = this.graph.hasEntity(n)
      if (!node) continue
      for (const w of this.graph.parentWays(node)) {
        if (w.id !== self.id && !seen.has(w.id) && selectorMatches(sel, this.graph, w)) seen.set(w.id, w)
      }
    }
    const what = describe(self)
    const value = this.require(
      step,
      [...seen.values()],
      () => t('smart.err.noConnected', { as, what }),
      (n, ids) => tn('smart.err.manyConnected', n, { what, ids, as }),
    )
    this.summary.push(`${as}: ${value == null ? t('smart.none') : [value].flat().join(', ')}`)
    this.bind(step, value)
  }

  findNearby(step: Step) {
    const sel = this.selector(step)
    const as = this.str(step, 'as')
    const self = this.single('self')
    const d = this.distance(step.distance)
    if (self == null || d == null) return this.bind(step, null)
    const selfLocs = entityLocs(this.graph, self)
    if (!selfLocs) this.fail('wrong_type', t('smart.err.noGeometry', { id: self.id }))
    const box = grow(boxOf(selfLocs!), d)
    const own = new Set<string>(self.type === 'way' ? self.nodes : [])
    const project = projector((box.minY + box.maxY) / 2)
    const selfPts = selfLocs!.map(project)
    const found = this.all().filter((e) => {
      if (e.id === self.id || own.has(e.id) || (e.type !== 'node' && e.type !== 'way')) return false
      const locs = entityLocs(this.graph, e)
      if (!locs || locs.length === 0 || !overlaps(box, boxOf(locs))) return false
      if (polylineDistance(selfPts, locs.map(project)) > d) return false
      return selectorMatches(sel, this.graph, e)
    })
    const what = describe(self)
    const value = this.require(
      step,
      found,
      () => t('smart.err.noNearby', { as, d, what }),
      (n, ids) => tn('smart.err.manyNearby', n, { d, what, ids, as }),
    )
    this.summary.push(`${as}: ${value == null ? t('smart.none') : [value].flat().join(', ')}`)
    this.bind(step, value)
  }

  ensureSharedNode(step: Step) {
    const names = step.ways
    if (!Array.isArray(names) || names.length < 2 || names.some((n) => typeof n !== 'string')) {
      return this.fail('bad_step', t('smart.err.needsWays', { step: this.stepIndex + 1 }))
    }
    const ways = (names as string[]).map((n) => this.single(n, 'way'))
    if (ways.some((w) => w == null)) return this.bind(step, null)
    let shared = new Set<string>(ways[0].nodes)
    for (const w of ways.slice(1)) shared = new Set(w.nodes.filter((n: string) => shared.has(n)))
    const label = ways.map((w) => w.id).join(` ${t('smart.and')} `)
    if (shared.size === 1) {
      const id = [...shared][0]
      this.summary.push(t('smart.sum.sharedNode', { id }))
      return this.bind(step, id)
    }
    if (shared.size > 1) this.fail('several_shared', tn('smart.err.severalShared', shared.size, { label, ids: [...shared].join(', ') }))
    if (ways.length !== 2) this.fail('no_shared', t('smart.err.noShared', { label }))
    const [a, b] = ways
    const hits = crossPoints(this.graph, a, b, false)
    if (hits.length === 0) this.fail('no_intersection', t('smart.err.noIntersection', { a: a.id, b: b.id }))
    if (hits.length > 1) this.fail('several_intersections', t('smart.err.severalIntersections', { a: a.id, b: b.id, count: hits.length }))
    const hit = hits[0]
    const near = (w: any, seg: number) =>
      [w.nodes[seg], w.nodes[seg + 1]].find((n: string) => metres(this.graph.entity(n).loc, hit.loc) < SAME_POINT_M) ?? null
    const aNode = near(a, hit.aSegment)
    const bNode = near(b, hit.bSegment)
    let nodeId: string
    if (aNode) {
      nodeId = aNode
      this.graph = iD.actionAddVertex(b.id, nodeId, hit.bSegment + 1)(this.graph)
      this.edits.push({ kind: 'connected', id: b.id, text: t('smart.edit.connected', { way: b.id, node: nodeId }) })
    } else if (bNode) {
      nodeId = bNode
      this.graph = iD.actionAddVertex(a.id, nodeId, hit.aSegment + 1)(this.graph)
      this.edits.push({ kind: 'connected', id: a.id, text: t('smart.edit.connected', { way: a.id, node: nodeId }) })
    } else {
      const node = new iD.osmNode({ loc: hit.loc })
      nodeId = node.id
      this.graph = this.graph.replace(node)
      this.graph = iD.actionAddVertex(a.id, nodeId, hit.aSegment + 1)(this.graph)
      this.graph = iD.actionAddVertex(b.id, nodeId, hit.bSegment + 1)(this.graph)
      this.edits.push({ kind: 'added', id: nodeId, text: t('smart.edit.addedNode', { id: nodeId }) })
    }
    this.touched()
    this.summary.push(t('smart.sum.joins', { id: nodeId, label }))
    this.bind(step, nodeId)
  }

  splitAround(step: Step) {
    const way = this.single(this.str(step, 'way'), 'way')
    const node = this.single(this.str(step, 'node'), 'node')
    const d = this.distance(step.distance)
    if (way == null || node == null || d == null) return this.bind(step, null)
    const at = this.str(step, 'at', 'nearestNodeOr')
    if (way.isClosed()) this.fail('closed_way', t('smart.err.closedWay', { id: way.id }))
    const k = way.nodes.indexOf(node.id)
    if (k < 0) this.fail('node_not_on_way', t('smart.err.notOnWay', { node: node.id, way: way.id }))
    if (way.nodes.indexOf(node.id, k + 1) >= 0) this.fail('node_twice', t('smart.err.twice', { way: way.id, node: node.id }))
    if (!(d > 0)) this.fail('bad_distance', t('smart.err.distancePositive', { d }))
    const locs = wayLocs(this.graph, way)
    const tolerance = at === 'exact' ? 0.01 : Math.min(0.5, 0.15 * d)
    const last = way.nodes.length - 1

    const target = (dir: 1 | -1): { reuse: string } | { loc: Loc; index: number } | null => {
      let acc = 0
      for (let i = k; i + dir >= 0 && i + dir <= last; i += dir) {
        const j = i + dir
        const seg = metres(locs[i], locs[j])
        if (acc + seg < d) {
          acc += seg
          continue
        }
        const loc = interp(locs[i], locs[j], seg > 0 ? (d - acc) / seg : 0)
        let best: number | null = null
        for (const c of [i, j]) {
          if (c === k) continue
          const m = metres(locs[c], loc)
          if (m <= tolerance && (best == null || m < metres(locs[best], loc))) best = c
        }
        if (best != null) return best === 0 || best === last ? null : { reuse: way.nodes[best] }
        return { loc, index: Math.max(i, j) }
      }
      return null
    }

    const forward = k < last ? target(1) : null
    const backward = k > 0 ? target(-1) : null
    const splitAt: string[] = []
    for (const t of [forward, backward]) {
      if (t == null) continue
      if ('reuse' in t) {
        splitAt.push(t.reuse)
        continue
      }
      const n = new iD.osmNode({ loc: t.loc })
      this.graph = this.graph.replace(n)
      this.graph = iD.actionAddVertex(way.id, n.id, t.index)(this.graph)
      splitAt.push(n.id)
    }
    if (splitAt.length === 0) {
      this.summary.push(t('smart.sum.short', { way: way.id, d: round(d), node: node.id }))
      this.edits.push({ kind: 'kept', id: way.id, text: t('smart.edit.kept', { id: way.id }) })
      return this.bind(step, way.id)
    }
    const action = iD.actionSplit(splitAt).limitWays([way.id])
    this.graph = action(this.graph)
    const pieces: string[] = [way.id, ...action.getCreatedWayIDs()]
    this.touched()
    const piece = pieces.find((p) => this.graph.entity(p).nodes.includes(node.id))
    if (!piece) this.fail('split_failed', t('smart.err.splitFailed', { way: way.id, node: node.id }))
    this.edits.push({ kind: 'split', id: way.id, text: tn('smart.edit.split', pieces.length, { id: way.id }) })
    this.summary.push(tn('smart.sum.split', pieces.length, { way: way.id, at: splitAt.join(', '), d: round(d), node: node.id, piece: piece! }))
    this.bind(step, piece!)
  }

  tag(step: Step) {
    const ref = this.ref(this.str(step, 'target'))
    if (ref == null) return
    const ids = [ref].flat()
    const set = step.set && typeof step.set === 'object' && !Array.isArray(step.set) ? (step.set as Record<string, JsonValue>) : {}
    const remove = Array.isArray(step.remove) ? step.remove.filter((k): k is string => typeof k === 'string') : []
    for (const id of ids) {
      const e = this.graph.hasEntity(id)
      if (!e) this.fail('missing', t('smart.err.missing', { id }))
      const tags: Record<string, string> = { ...e.tags }
      for (const k of remove) delete tags[k]
      for (const [k, raw] of Object.entries(set)) {
        const v = String(raw ?? '').replace(/\{([^{}]*)\}/g, (_, p: string) => this.params[p] ?? '').trim()
        if (v === '') delete tags[k]
        else tags[k] = v
      }
      if (sameTags(tags, e.tags)) continue
      this.graph = iD.actionChangeTags(id, tags)(this.graph)
      const changes = [...Object.entries(set).map(([k]) => (k in tags ? `${k}=${tags[k]}` : `-${k}`)), ...remove.filter((k) => !(k in set)).map((k) => `-${k}`)]
      this.summary.push(`${id}: ${changes.join(', ')}`)
      if (e.type === 'node' && tags.highway === 'crossing') this.edits.push({ kind: 'crossing', id, text: t('smart.edit.crossing', { id }) })
      else this.edits.push({ kind: 'tagged', id, text: t('smart.edit.tagged', { id }) })
    }
    this.touched()
  }
}

function round(n: number): string {
  return String(Math.round(n * 100) / 100)
}

function sameTags(a: Record<string, string>, b: Record<string, string>): boolean {
  const ak = Object.keys(a)
  if (ak.length !== Object.keys(b).length) return false
  return ak.every((k) => a[k] === b[k])
}

function changes(before: any, after: any): { created: string[]; modified: string[] } {
  const created: string[] = []
  const modified: string[] = []
  for (const id of Object.keys(after.entities)) {
    const a = after.entities[id]
    const b = before.hasEntity(id)
    if (a === b || !a) continue
    if (b) modified.push(id)
    else created.push(id)
  }
  return { created, modified }
}

export function runSmartPreset(graph: any, preset: SmartPreset, selfId: string, params: Record<string, string>): SmartRunResult {
  const runner = new Runner(graph, preset, { ...defaultSmartParams(preset), ...params })
  runner.run(selfId)
  const bindings: Record<string, string | string[]> = {}
  for (const [k, v] of Object.entries(runner.bindings)) if (v != null) bindings[k] = v
  const { created, modified } = changes(graph, runner.graph)
  const split = runner.edits.some((x) => x.kind === 'split')
  const edits = runner.edits.filter((e) => {
    if (split && e.kind === 'tagged') return false
    if (e.kind === 'added') return !runner.edits.some((x) => x.kind === 'crossing' && x.id === e.id)
    return true
  })
  return {
    graph: runner.graph,
    annotation: edits.length ? `${preset.name}: ${edits.map((e) => e.text).join(', ')}` : preset.name,
    bindings,
    created,
    modified,
    summary: runner.summary,
  }
}

export type SmartPresetAction = ((graph: any) => any) & { disabled(graph: any): string | false }

export function smartPresetAction(preset: SmartPreset, selfId: string, params: Record<string, string>): SmartPresetAction {
  const action = ((graph: any) => runSmartPreset(graph, preset, selfId, params).graph) as SmartPresetAction
  action.disabled = (graph: any) => {
    try {
      runSmartPreset(graph, preset, selfId, params)
      return false
    } catch (e) {
      if (e instanceof SmartPresetError) return e.code
      throw e
    }
  }
  return action
}
