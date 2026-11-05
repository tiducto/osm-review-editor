import type { VectorTile, VectorTileFeature, VectorTileLayer } from '@mapbox/vector-tile'
import type { VectorTileFeatureLike, VectorTileLayerLike } from '@maplibre/vt-pbf'
import { areaStyle, hasInterestingTags, isAddressPoint, isWikidata, lineStyle, type Stroke, type Tags } from './classify'
import { areaPattern } from './patterns'
import type { PresetMatcher } from './presets'
import { featureGroup } from './features'

export type Props = Record<string, string | number | boolean>

export interface Transformed {
  layers: Record<string, VectorTileLayerLike>
  icons: string[]
  dashes: string[]
}

export const OUT = {
  area: 'area',
  band: 'band',
  label: 'label',
  line: 'line',
  vertex: 'vertex',
  point: 'point',
  place: 'place',
} as const

export const TILE_DETAIL_ZOOM = 15

const LABEL_AREA_KEYS = ['aeroway', 'amenity', 'building', 'historic', 'leisure', 'man_made', 'natural', 'shop', 'craft', 'tourism']

export function tagsOf(p: Props): Tags {
  const t: Tags = {}
  for (const k in p) if (k[0] !== '@' && k[0] !== '$') t[k] = String(p[k])
  return t
}

export const TILE_PROPS = { type: '$type', closed: '$closed', id: '$id' } as const

export function convention(layer: string, p: Props): Props {
  if (p['@id'] === undefined) return {}
  const closed = layer === OUT.area || layer === OUT.band || p['@closed'] === true
  return { [TILE_PROPS.type]: p['@type'], [TILE_PROPS.id]: p['@id'], [TILE_PROPS.closed]: closed }
}

const RAW_LAYERS: string[] = ['area', 'line', 'point', 'vertex']

export function rawLayers(tile: VectorTile): Record<string, VectorTileLayerLike> {
  const out: Record<string, OutLayer> = {}
  for (const name of RAW_LAYERS) {
    const src = tile.layers[name]
    if (!src) continue
    const layer = (out[name] = new OutLayer(name, src.extent))
    each(src, (f, p) => {
      if (p['@id'] === undefined) return
      layer.push(f, { ...p, ...convention(name, p) })
    })
  }
  return out
}

export function dashKey(s: Stroke, low: boolean): string {
  const d = low ? s.dashl : s.dash
  if (!d) return 'solid'
  const w = low ? s.wl : s.w
  return d.map((x) => +(x / w).toFixed(3)).join('_')
}

export function strokeProps(prefix: string, s: Stroke | null, dashes: Set<string>): Props {
  if (!s) return { [prefix + 'on']: false }
  const d = dashKey(s, false)
  const dl = dashKey(s, true)
  dashes.add(d)
  dashes.add(dl)
  return {
    [prefix + 'on']: true,
    [prefix + 'c']: s.color,
    [prefix + 'w']: s.w,
    [prefix + 'wl']: s.wl,
    [prefix + 'o']: s.opacity,
    [prefix + 'cap']: s.cap,
    [prefix + 'd']: d,
    [prefix + 'dl']: dl,
  }
}

export function knownDashes(): string[] {
  const out = new Set<string>(['solid'])
  const highways = ['motorway', 'primary', 'residential', 'unclassified', 'living_street', 'service', 'track', 'pedestrian', 'corridor', 'steps', 'ladder', 'path', 'footway', 'cycleway', 'bridleway', 'road']
  const variants: Tags[] = [
    {}, { surface: 'gravel' }, { surface: 'sett' }, { bridge: 'yes' }, { tunnel: 'yes' }, { service: 'driveway' },
    { crossing: 'marked' }, { crossing: 'unmarked' }, { footway: 'access_aisle' }, { tracktype: 'grade1' },
    { tracktype: 'grade2' }, { tracktype: 'grade3' }, { tracktype: 'grade4' }, { tracktype: 'grade5' },
  ]
  const samples: Tags[] = [
    ...highways.flatMap((highway) => variants.map((v) => ({ highway, ...v }))),
    { railway: 'rail' }, { railway: 'disused' }, { railway: 'abandoned' }, { railway: 'platform' },
    { barrier: 'wall' }, { barrier: 'fence' }, { barrier: 'hedge' }, { waterway: 'river' }, { waterway: 'ditch' },
    { power: 'line' }, { aerialway: 'cable_car' }, { natural: 'tree_row' },
  ]
  for (const t of samples) {
    const ls = lineStyle(t)
    strokeProps('', ls.casing, out)
    strokeProps('', ls.stroke, out)
  }
  return [...out]
}

