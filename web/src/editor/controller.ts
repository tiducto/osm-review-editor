import type {
  ExpressionSpecification,
  FilterSpecification,
  GeoJSONSource,
  LayerSpecification,
  Map as MlMap,
  MapGeoJSONFeature,
  MapMouseEvent,
  PointLike,
} from 'maplibre-gl'
import type { Feature, FeatureCollection } from 'geojson'
import { DETAIL_MIN_ZOOM, LAYER, MAX_BAND_ZOOM, OSM_SRC, SHADOW, osmLayers } from '@/map/osm/style'
import { t } from '@/i18n'
import { ensureIcon } from '@/map/osm/icons'
import { setFilterPart } from '@/map/filters'
import { applyFeatureFilters } from '@/map/osm/features'
import { getDashState } from '@/stores/map'
import type { IdAdapter } from './id/adapter'
import { bandFeatureIds, EDIT_LAYERS, featureId, midpoints, render, tileFeatureId, type EditLayer } from './render'
import { issueLayerIds } from './issueLayer'
import { lassoNodes } from './lasso'
import type { Bounds, IdEntity, IdNode, IdRelation, IdWay, LngLatTuple, SnapTarget } from './types'

export type EditMode = 'browse' | 'select' | 'add-point' | 'add-line' | 'add-area' | 'draw' | 'move' | 'rotate'

export interface ControllerHooks {
  selectionChanged(ids: string[]): void
  modeChanged(mode: EditMode): void
  historyChanged(): void
  openMenu(x: number, y: number): void
  hover(info: { x: number; y: number; id: string } | null): void
  notice(text: string): void
  featureCreated?(id: string): void
  issueClicked?(id: string): void
}

const SRC: Record<EditLayer | 'mid' | 'draw' | 'lasso', string> = {
  area: 'ore-edit-area',
  band: 'ore-edit-band',
  line: 'ore-edit-line',
  vertex: 'ore-edit-vertex',
  point: 'ore-edit-point',
  label: 'ore-edit-label',
  mid: 'ore-edit-mid',
  draw: 'ore-edit-draw',
  lasso: 'ore-edit-lasso',
}

const EMPTY: FeatureCollection = { type: 'FeatureCollection', features: [] }
const NODE_LAYERS = ['edit-osm-vertex', 'edit-osm-pin', 'edit-osm-addr', 'edit-osm-point-dot']
const WAY_LAYERS = ['edit-osm-line-hit', 'edit-osm-area-hit']
const BAND_EDIT_LAYERS = Array.from({ length: MAX_BAND_ZOOM - DETAIL_MIN_ZOOM + 1 }, (_, i) => `edit-osm-band-${DETAIL_MIN_ZOOM + i}`).flatMap((id) => [id, `${id}-pattern`])
const FILL_LAYERS = ['edit-osm-fill', 'edit-osm-fill-pattern', ...BAND_EDIT_LAYERS]
const PARTIAL_LIMIT = 400
const MID_LAYER = 'edit-midpoint'
const TILE_GROUPS: Record<string, 'ways' | 'nodes'> = { area: 'ways', band: 'ways', label: 'ways', line: 'ways', vertex: 'nodes', point: 'nodes' }

const hovered: ExpressionSpecification = ['boolean', ['feature-state', 'hover'], false]
const selected: ExpressionSpecification = ['boolean', ['feature-state', 'sel'], false]
const shadowOpacity = (hover: number, sel: number): ExpressionSpecification => ['case', selected, sel, hovered, hover, 0]

type Hit =
  | { kind: 'entity'; id: string; layer: string }
  | { kind: 'issue'; id: string }
  | { kind: 'midpoint'; way: string; edge: [string, string]; loc: LngLatTuple }

interface Lasso {
  points: [number, number][]
  active: boolean
}

interface Pointer {
  x: number
  y: number
  hit: Hit
  dragging: boolean
}

function kindOf(id: string): 'node' | 'way' | 'relation' {
  return id[0] === 'n' ? 'node' : id[0] === 'w' ? 'way' : 'relation'
}

