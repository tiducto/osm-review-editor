import type { Feature, FeatureCollection, Geometry, Point, Position } from 'geojson'
import type { Area, Change, MapFeature, OsmRef } from '@/generated/models'
import type { ShownIssue } from '@/stores/rules'
import { asGeometry } from '@/lib/geo'
import { refString } from '@/lib/osm'
import { severityRank, changeKindColor } from '@/lib/colors'
import { changeKind } from '@/lib/changes'

export const empty: FeatureCollection = { type: 'FeatureCollection', features: [] }

type Props = Record<string, string | number | boolean | null>

function geomClass(g: Geometry): 'point' | 'line' | 'polygon' {
  switch (g.type) {
    case 'Point':
    case 'MultiPoint':
      return 'point'
    case 'LineString':
    case 'MultiLineString':
      return 'line'
    case 'Polygon':
    case 'MultiPolygon':
      return 'polygon'
    case 'GeometryCollection':
      return g.geometries[0] ? geomClass(g.geometries[0]) : 'point'
  }
}

function feature(g: Geometry, props: Props): Feature {
  return { type: 'Feature', geometry: g, properties: { ...props, g: geomClass(g) } }
}

export function featuresToGeoJSON(list: MapFeature[]): FeatureCollection {
  const out: Feature[] = []
  for (const f of list) {
    const g = asGeometry(f.geometry)
    if (g) out.push(feature(g, { key: refString(f.ref), name: f.tags.name ?? null }))
  }
  return { type: 'FeatureCollection', features: out }
}

function wayNodes(g: Geometry): Position[] {
  switch (g.type) {
    case 'LineString':
      return g.coordinates
    case 'MultiLineString':
      return g.coordinates.flat()
    case 'Polygon':
      return g.coordinates.flatMap((r) => r.slice(0, -1))
    case 'MultiPolygon':
      return g.coordinates.flatMap((p) => p.flatMap((r) => r.slice(0, -1)))
    default:
      return []
  }
}

export function selectionToGeoJSON(f: MapFeature | null, vertex: boolean): FeatureCollection {
  if (!f) return empty
  const g = asGeometry(f.geometry)
  if (!g) return empty
  const out: Feature[] = [feature(g, { key: refString(f.ref), vertex })]
  if (f.ref.type === 'way') {
    const nodes = wayNodes(g)
    if (nodes.length) out.push({ type: 'Feature', geometry: { type: 'MultiPoint', coordinates: nodes }, properties: { g: 'nodes' } })
  }
  return { type: 'FeatureCollection', features: out }
}

export function issuesToGeoJSON(list: ShownIssue[]): FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: list.map(({ issue, color }, i): Feature<Point> => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [issue.centroid.lng, issue.centroid.lat] },
      properties: { i, key: refString(issue.ref), ruleId: issue.ruleId, color, sev: severityRank[issue.severity] },
    })),
  }
}

export function changesToGeoJSON(list: Change[], currentId: string | null): FeatureCollection {
  const out: Feature[] = []
  list.forEach((c, i) => {
    const g =
      asGeometry(c.action === 'delete' ? c.baseGeom : (c.newGeom ?? c.baseGeom)) ??
      (c.centroid ? ({ type: 'Point', coordinates: [c.centroid.lng, c.centroid.lat] } as Point) : null)
    if (!g) return
    const kind = changeKind(c)
    out.push(
      feature(g, {
        i,
        id: c.id,
        kind,
        color: changeKindColor[kind],
        status: c.status,
        current: c.id === currentId,
        done: c.status === 'approved' || c.status === 'rejected' || c.status === 'uploaded',
      }),
    )
  })
  return { type: 'FeatureCollection', features: out }
}

export function beforeToGeoJSON(c: Change | null, showBefore: boolean): FeatureCollection {
  if (!c) return empty
  const base = asGeometry(c.baseGeom)
  if (!base) return empty
  if (!showBefore && !c.newGeom) return empty
  const color = changeKindColor[changeKind(c)]
  return { type: 'FeatureCollection', features: [feature(base, { mode: showBefore ? 'solid' : 'ghost', color })] }
}

export function areaToGeoJSON(a: Area | null): FeatureCollection {
  const g = asGeometry(a?.geometry)
  if (g) return { type: 'FeatureCollection', features: [feature(g, { name: a?.name ?? '' })] }
  if (!a) return empty
  const { west, south, east, north } = a.bbox
  const ring = [[west, south], [east, south], [east, north], [west, north], [west, south]]
  return { type: 'FeatureCollection', features: [feature({ type: 'Polygon', coordinates: [ring] }, { name: a.name })] }
}

export function keysOf(refs: OsmRef[]): string[] {
  return refs.map(refString)
}
