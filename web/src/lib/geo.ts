import type { Geometry, Position } from 'geojson'
import type { BBox, JsonValue, LngLat } from '@/generated/models'

export function bboxParam(b: BBox): string {
  return [b.west, b.south, b.east, b.north].map((n) => n.toFixed(7)).join(',')
}

const geometryTypes = new Set(['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon', 'GeometryCollection'])

export function asGeometry(v: JsonValue | null | undefined): Geometry | null {
  if (!v || typeof v !== 'object') return null
  const o = v as { type?: unknown; geometry?: unknown }
  if (o.type === 'Feature') return asGeometry(o.geometry as JsonValue)
  if (typeof o.type === 'string' && geometryTypes.has(o.type)) return v as Geometry
  return null
}

function eachPosition(g: Geometry, fn: (p: Position) => void): void {
  switch (g.type) {
    case 'Point':
      fn(g.coordinates)
      break
    case 'MultiPoint':
    case 'LineString':
      g.coordinates.forEach(fn)
      break
    case 'MultiLineString':
    case 'Polygon':
      g.coordinates.forEach((r) => r.forEach(fn))
      break
    case 'MultiPolygon':
      g.coordinates.forEach((p) => p.forEach((r) => r.forEach(fn)))
      break
    case 'GeometryCollection':
      g.geometries.forEach((c) => eachPosition(c, fn))
      break
  }
}

export function geometryBBox(...geoms: (Geometry | null | undefined)[]): BBox | null {
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity
  for (const g of geoms) {
    if (!g) continue
    eachPosition(g, ([x, y]) => {
      if (x < west) west = x
      if (x > east) east = x
      if (y < south) south = y
      if (y > north) north = y
    })
  }
  return west === Infinity ? null : { west, south, east, north }
}

export function bboxAround(p: LngLat, meters: number): BBox {
  const dLat = meters / 111_320
  const dLng = meters / (111_320 * Math.max(0.2, Math.cos((p.lat * Math.PI) / 180)))
  return { west: p.lng - dLng, south: p.lat - dLat, east: p.lng + dLng, north: p.lat + dLat }
}

export function bboxCenter(b: BBox): LngLat {
  return { lng: (b.west + b.east) / 2, lat: (b.south + b.north) / 2 }
}

export function isPolygonal(g: Geometry | null): boolean {
  return !!g && (g.type === 'Polygon' || g.type === 'MultiPolygon')
}

export function padBBox(b: BBox, ratio: number): BBox {
  const dx = (b.east - b.west) * ratio
  const dy = (b.north - b.south) * ratio
  return { west: b.west - dx, south: b.south - dy, east: b.east + dx, north: b.north + dy }
}

export function containsBBox(outer: BBox, inner: BBox): boolean {
  return outer.west <= inner.west && outer.south <= inner.south && outer.east >= inner.east && outer.north >= inner.north
}