export class EditController {
  mode: EditMode = 'browse'
  private hoverId: string | null = null
  private pointer: Pointer | null = null
  private suppressClick = false
  private frame = 0
  private midFrame = 0
  private stateIds = new Map<string, Set<string>>()
  private installed = false
  private unsubscribe: (() => void) | null = null
  private cursorLoc: LngLatTuple | null = null
  private hideCount = -1
  private lasso: Lasso | null = null
  private fullPending = false
  private pendingIds = new Set<string>()
  fullEveryFrame = false
  private iconsTried = new Set<string>()
  stats = { full: 0, partial: 0, lastMs: 0 }

  constructor(
    readonly map: MlMap,
    readonly adapter: IdAdapter,
    private hooks: ControllerHooks,
  ) {}

  attach() {
    this.install()
    this.syncView()
    const m = this.map
    m.on('mousemove', this.onMove)
    m.on('mousedown', this.onDown)
    m.on('mouseup', this.onUp)
    m.on('click', this.onClick)
    m.on('contextmenu', this.onContext)
    m.on('move', this.onMapMove)
    m.on('moveend', this.onMoveEnd)
    m.on('mouseout', this.onOut)
    m.boxZoom.disable()
    this.unsubscribe = this.adapter.onChange((diff) => {
      this.hooks.historyChanged()
      this.scheduleChange(diff)
    })
    this.refreshHidden()
    this.scheduleRender()
  }

  detach() {
    const m = this.map
    m.off('mousemove', this.onMove)
    m.off('mousedown', this.onDown)
    m.off('mouseup', this.onUp)
    m.off('click', this.onClick)
    m.off('contextmenu', this.onContext)
    m.off('move', this.onMapMove)
    m.off('moveend', this.onMoveEnd)
    m.off('mouseout', this.onOut)
    m.boxZoom.enable()
    m.doubleClickZoom.enable()
    m.getCanvas().style.cursor = ''
    this.unsubscribe?.()
    cancelAnimationFrame(this.frame)
    cancelAnimationFrame(this.midFrame)
    for (const layer of this.tileLayers()) setFilterPart(m, layer.id, 'edit-hide', null)
    for (const layer of (m.getStyle()?.layers ?? []).filter((l) => l.id.startsWith('edit-'))) m.removeLayer(layer.id)
    for (const id of Object.values(SRC)) if (m.getSource(id)) m.removeSource(id)
    this.installed = false
  }

  private install() {
    const m = this.map
    if (this.installed) return
    this.installed = true
    for (const id of Object.values(SRC)) if (!m.getSource(id)) m.addSource(id, { type: 'geojson', data: EMPTY })
    const before = m.getLayer(LAYER.highlightSlot) ? LAYER.highlightSlot : undefined
    const dashes = getDashState()?.dashes ?? new Map<string, number[]>()
    for (const spec of osmLayers(m.getCenter().lat, dashes)) {
      const s = spec as LayerSpecification & { source?: string; 'source-layer'?: string }
      if (s.source !== OSM_SRC) continue
      const sl = s['source-layer'] as EditLayer | undefined
      if (!sl || !(EDIT_LAYERS as readonly string[]).includes(sl)) continue
      const copy = { ...s, id: `edit-${s.id}`, source: SRC[sl] } as Record<string, unknown>
      delete copy['source-layer']
      const paint = { ...((s as { paint?: Record<string, unknown> }).paint ?? {}) }
      if (s.id === 'osm-line-shadow' || s.id === 'osm-area-shadow') {
        paint['line-color'] = SHADOW
        paint['line-opacity'] = shadowOpacity(0.4, 0.75)
      }
      if (s.id === 'osm-vertex-shadow' || s.id === 'osm-pin-shadow' || s.id === 'osm-addr-shadow') {
        paint['circle-color'] = SHADOW
        paint['circle-opacity'] = shadowOpacity(0.5, 0.8)
      }
      copy.paint = paint
      if (s.id === 'osm-area-hit') copy.filter = ['!=', ['get', '@mp'], true]
      m.addLayer(copy as LayerSpecification, before)
    }
    m.addLayer(
      {
        id: 'edit-draw-line',
        type: 'line',
        source: SRC.draw,
        paint: { 'line-color': '#7092ff', 'line-width': 2, 'line-dasharray': [3, 2] },
      },
      before,
    )
    m.addLayer({ id: 'edit-lasso-fill', type: 'fill', source: SRC.lasso, paint: { 'fill-color': '#7092ff', 'fill-opacity': 0.12 } }, before)
    m.addLayer({ id: 'edit-lasso-line', type: 'line', source: SRC.lasso, paint: { 'line-color': '#7092ff', 'line-width': 1.5, 'line-dasharray': [2, 2] } }, before)
    m.addLayer(
      {
        id: MID_LAYER,
        type: 'circle',
        source: SRC.mid,
        paint: { 'circle-radius': 4, 'circle-color': '#fff', 'circle-stroke-color': '#7092ff', 'circle-stroke-width': 1.5, 'circle-opacity': 0.9 },
      },
      before,
    )
    applyFeatureFilters(m)
  }