export function onewayDir(t: Tags): number {
  if (t.oneway === 'yes' || t.oneway === '1' || t.oneway === 'true') return 1
  if (t.oneway === '-1' || t.oneway === 'reverse') return -1
  if (t.oneway === 'no') return 0
  if (t.junction === 'roundabout' || t.junction === 'circular' || t.highway === 'motorway') return 1
  if (t.waterway && ['river', 'stream', 'canal', 'ditch', 'drain', 'brook', 'fish_pass', 'tidal_channel'].includes(t.waterway)) return 1
  return 0
}

const PARKING_BUILDINGS = new Set(['multi-storey', 'sheds', 'carports', 'garage_boxes'])
const LIFECYCLE = new Set(['proposed', 'planned', 'construction', 'disused', 'abandoned', 'was', 'dismantled', 'razed', 'demolished', 'destroyed', 'removed', 'intermittent'])

export function isBuilding(t: Tags): boolean {
  return (!!t.building && t.building !== 'no' && !LIFECYCLE.has(t.building)) || PARKING_BUILDINGS.has(t.parking)
}

class OutLayer implements VectorTileLayerLike {
  version = 2
  features: VectorTileFeatureLike[] = []
  constructor(public name: string, public extent: number) {}
  get length() {
    return this.features.length
  }
  feature(i: number) {
    return this.features[i]
  }
  push(src: VectorTileFeature, properties: Props) {
    const geometry = src.loadGeometry()
    this.features.push({ type: src.type, id: src.id, extent: src.extent, properties, loadGeometry: () => geometry })
  }
}

function each(layer: VectorTileLayer | undefined, fn: (f: VectorTileFeature, p: Props) => void) {
  if (!layer) return
  for (let i = 0; i < layer.length; i++) {
    const f = layer.feature(i)
    fn(f, f.properties as Props)
  }
}

