import type { BBox, JsonValue } from '@/generated/models'
import type { ScopeBody } from '@/api/rules'

export interface Scope {
  kind: 'area' | 'polygon' | 'view'
  label: string
  key: string
  body: ScopeBody
}

export function roundBBox(b: BBox, digits = 4): BBox {
  const f = 10 ** digits
  return {
    west: Math.floor(b.west * f) / f,
    south: Math.floor(b.south * f) / f,
    east: Math.ceil(b.east * f) / f,
    north: Math.ceil(b.north * f) / f,
  }
}

export function scopeOf(area: { name: string } | null, polygon: JsonValue | null, view: BBox | null): Scope | null {
  if (area) return { kind: 'area', label: area.name, key: `area:${area.name}`, body: { area: area.name } }
  if (polygon) return { kind: 'polygon', label: 'Drawn polygon', key: `polygon:${JSON.stringify(polygon).length}:${hash(JSON.stringify(polygon))}`, body: { polygon } }
  if (view) {
    const b = roundBBox(view)
    return { kind: 'view', label: 'Map view', key: `bbox:${b.west},${b.south},${b.east},${b.north}`, body: { bbox: b } }
  }
  return null
}

export function hash(s: string): string {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(36)
}

export function tileKey(z: number, x: number, y: number): string {
  return `${z}/${x}/${y}`
}

export function tilesFor(b: BBox, z: number, max: number): string[] | null {
  const n = 2 ** z
  const x = (lon: number) => Math.min(n - 1, Math.max(0, Math.floor(((lon + 180) / 360) * n)))
  const y = (lat: number) => {
    const r = (Math.max(-85, Math.min(85, lat)) * Math.PI) / 180
    return Math.min(n - 1, Math.max(0, Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n)))
  }
  const x0 = x(b.west)
  const x1 = x(b.east)
  const y0 = y(b.north)
  const y1 = y(b.south)
  if ((x1 - x0 + 1) * (y1 - y0 + 1) > max) return null
  const out: string[] = []
  for (let tx = x0; tx <= x1; tx++) for (let ty = y0; ty <= y1; ty++) out.push(tileKey(z, tx, ty))
  return out
}

export function formatKm(v: number): string {
  if (v >= 100) return `${Math.round(v).toLocaleString()} km`
  if (v >= 1) return `${v.toFixed(1)} km`
  return `${Math.round(v * 1000)} m`
}

export function formatArea(v: number): string {
  if (v >= 1) return `${v.toFixed(2)} km²`
  return `${Math.round(v * 1e6).toLocaleString()} m²`
}