  private tileLayers(): { id: string; sourceLayer: string }[] {
    const out: { id: string; sourceLayer: string }[] = []
    for (const l of this.map.getStyle()?.layers ?? []) {
      const s = l as { source?: string; 'source-layer'?: string }
      if (s.source === OSM_SRC && s['source-layer'] && TILE_GROUPS[s['source-layer']]) out.push({ id: l.id, sourceLayer: s['source-layer'] })
    }
    return out
  }

  refreshHidden() {
    const ids = this.adapter.loadedBaseIds()
    if (ids.length === this.hideCount) return
    this.hideCount = ids.length
    const base = this.adapter.base()
    const ways: number[] = []
    const nodes: number[] = []
    for (const id of ids) {
      const tid = tileFeatureId(id)
      if (tid == null) continue
      if (id[0] === 'n') nodes.push(tid)
      else if (id[0] === 'w') ways.push(tid)
      else {
        const r = base.hasEntity(id) as IdRelation | undefined
        if (r && r.isMultipolygon() && r.isComplete(base)) ways.push(tid)
      }
    }
    const expr = (list: number[]): FilterSpecification | null =>
      list.length ? (['match', ['id'], [...new Set(list)], false, true] as unknown as FilterSpecification) : null
    const byGroup = { ways: expr(ways), nodes: expr(nodes) }
    for (const l of this.tileLayers()) setFilterPart(this.map, l.id, 'edit-hide', byGroup[TILE_GROUPS[l.sourceLayer]])
  }

  syncView() {
    const m = this.map
    const b = m.getBounds()
    const c = m.getCanvas()
    this.adapter.setView({
      center: [m.getCenter().lng, m.getCenter().lat],
      zoom: m.getZoom(),
      width: c.clientWidth,
      height: c.clientHeight,
      bounds: [
        [b.getWest(), b.getSouth()],
        [b.getEast(), b.getNorth()],
      ],
    })
  }

  private paddedBounds(): Bounds {
    const b = this.map.getBounds()
    const w = b.getEast() - b.getWest()
    const h = b.getNorth() - b.getSouth()
    return [
      [b.getWest() - w, b.getSouth() - h],
      [b.getEast() + w, b.getNorth() + h],
    ]
  }

  scheduleRender() {
    this.fullPending = true
    this.requestFrame()
  }

  private scheduleChange(diff: { complete?: () => Record<string, unknown> } | undefined) {
    if (!diff?.complete || this.fullPending || this.fullEveryFrame) {
      this.scheduleRender()
      return
    }
    for (const id of Object.keys(diff.complete())) this.pendingIds.add(id)
    if (this.pendingIds.size > PARTIAL_LIMIT) this.fullPending = true
    this.requestFrame()
  }