export function transform(tile: VectorTile, presets: PresetMatcher, z: number): Transformed {
  const out: Record<string, OutLayer> = {}
  const extent = Object.values(tile.layers)[0]?.extent ?? 4096
  const layer = (name: string) => (out[name] ??= new OutLayer(name, extent))
  const icons = new Set<string>()
  const dashes = new Set<string>()
  const detail = z >= TILE_DETAIL_ZOOM

  const areas = new Map<string, { fc: string; pat: string; sort: number; props: Props }>()
  each(tile.layers.area, (f, p) => {
    const t = tagsOf(p)
    const st = areaStyle(t)
    const pattern = areaPattern(t)
    const pat = pattern ? `pat:${pattern}` : ''
    const sort = Number(p['@fz'] ?? 0)
    areas.set(`${p['@type']}${p['@id']}`, { fc: st.fill, pat, sort, props: p })
    layer(OUT.area).push(f, { ...p, ...convention(OUT.area, p), '@fc': st.fill, '@sc': st.stroke, '@pat': pat, '@sort': sort, '@bld': isBuilding(t), '@feat': featureGroup(t, 'area') })
  })
  each(tile.layers.band, (f, p) => {
    const a = areas.get(`${p['@type']}${p['@id']}`)
    if (!a) return
    layer(OUT.band).push(f, { ...a.props, ...p, '@fc': a.fc, '@pat': a.pat, '@sort': a.sort, '@bld': isBuilding(tagsOf(a.props)), '@feat': featureGroup(tagsOf(a.props), 'area') })
  })
  each(tile.layers.label, (f, p) => {
    const t = tagsOf(p)
    const preset = presets.match(t, 'area')
    const skipIcon = !preset || /building|landuse|natural/.test(preset.id)
    const icon = !skipIcon && preset?.icon ? preset.icon : null
    const name = t.name && LABEL_AREA_KEYS.some((k) => k in t) ? t.name : ''
    if (!icon && !name) return
    if (icon) icons.add(`aicon:${icon}`)
    layer(OUT.label).push(f, {
      '@id': p['@id'],
      '@type': p['@type'],
      '@icon': icon ? `aicon:${icon}` : '',
      '@hasIcon': !!icon,
      '@name': name,
      '@w0': Number(p['@width'] ?? 0),
      '@lw': name.length * 7 + 20,
      '@sort': -Number(p['@area'] ?? 0),
      '@bld': isBuilding(t),
      '@feat': featureGroup(t, 'area'),
    })
  })
  each(tile.layers.line, (f, p) => {
    const t = tagsOf(p)
    if (t.boundary && !t.highway && !t.railway && !t.waterway && !t.barrier) return
    const ls = lineStyle(t)
    const lvl = Number.parseInt(t.layer ?? '0', 10) || 0
    const bridge = t.bridge && t.bridge !== 'no' ? 1 : 0
    const dir = onewayDir(t)
    const dark = !!t.railway || (!!t.bridge && t.bridge !== 'no' && !!t.construction)
    layer(OUT.line).push(f, {
      ...p,
      ...convention(OUT.line, p),
      '@sort': (lvl + bridge) * 100 + ls.rank,
      '@name': t.highway || t.railway || t.waterway ? (t.name ?? '') : '',
      '@ow': dir,
      '@owimg': dark ? 'oneway-white' : 'oneway-black',
      '@feat': featureGroup(t, 'line'),
      ...strokeProps('@c', ls.casing, dashes),
      ...strokeProps('@s', ls.stroke, dashes),
    })
  })
  each(tile.layers.vertex, (f, p) => {
    const t = tagsOf(p)
    const tagged = hasInterestingTags(t)
    const preset = tagged ? presets.match(t, 'vertex') : null
    const icon = preset?.icon ?? null
    if (icon) icons.add(`vicon:${icon}`)
    layer(OUT.vertex).push(f, {
      ...p,
      ...convention(OUT.vertex, p),
      '@tagged': tagged,
      '@icon': icon ? `vicon:${icon}` : '',
      '@hasIcon': !!icon,
      '@shared': p['@junction'] === true,
      '@loose': p['@end'] === true,
      '@name': t.name ?? '',
    })
  })
  each(tile.layers.point, (f, p) => {
    const t = tagsOf(p)
    if (t.place && !detail) {
      const rank = ({ city: 4, town: 3, village: 2, suburb: 2, quarter: 1 } as Record<string, number>)[t.place] ?? 0
      layer(OUT.place).push(f, { ...p, ...convention(OUT.point, p), '@name': t.name ?? '', '@rank': rank })
      return
    }
    if (isAddressPoint(t)) {
      layer(OUT.point).push(f, { ...p, ...convention(OUT.point, p), '@addr': true, '@hn': t['addr:housenumber'] ?? t['addr:housename'] ?? '', '@name': '', '@feat': 'address_points' })
      return
    }
    const preset = presets.match(t, 'point')
    const icon = preset?.icon ?? null
    const wd = isWikidata(t)
    const pin = `pin:${icon ?? ''}:${wd ? 'w' : ''}`
    icons.add(pin)
    layer(OUT.point).push(f, { ...p, ...convention(OUT.point, p), '@addr': false, '@pin': pin, '@name': t.name ?? '', '@feat': 'points' })
  })
  return { layers: out, icons: [...icons], dashes: [...dashes] }
}
