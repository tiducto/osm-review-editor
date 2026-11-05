import type { Feature, FeatureCollection, LineString, MultiLineString, MultiPolygon, Polygon, Position } from 'geojson'
import { areaStyle, hasInterestingTags, isAddressPoint, isWikidata, lineStyle, type Stroke, type Tags } from './classify'
import type { Geometry as PresetGeometry, PresetMatcher } from './presets'

export interface ApiFeature {
  ref: { type: 'node' | 'way' | 'relation'; id: number }
  tags: Tags
  geometry: { type: string; coordinates: unknown }
}

export interface Built {
  areas: FeatureCollection
  lines: FeatureCollection
  oneways: FeatureCollection
  vertices: FeatureCollection
  points: FeatureCollection
  areaLabels: FeatureCollection
  dashes: Map<string, number[]>
  pins: Set<string>
  vertexIcons: Set<string>
  areaIcons: Set<string>
}

const R = 6378137
const key = (p: Position) => p[0].toFixed(7) + ',' + p[1].toFixed(7)

function ringMetrics(ring: Position[]) {
  const lat0 = (ring[0][1] * Math.PI) / 180
  const kx = (Math.PI / 180) * R * Math.cos(lat0)
  const ky = (Math.PI / 180) * R
  let a = 0, per = 0, cx = 0, cy = 0
  let minX = Infinity, maxX = -Infinity
  for (let i = 0; i < ring.length - 1; i++) {
    const x1 = ring[i][0] * kx, y1 = ring[i][1] * ky
    const x2 = ring[i + 1][0] * kx, y2 = ring[i + 1][1] * ky
    const cr = x1 * y2 - x2 * y1
    a += cr
    cx += (x1 + x2) * cr
    cy += (y1 + y2) * cr
    per += Math.hypot(x2 - x1, y2 - y1)
    minX = Math.min(minX, x1)
    maxX = Math.max(maxX, x1)
  }
  a /= 2
  const centroid: Position = a !== 0 ? [cx / (6 * a) / kx, cy / (6 * a) / ky] : ring[0]
  return { signed: a, area: Math.abs(a), perimeter: per, width: maxX - minX, centroid }
}

function rewind(ring: Position[], ccw: boolean): Position[] {
  const signed = ringMetrics(ring).signed
  return (signed > 0) === ccw ? ring : [...ring].reverse()
}

function rewindPolygon(rings: Position[][]): Position[][] {
  return rings.map((r, i) => rewind(r, i === 0))
}

function mppZ0(lat: number) {
  return (2 * Math.PI * R * Math.cos((lat * Math.PI) / 180)) / 512
}

function dashKey(dashes: Map<string, number[]>, s: Stroke, low: boolean): string {
  const d = low ? s.dashl : s.dash
  if (!d) return 'solid'
  const w = low ? s.wl : s.w
  const norm = d.map((x) => +(x / w).toFixed(3))
  const k = norm.join('_')
  dashes.set(k, norm)
  return k
}

function strokeProps(dashes: Map<string, number[]>, prefix: string, s: Stroke | null) {
  if (!s) return { [prefix + 'on']: false }
  return {
    [prefix + 'on']: true,
    [prefix + 'c']: s.color,
    [prefix + 'w']: s.w,
    [prefix + 'wl']: s.wl,
    [prefix + 'o']: s.opacity,
    [prefix + 'cap']: s.cap,
    [prefix + 'd']: dashKey(dashes, s, false),
    [prefix + 'dl']: dashKey(dashes, s, true),
  }
}

const LABEL_AREA_KEYS = ['aeroway', 'amenity', 'building', 'historic', 'leisure', 'man_made', 'natural', 'shop', 'craft', 'tourism']

function onewayDir(t: Tags): number {
  if (t.oneway === 'yes' || t.oneway === '1' || t.oneway === 'true') return 1
  if (t.oneway === '-1' || t.oneway === 'reverse') return -1
  if (t.oneway === 'no') return 0
  if (t.junction === 'roundabout' || t.junction === 'circular' || t.highway === 'motorway') return 1
  if (t.waterway && ['river', 'stream', 'canal', 'ditch', 'drain', 'brook', 'fish_pass', 'tidal_channel'].includes(t.waterway)) return 1
  return 0
}

export interface Hidden {
  buildings: boolean
  points: boolean
  addressPoints: boolean
}