  private requestFrame() {
    if (this.frame) return
    this.frame = requestAnimationFrame(() => {
      this.frame = 0
      const t0 = performance.now()
      if (this.fullPending) this.renderNow()
      else if (this.pendingIds.size) this.renderPartial([...this.pendingIds])
      this.pendingIds.clear()
      this.stats.lastMs = performance.now() - t0
    })
  }

  private bandZooms(): number[] {
    const z = Math.floor(this.map.getZoom())
    const from = Math.max(DETAIL_MIN_ZOOM, z - 1)
    const to = Math.min(MAX_BAND_ZOOM, z + 1)
    const out: number[] = []
    for (let bz = from; bz <= to; bz++) out.push(bz)
    return out
  }

  private forcedVertices(): Set<string> {
    const g = this.adapter.graph()
    const out = new Set<string>()
    const addWay = (id: string | null) => {
      const w = id ? (g.hasEntity(id) as IdWay | undefined) : undefined
      if (w && w.type === 'way') w.nodes.forEach((n) => out.add(n))
    }
    for (const id of this.adapter.selected) {
      if (id[0] === 'n') out.add(id)
      else addWay(id)
    }
    if (this.hoverId?.[0] === 'w' && this.mode !== 'browse') addWay(this.hoverId)
    if (this.adapter.drawing) addWay(this.adapter.drawing.wayId)
    const drag = this.adapter.dragging()
    if (drag) out.add(drag)
    return out
  }

