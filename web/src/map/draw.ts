import type { GeoJSONSource, Map as MlMap, MapMouseEvent } from 'maplibre-gl'
import type { Feature, FeatureCollection, Polygon, Position } from 'geojson'
import { SRC } from './layers'
import { empty } from './data'

const CLOSE_PX = 12
const DUP_PX = 5

export interface DrawState {
  count: number
  canFinish: boolean
}

let endedAt = 0

export function drawJustEnded(): boolean {
  return Date.now() - endedAt < 400
}

function dist(a: { x: number; y: number }, b: { x: number; y: number }): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

export class PolygonDraw {
  private pts: Position[] = []
  private cursor: Position | null = null
  private active = false
  private restoreDblZoom = false

  constructor(
    private readonly map: MlMap,
    private readonly handlers: {
      finish: (p: Polygon) => void
      cancel: () => void
      change: (s: DrawState) => void
    },
  ) {}

  get isActive(): boolean {
    return this.active
  }

  start(): void {
    if (this.active) return
    this.active = true
    this.pts = []
    this.cursor = null
    this.restoreDblZoom = this.map.doubleClickZoom.isEnabled()
    this.map.doubleClickZoom.disable()
    this.map.getCanvas().style.cursor = 'crosshair'
    this.map.on('click', this.onClick)
    this.map.on('dblclick', this.onDblClick)
    this.map.on('mousemove', this.onMove)
    window.addEventListener('keydown', this.onKey, true)
    this.render()
  }

  stop(): void {
    if (!this.active) return
    this.active = false
    endedAt = Date.now()
    this.map.off('click', this.onClick)
    this.map.off('dblclick', this.onDblClick)
    this.map.off('mousemove', this.onMove)
    window.removeEventListener('keydown', this.onKey, true)
    if (this.restoreDblZoom) this.map.doubleClickZoom.enable()
    this.map.getCanvas().style.cursor = ''
    this.pts = []
    this.cursor = null
    this.setData(empty)
  }

  finish(): boolean {
    if (this.pts.length < 3) return false
    const ring = [...this.pts, this.pts[0]]
    this.stop()
    this.handlers.finish({ type: 'Polygon', coordinates: [ring] })
    return true
  }

  cancel(): void {
    this.stop()
    this.handlers.cancel()
  }

  undo(): void {
    this.pts.pop()
    this.render()
  }

  redraw(): void {
    if (this.active) this.render()
  }

  private project(p: Position) {
    return this.map.project(p as [number, number])
  }

  private nearFirst(point: { x: number; y: number }): boolean {
    return this.pts.length >= 3 && dist(this.project(this.pts[0]), point) <= CLOSE_PX
  }

  private readonly onClick = (e: MapMouseEvent) => {
    if (this.nearFirst(e.point)) {
      this.finish()
      return
    }
    const last = this.pts.at(-1)
    if (last && dist(this.project(last), e.point) <= DUP_PX) return
    this.pts.push([e.lngLat.lng, e.lngLat.lat])
    this.render()
  }

  private readonly onDblClick = (e: MapMouseEvent) => {
    e.preventDefault()
    this.finish()
  }

  private readonly onMove = (e: MapMouseEvent) => {
    this.cursor = [e.lngLat.lng, e.lngLat.lat]
    this.map.getCanvas().style.cursor = this.nearFirst(e.point) ? 'pointer' : 'crosshair'
    this.render()
  }

  private readonly onKey = (e: KeyboardEvent) => {
    const t = e.target as HTMLElement | null
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
    if (e.key === 'Escape') {
      e.preventDefault()
      e.stopImmediatePropagation()
      this.cancel()
    } else if (e.key === 'Enter') {
      e.preventDefault()
      e.stopImmediatePropagation()
      this.finish()
    } else if (e.key === 'Backspace' || ((e.metaKey || e.ctrlKey) && e.key === 'z')) {
      e.preventDefault()
      e.stopImmediatePropagation()
      this.undo()
    }
  }

  private setData(fc: FeatureCollection): void {
    const src = this.map.getSource(SRC.draw) as GeoJSONSource | undefined
    src?.setData(fc)
  }

  private render(): void {
    const pts = this.pts
    const out: Feature[] = []
    if (pts.length >= 3) {
      out.push({ type: 'Feature', properties: { g: 'polygon' }, geometry: { type: 'Polygon', coordinates: [[...pts, pts[0]]] } })
    }
    if (pts.length >= 2) {
      out.push({ type: 'Feature', properties: { g: 'line' }, geometry: { type: 'LineString', coordinates: pts } })
    }
    if (pts.length && this.cursor) {
      const coords = pts.length >= 2 ? [pts[pts.length - 1], this.cursor, pts[0]] : [pts[0], this.cursor]
      out.push({ type: 'Feature', properties: { g: 'line', preview: true }, geometry: { type: 'LineString', coordinates: coords } })
    }
    pts.forEach((p, i) => out.push({ type: 'Feature', properties: { g: 'point', first: i === 0 }, geometry: { type: 'Point', coordinates: p } }))
    this.setData({ type: 'FeatureCollection', features: out })
    this.handlers.change({ count: pts.length, canFinish: pts.length >= 3 })
  }
}