const PARKING_BUILDINGS = new Set(['multi-storey', 'sheds', 'carports', 'garage_boxes'])
const LIFECYCLE = new Set(['proposed', 'planned', 'construction', 'disused', 'abandoned', 'was', 'dismantled', 'razed', 'demolished', 'destroyed', 'removed', 'intermittent'])

function isBuilding(t: Tags): boolean {
  return (!!t.building && t.building !== 'no' && !LIFECYCLE.has(t.building)) || PARKING_BUILDINGS.has(t.parking)
}

export function autoHidden(list: ApiFeature[], viewport: [number, number]): Hidden {
  const cull = (viewport[0] * viewport[1]) / 1e6
  const lineCoords = new Set<string>()
  for (const f of list) {
    if (f.ref.type === 'node') continue
    const g = f.geometry
    const rings: Position[][] =
      g.type === 'LineString' ? [g.coordinates as Position[]]
      : g.type === 'Polygon' ? (g.coordinates as Position[][])
      : g.type === 'MultiPolygon' ? (g.coordinates as Position[][][]).flat()
      : []
    for (const r of rings) for (const p of r) lineCoords.add(key(p))
  }
  let buildings = 0, points = 0, addr = 0
  for (const f of list) {
    if (f.ref.type !== 'node' && isBuilding(f.tags)) buildings++
    if (f.ref.type === 'node' && !lineCoords.has(key(f.geometry.coordinates as Position))) {
      if (isAddressPoint(f.tags)) addr++
      else points++
    }
  }
  return { buildings: buildings > 250 * cull, points: points > 200 * cull, addressPoints: addr > 100 * cull }
}