  renderNow() {
    const m = this.map
    this.fullPending = false
    this.pendingIds.clear()
    if (!this.installed) return
    this.stats.full++
    const g = this.adapter.graph()
    const ents = this.adapter.intersects(this.paddedBounds())
    const extra: IdEntity[] = []
    for (const id of this.adapter.selected) {
      const e = g.hasEntity(id)
      if (e) extra.push(e)
    }
    const result = render({
      graph: g,
      entities: [...ents, ...extra],
      forcedVertices: this.forcedVertices(),
      presetIcon: (e) => this.adapter.presetIcon(e),
      bandZooms: this.bandZooms(),
    })
    for (const layer of EDIT_LAYERS) (m.getSource(SRC[layer]) as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features: result.layers[layer] })
    this.afterRender(result)
  }

  private afterRender(result: ReturnType<typeof render>) {
    const m = this.map
    const missing = [...result.icons].filter((n) => !m.hasImage(n) && !this.iconsTried.has(n))
    for (const n of missing) this.iconsTried.add(n)
    if (missing.length) void Promise.all(missing.map((n) => ensureIcon(m, n))).then(() => {
      if (missing.some((n) => m.hasImage(n))) this.scheduleRender()
    })
    const ds = getDashState()
    if (ds && result.dashes.size) ds.add([...result.dashes])
    this.renderMidpoints()
    this.renderDraw()
    this.syncStates()
  }

  renderPartial(ids: string[]) {
    const m = this.map
    if (!this.installed) return
    this.stats.partial++
    const g = this.adapter.graph()
    const touched = new Set<string>(ids)
    const entities: IdEntity[] = []
    for (const id of ids) {
      const e = g.hasEntity(id)
      if (!e) continue
      entities.push(e)
      if (e.type === 'way') for (const n of (e as IdWay).nodes) touched.add(n)
    }
    const result = render({
      graph: g,
      entities,
      forcedVertices: this.forcedVertices(),
      presetIcon: (e) => this.adapter.presetIcon(e),
      bandZooms: this.bandZooms(),
    })
    const remove = [...touched].map(featureId)
    const bandRemove = [...touched].filter((id) => id[0] !== 'n').flatMap(bandFeatureIds)
    for (const layer of EDIT_LAYERS) {
      const src = m.getSource(SRC[layer]) as GeoJSONSource | undefined
      if (!src) continue
      void src.updateData({ remove: layer === 'band' ? bandRemove : remove, add: result.layers[layer] })
    }
    this.afterRender(result)
  }

  private renderMidpoints() {
    const ways = this.mode === 'select' ? this.adapter.selected.filter((id) => id[0] === 'w') : []
    const g = this.adapter.graph()
    const features = ways.length ? midpoints(g, ways, (l) => this.adapter.project(l)) : []
    ;(this.map.getSource(SRC.mid) as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features })
  }

  private renderDraw() {
    const src = this.map.getSource(SRC.draw) as GeoJSONSource | undefined
    if (!src) return
    const d = this.adapter.drawing
    const tip = this.adapter.drawingTip()
    const cursor = this.cursorLoc
    if (this.mode !== 'draw' || !d || !tip || !cursor) {
      src.setData(EMPTY)
      return
    }
    const g = this.adapter.graph()
    const tipNode = g.hasEntity(tip) as IdNode | undefined
    const way = g.hasEntity(d.wayId) as IdWay | undefined
    if (!tipNode || !way) {
      src.setData(EMPTY)
      return
    }
    const coords: LngLatTuple[] = [tipNode.loc, cursor]
    if (d.kind === 'area') {
      const first = g.hasEntity(way.first()) as IdNode | undefined
      if (first) coords.push(first.loc)
    }
    const f: Feature = { type: 'Feature', geometry: { type: 'LineString', coordinates: coords }, properties: {} }
    src.setData({ type: 'FeatureCollection', features: [f] })
  }

  private setStates(key: string, ids: string[]) {
    const prev = this.stateIds.get(key) ?? new Set<string>()
    const next = new Set(ids)
    const apply = (id: string, value: boolean) => {
      const fid = featureId(id)
      const sources = id[0] === 'n' ? [SRC.vertex, SRC.point] : [SRC.line, SRC.area]
      for (const source of sources) if (this.map.getSource(source)) this.map.setFeatureState({ source, id: fid }, { [key]: value })
    }
    for (const id of prev) if (!next.has(id)) apply(id, false)
    for (const id of next) apply(id, true)
    this.stateIds.set(key, next)
  }

  private syncStates() {
    this.setStates('sel', this.adapter.selected)
    this.setStates('hover', this.hoverId ? [this.hoverId] : [])
  }

  setMode(mode: EditMode) {
    if (this.mode === mode) return
    this.mode = mode
    const drawing = mode === 'add-point' || mode === 'add-line' || mode === 'add-area' || mode === 'draw'
    if (drawing) this.map.doubleClickZoom.disable()
    else this.map.doubleClickZoom.enable()
    this.map.getCanvas().style.cursor = drawing || mode === 'move' || mode === 'rotate' ? 'crosshair' : ''
    this.hooks.modeChanged(mode)
    this.scheduleRender()
  }

  select(ids: string[]) {
    this.adapter.selected = [...ids]
    this.setMode(ids.length ? 'select' : 'browse')
    this.hooks.selectionChanged(this.adapter.selected)
    this.scheduleRender()
  }

  startTransform(kind: 'move' | 'rotate', ids: string[]) {
    if (!ids.length) return
    const loc = this.cursorLoc ?? ([this.map.getCenter().lng, this.map.getCenter().lat] as LngLatTuple)
    this.adapter.beginTransform(kind, ids, loc)
    this.setMode(kind)
  }

  startContinue(ids: string[]): boolean {
    const g = this.adapter.graph()
    const vertex = ids.find((id) => id[0] === 'n')
    if (!vertex) return false
    const node = g.hasEntity(vertex)
    if (!node) return false
    let way = ids.find((id) => id[0] === 'w')
    if (!way) {
      const candidates = g.parentWays(node).filter((w) => !w.isClosed() && (w.first() === vertex || w.last() === vertex))
      if (candidates.length !== 1) return false
      way = candidates[0].id
    }
    if (!this.adapter.continueWay(way, vertex)) return false
    this.setMode('draw')
    this.hooks.selectionChanged(this.adapter.selected)
    return true
  }

  escape(): boolean {
    if (this.lasso) {
      this.lasso = null
      ;(this.map.getSource(SRC.lasso) as GeoJSONSource | undefined)?.setData(EMPTY)
      return true
    }
    if (this.adapter.dragging()) {
      this.adapter.cancelDrag()
      this.pointer = null
      return true
    }
    if (this.mode === 'move' || this.mode === 'rotate') {
      this.adapter.cancelTransform()
      this.select(this.adapter.selected)
      return true
    }
    if (this.mode === 'draw') {
      this.finishDrawing()
      return true
    }
    if (this.mode === 'add-point' || this.mode === 'add-line' || this.mode === 'add-area') {
      this.select(this.adapter.selected)
      return true
    }
    if (this.adapter.selected.length) {
      this.select([])
      return true
    }
    return false
  }

  finishDrawing() {
    const id = this.adapter.finishWay()
    this.select(id ? [id] : [])
    if (id) this.hooks.featureCreated?.(id)
  }

  afterUndoRedo() {
    if (this.mode === 'draw' && !this.adapter.drawing) this.setMode('browse')
    this.hooks.selectionChanged(this.adapter.selected)
    if (!this.adapter.drawing && this.mode !== 'draw') this.setMode(this.adapter.selected.length ? 'select' : 'browse')
    this.scheduleRender()
  }

  private box(p: { x: number; y: number }, r: number): [PointLike, PointLike] {
    return [
      [p.x - r, p.y - r],
      [p.x + r, p.y + r],
    ]
  }

  private layersPresent(ids: string[]): string[] {
    return ids.filter((id) => this.map.getLayer(id))
  }

  private topHit(p: { x: number; y: number }): Hit | null {
    const issueLayers = issueLayerIds()
    const layers = this.layersPresent([...issueLayers, MID_LAYER, ...NODE_LAYERS, ...WAY_LAYERS, ...FILL_LAYERS])
    const hits = this.map.queryRenderedFeatures(this.box(p, 5), { layers })
    const rank = (f: MapGeoJSONFeature) =>
      issueLayers.includes(f.layer.id) ? -1 : f.layer.id === MID_LAYER ? 0 : NODE_LAYERS.includes(f.layer.id) ? 1 : f.layer.id === 'edit-osm-line-hit' ? 2 : f.layer.id === 'edit-osm-area-hit' ? 3 : 4
    const f = hits.sort((a, b) => rank(a) - rank(b))[0]
    if (!f) return null
    const props = f.properties as Record<string, unknown>
    if (issueLayers.includes(f.layer.id)) return { kind: 'issue', id: String(props.issue) }
    if (f.layer.id === MID_LAYER) {
      const g = f.geometry
      return { kind: 'midpoint', way: String(props.way), edge: [String(props.a), String(props.b)], loc: (g.type === 'Point' ? g.coordinates : [0, 0]) as LngLatTuple }
    }
    const id = String(props.eid ?? '')
    return id ? { kind: 'entity', id, layer: f.layer.id } : null
  }

  snapAt(p: { x: number; y: number }, exclude: string | null, free = false): SnapTarget {
    const loc = this.map.unproject([p.x, p.y])
    const none: SnapTarget = { kind: 'none', loc: [loc.lng, loc.lat] }
    if (free) return none
    const hits = this.map.queryRenderedFeatures(this.box(p, 6), { layers: this.layersPresent([...NODE_LAYERS, ...WAY_LAYERS]) })
    const g = this.adapter.graph()
    const usable = hits.filter((f) => {
      const id = String((f.properties as Record<string, unknown>).eid ?? '')
      return id && id !== exclude && g.hasEntity(id)
    })
    const node = usable.find((f) => NODE_LAYERS.includes(f.layer.id))
    usable.sort((a, b) => (a.layer.id === 'edit-osm-line-hit' ? 0 : 1) - (b.layer.id === 'edit-osm-line-hit' ? 0 : 1))
    if (node) {
      const id = String((node.properties as Record<string, unknown>).eid)
      const n = g.entity(id) as IdNode
      return { kind: 'node', id, loc: n.loc }
    }
    for (const f of usable) {
      const id = String((f.properties as Record<string, unknown>).eid)
      if (id[0] !== 'w') continue
      const e = this.adapter.chooseEdge(id, [p.x, p.y], exclude ?? undefined)
      if (e && e.distance <= 12) return { kind: 'way', id, loc: e.loc, edge: e.edge }
    }
    return none
  }

  private setHover(id: string | null, p?: { x: number; y: number }) {
    const prev = this.hoverId
    this.hoverId = id
    this.hooks.hover(id && p ? { x: p.x, y: p.y, id } : null)
    if (id !== prev) {
      if (this.mode !== 'browse' && (id?.[0] === 'w' || prev?.[0] === 'w')) this.scheduleRender()
      else this.syncStates()
    }
  }

  private onMapMove = () => {
    this.syncView()
    if (this.mode === 'select' && !this.midFrame) {
      this.midFrame = requestAnimationFrame(() => {
        this.midFrame = 0
        this.renderMidpoints()
      })
    }
  }

  private onMoveEnd = () => {
    this.syncView()
    this.scheduleRender()
  }

  private onOut = () => {
    if (!this.pointer) this.setHover(null)
  }

  private onMove = (e: MapMouseEvent) => {
    const p = e.point
    this.cursorLoc = [e.lngLat.lng, e.lngLat.lat]
    this.adapter.mouseLoc = this.cursorLoc
    if (this.lasso) {
      const first = this.lasso.points[0]
      if (!this.lasso.active && Math.hypot(p.x - first[0], p.y - first[1]) > 3) this.lasso.active = true
      if (this.lasso.active) {
        this.lasso.points.push([p.x, p.y])
        this.renderLasso()
      }
      return
    }
    const ptr = this.pointer
    if (ptr && !ptr.dragging && Math.hypot(p.x - ptr.x, p.y - ptr.y) > 3) {
      if (ptr.hit.kind === 'midpoint') this.adapter.beginMidpointDrag(ptr.hit.edge, ptr.hit.loc)
      else this.adapter.beginDrag(ptr.hit.id)
      ptr.dragging = true
    }
    if (ptr?.dragging) {
      const dragId = this.adapter.dragging()
      const target = this.snapAt(p, dragId, e.originalEvent.altKey)
      this.adapter.dragTo(target.loc)
      this.setHover(target.kind === 'none' ? null : target.id, p)
      return
    }
    if (this.adapter.transforming()) {
      this.adapter.transformTo(this.cursorLoc)
      return
    }
    if (this.mode === 'draw' || this.mode === 'add-point' || this.mode === 'add-line' || this.mode === 'add-area') {
      const target = this.snapAt(p, null)
      this.setHover(target.kind === 'none' ? null : target.id, p)
      if (this.mode === 'draw') this.renderDraw()
      return
    }
    const hit = this.topHit(p)
    const id = hit?.kind === 'entity' ? hit.id : null
    this.map.getCanvas().style.cursor = hit ? 'pointer' : e.originalEvent.shiftKey ? 'crosshair' : ''
    this.setHover(id, p)
  }

  private onDown = (e: MapMouseEvent) => {
    if (e.originalEvent.button !== 0) return
    if (this.mode !== 'browse' && this.mode !== 'select') return
    if (e.originalEvent.shiftKey) {
      this.lasso = { points: [[e.point.x, e.point.y]], active: false }
      e.preventDefault()
      return
    }
    const hit = this.topHit(e.point)
    if (!hit || hit.kind === 'issue') return
    if (hit.kind === 'midpoint' || (hit.kind === 'entity' && kindOf(hit.id) === 'node')) {
      this.pointer = { x: e.point.x, y: e.point.y, hit, dragging: false }
      e.preventDefault()
    }
  }

  private finishLasso(): boolean {
    const lasso = this.lasso
    this.lasso = null
    if (!lasso) return false
    ;(this.map.getSource(SRC.lasso) as GeoJSONSource | undefined)?.setData(EMPTY)
    if (!lasso.active || lasso.points.length < 3) return false
    const polygon = lasso.points.map(([x, y]) => {
      const l = this.map.unproject([x, y])
      return [l.lng, l.lat] as LngLatTuple
    })
    const ids = lassoNodes(this.adapter, polygon)
    this.suppressClick = true
    setTimeout(() => (this.suppressClick = false), 0)
    if (ids.length) this.select(ids)
    else this.hooks.notice(t('edit.lassoEmpty'))
    return true
  }

  private renderLasso() {
    const src = this.map.getSource(SRC.lasso) as GeoJSONSource | undefined
    if (!src || !this.lasso) return
    const coords = this.lasso.points.map(([x, y]) => {
      const l = this.map.unproject([x, y])
      return [l.lng, l.lat]
    })
    if (coords.length < 3) return
    src.setData({ type: 'Feature', geometry: { type: 'Polygon', coordinates: [[...coords, coords[0]]] }, properties: {} })
  }

  private onUp = (e: MapMouseEvent) => {
    if (this.lasso) {
      this.finishLasso()
      return
    }
    const ptr = this.pointer
    this.pointer = null
    if (!ptr?.dragging) return
    const dragId = this.adapter.dragging()
    const target = this.snapAt(e.point, dragId, e.originalEvent.altKey)
    const id = this.adapter.endDrag(target)
    this.suppressClick = true
    setTimeout(() => (this.suppressClick = false), 0)
    if (ptr.hit.kind === 'midpoint' && id) this.select([id])
    else this.scheduleRender()
  }

  private onClick = (e: MapMouseEvent) => {
    if (this.suppressClick) {
      this.suppressClick = false
      return
    }
    const p = e.point
    switch (this.mode) {
      case 'browse':
      case 'select': {
        const hit = this.topHit(p)
        if (hit?.kind === 'issue') {
          this.hooks.issueClicked?.(hit.id)
          return
        }
        if (hit?.kind === 'midpoint') {
          this.adapter.beginMidpointDrag(hit.edge, hit.loc)
          const id = this.adapter.endDrag(null)
          if (id) this.select([id])
          return
        }
        if (!hit) {
          this.select([])
          return
        }
        const shift = e.originalEvent.shiftKey
        const cur = this.adapter.selected
        if (shift) this.select(cur.includes(hit.id) ? cur.filter((x) => x !== hit.id) : [...cur, hit.id])
        else this.select([hit.id])
        return
      }
      case 'add-point': {
        const id = this.adapter.addPoint(this.snapAt(p, null, e.originalEvent.altKey))
        this.select([id])
        this.hooks.featureCreated?.(id)
        return
      }
      case 'add-line':
      case 'add-area': {
        this.adapter.startWay(this.mode === 'add-line' ? 'line' : 'area', this.snapAt(p, null, e.originalEvent.altKey))
        this.hooks.selectionChanged(this.adapter.selected)
        this.setMode('draw')
        return
      }
      case 'draw': {
        const r = this.adapter.extendWay(this.snapAt(p, null, e.originalEvent.altKey))
        if (r === 'finished' || !this.adapter.drawing) {
          this.select(this.adapter.selected)
          if (r === 'finished' && this.adapter.selected.length === 1) this.hooks.featureCreated?.(this.adapter.selected[0])
        } else this.scheduleRender()
        return
      }
      case 'move':
      case 'rotate': {
        this.adapter.endTransform()
        this.select(this.adapter.selected)
        return
      }
    }
  }

  private onContext = (e: MapMouseEvent) => {
    e.preventDefault()
    if (this.mode !== 'browse' && this.mode !== 'select') return
    const hit = this.topHit(e.point)
    if (hit?.kind === 'entity' && !this.adapter.selected.includes(hit.id)) this.select([hit.id])
    if (this.adapter.selected.length) this.hooks.openMenu(e.point.x, e.point.y)
  }
}