export function build(all: ApiFeature[], presets: PresetMatcher, hidden: Hidden = { buildings: false, points: false, addressPoints: false }): Built {
  const list = hidden.buildings ? all.filter((f) => f.ref.type === 'node' || !isBuilding(f.tags)) : all
  const dashes = new Map<string, number[]>([['solid', [1, 0]]])
  const pins = new Set<string>()
  const vertexIcons = new Set<string>()
  const areaIcons = new Set<string>()
  const areas: Feature[] = []
  const lines: Feature[] = []
  const oneways: Feature[] = []
  const vertices: Feature[] = []
  const points: Feature[] = []
  const areaLabels: Feature[] = []

  const parents = new Map<string, number>()
  const endpoints = new Set<string>()
  const onWay = new Set<string>()

  for (const f of list) {
    const g = f.geometry
    if (f.ref.type === 'node') continue
    const rings: Position[][] =
      g.type === 'LineString' ? [g.coordinates as Position[]]
      : g.type === 'Polygon' ? (g.coordinates as Position[][])
      : g.type === 'MultiPolygon' ? (g.coordinates as Position[][][]).flat()
      : g.type === 'MultiLineString' ? (g.coordinates as Position[][])
      : []
    for (const ring of rings) for (const p of ring) onWay.add(key(p))
    if (f.ref.type !== 'way') continue
    const coords = rings[0]
    if (!coords) continue
    const seen = new Set<string>()
    for (const p of coords) {
      const k = key(p)
      if (seen.has(k)) continue
      seen.add(k)
      parents.set(k, (parents.get(k) ?? 0) + 1)
    }
    if (g.type === 'LineString') {
      const a = key(coords[0]), b = key(coords[coords.length - 1])
      if (a !== b) { endpoints.add(a); endpoints.add(b) }
    }
  }

  for (const f of list) {
    const t = f.tags
    const g = f.geometry
    const id = `${f.ref.type}/${f.ref.id}`
    if (g.type === 'Polygon' || g.type === 'MultiPolygon') {
      const polys: Position[][][] = g.type === 'Polygon' ? [g.coordinates as Position[][]] : (g.coordinates as Position[][][])
      const rewound = polys.map(rewindPolygon)
      let area = 0, per = 0, width = 0
      let best = ringMetrics(rewound[0][0])
      for (const p of rewound) {
        const m = ringMetrics(p[0])
        area += m.area
        per += m.perimeter
        width = Math.max(width, m.width)
        if (m.area > best.area) best = m
      }
      const st = areaStyle(t)
      const lat = best.centroid[1]
      const mpp = mppZ0(lat)
      const geometry: Polygon | MultiPolygon =
        rewound.length === 1 ? { type: 'Polygon', coordinates: rewound[0] } : { type: 'MultiPolygon', coordinates: rewound }
      areas.push({
        type: 'Feature',
        id: f.ref.id,
        geometry,
        properties: { id, sc: st.stroke, fc: st.fill, sort: -area, r0: (2 * area) / Math.max(per, 1e-9) / mpp, building: !!t.building },
      })
      const preset = presets.match(t, 'area')
      const skipIcon = !preset || /building|landuse|natural/.test(preset.id)
      const icon = !skipIcon && preset?.icon ? preset.icon : null
      if (icon) areaIcons.add(icon)
      const name = t.name && LABEL_AREA_KEYS.some((k) => k in t) ? t.name : ''
      if (icon || name) {
        areaLabels.push({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: best.centroid },
          properties: {
            id,
            icon: icon ? `aicon:${icon}` : '',
            hasIcon: !!icon,
            name,
            w0: width / mpp,
            lw: name.length * 7 + 20,
            sort: -area,
          },
        })
      }
      continue
    }
    if (g.type === 'LineString' || g.type === 'MultiLineString') {
      if (t.boundary && !t.highway && !t.railway && !t.waterway && !t.barrier) continue
      const ls = lineStyle(t)
      const layer = Number.parseInt(t.layer ?? '0', 10) || 0
      const bridge = t.bridge && t.bridge !== 'no' ? 1 : 0
      lines.push({
        type: 'Feature',
        geometry: g as LineString | MultiLineString,
        properties: {
          id,
          sort: (layer + bridge) * 100 + ls.rank,
          name: t.highway || t.railway || t.waterway ? (t.name ?? '') : '',
          ...strokeProps(dashes, 'c', ls.casing),
          ...strokeProps(dashes, 's', ls.stroke),
        },
      })
      const dir = onewayDir(t)
      if (dir !== 0) {
        const dark = !!t.railway || (t.bridge && t.bridge !== 'no' && !!t.construction)
        const coords = g.type === 'LineString' ? [g.coordinates as Position[]] : (g.coordinates as Position[][])
        for (const c of coords) {
          oneways.push({
            type: 'Feature',
            geometry: { type: 'LineString', coordinates: dir < 0 ? [...c].reverse() : c },
            properties: { img: dark ? 'oneway-white' : 'oneway-black', sort: (layer + bridge) * 100 + ls.rank },
          })
        }
      }
      continue
    }
    if (g.type === 'Point') {
      const p = g.coordinates as Position
      const k = key(p)
      const isVertex = onWay.has(k)
      const geom: PresetGeometry = isVertex ? 'vertex' : 'point'
      if (!isVertex && isAddressPoint(t)) {
        if (hidden.addressPoints) continue
        points.push({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: p },
          properties: { id, addr: true, hn: t['addr:housenumber'] ?? t['addr:housename'] ?? '', lat: p[1] },
        })
        continue
      }
      const preset = presets.match(t, geom)
      const icon = preset?.icon ?? null
      if (isVertex) {
        if (icon) vertexIcons.add(icon)
        parents.set(k, Math.max(parents.get(k) ?? 0, 1))
        vertices.push({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: p },
          properties: {
            id,
            tagged: hasInterestingTags(t),
            icon: icon ? `vicon:${icon}` : '',
            hasIcon: !!icon,
            shared: (parents.get(k) ?? 0) > 1,
            loose: endpoints.has(k) && (parents.get(k) ?? 0) <= 1,
            name: t.name ?? '',
            lat: p[1],
          },
        })
        endpoints.delete(k)
        parents.delete(k)
        continue
      }
      if (hidden.points) continue
      const wd = isWikidata(t)
      const pin = `pin:${icon ?? ''}:${wd ? 'w' : ''}`
      pins.add(`${icon ?? ''}|${wd ? 'w' : ''}`)
      points.push({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: p },
        properties: { id, addr: false, pin, name: t.name ?? '', lat: p[1] },
      })
    }
  }

  for (const [k, count] of parents) {
    const isEnd = endpoints.has(k)
    if (count < 2 && !isEnd) continue
    const [lon, lat] = k.split(',').map(Number)
    vertices.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [lon, lat] },
      properties: { tagged: false, icon: '', hasIcon: false, shared: count > 1, loose: isEnd && count <= 1, name: '', lat },
    })
  }

  const fc = (features: Feature[]): FeatureCollection => ({ type: 'FeatureCollection', features })
  return {
    areas: fc(areas),
    lines: fc(lines),
    oneways: fc(oneways),
    vertices: fc(vertices),
    points: fc(points),
    areaLabels: fc(areaLabels),
    dashes,
    pins,
    vertexIcons,
    areaIcons,
  }
}
