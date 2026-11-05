import type { FilterSpecification, Map as MlMap } from 'maplibre-gl'
import type { Tags } from './classify'
import { setFilterPart } from '../filters'

export const FEATURE_GROUPS = [
  { id: 'points', label: 'Points' },
  { id: 'address_points', label: 'Address points' },
  { id: 'traffic_roads', label: 'Traffic roads' },
  { id: 'service_roads', label: 'Service roads' },
  { id: 'paths', label: 'Paths' },
  { id: 'buildings', label: 'Buildings' },
  { id: 'building_parts', label: 'Building parts' },
  { id: 'indoor', label: 'Indoor features' },
  { id: 'landuse', label: 'Landuse features' },
  { id: 'boundaries', label: 'Boundaries' },
  { id: 'water', label: 'Water features' },
  { id: 'rail', label: 'Rail features' },
  { id: 'pistes', label: 'Pistes' },
  { id: 'aerialways', label: 'Aerial features' },
  { id: 'power', label: 'Power features' },
  { id: 'past_future', label: 'Past/future features' },
  { id: 'others', label: 'Other features' },
] as const

export type FeatureGroup = (typeof FEATURE_GROUPS)[number]['id']
export type FeatureGeometry = 'point' | 'line' | 'area'

export const FEATURE_PROP = '@feat'

const TRAFFIC = new Set([
  'motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary', 'secondary_link',
  'tertiary', 'tertiary_link', 'residential', 'unclassified', 'living_street', 'busway',
])
const SERVICE = new Set(['service', 'road', 'track'])
const PATHS = new Set(['path', 'footway', 'cycleway', 'bridleway', 'steps', 'pedestrian', 'corridor', 'ladder'])
const PAST_FUTURE_VALUES = new Set(['construction', 'proposed', 'planned', 'disused', 'abandoned', 'was', 'dismantled', 'razed', 'demolished', 'destroyed', 'removed'])
const PAST_FUTURE_KEYS = ['highway', 'railway', 'building', 'landuse', 'amenity', 'man_made', 'power', 'waterway', 'leisure', 'shop', 'aeroway']
const LIFECYCLE_PREFIX = /^(construction|proposed|planned|disused|abandoned|was|dismantled|razed|demolished|destroyed|removed):/
const WATER_NATURAL = new Set(['water', 'bay', 'coastline', 'wetland', 'spring', 'strait', 'shoal', 'reef', 'glacier'])
const WATER_LANDUSE = new Set(['reservoir', 'basin', 'salt_pond'])
const PARKING_BUILDINGS = new Set(['multi-storey', 'sheds', 'carports', 'garage_boxes'])

function isPastFuture(t: Tags): boolean {
  if (t.construction || t.proposed) return true
  for (const k of PAST_FUTURE_KEYS) if (t[k] && PAST_FUTURE_VALUES.has(t[k])) return true
  for (const k in t) if (LIFECYCLE_PREFIX.test(k)) return true
  return false
}

export function featureGroup(t: Tags, geometry: FeatureGeometry, address = false): FeatureGroup {
  if (geometry === 'point') return address ? 'address_points' : 'points'
  if (t['building:part'] && t['building:part'] !== 'no') return 'building_parts'
  if (t.indoor && t.indoor !== 'no') return 'indoor'
  if (isPastFuture(t)) return 'past_future'
  if ((t.building && t.building !== 'no') || PARKING_BUILDINGS.has(t.parking)) return 'buildings'
  if (t['piste:type']) return 'pistes'
  if (t.aerialway) return 'aerialways'
  if (t.railway || t.landuse === 'railway') return 'rail'
  if (t.power) return 'power'
  if (t.waterway || t.water || WATER_NATURAL.has(t.natural) || WATER_LANDUSE.has(t.landuse)) return 'water'
  if (t.highway) {
    if (TRAFFIC.has(t.highway)) return 'traffic_roads'
    if (SERVICE.has(t.highway)) return 'service_roads'
    if (PATHS.has(t.highway)) return 'paths'
  }
  if (t.boundary) return 'boundaries'
  if (geometry === 'area') return 'landuse'
  return 'others'
}

const KEY = 'ore.hiddenFeatures'
const VALID = new Set<string>(FEATURE_GROUPS.map((g) => g.id))

export function readHiddenFeatures(): FeatureGroup[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? '[]') as unknown
    if (Array.isArray(v)) return v.filter((x): x is FeatureGroup => typeof x === 'string' && VALID.has(x))
  } catch {}
  return []
}

export function writeHiddenFeatures(hidden: FeatureGroup[]): void {
  try {
    if (hidden.length) localStorage.setItem(KEY, JSON.stringify(hidden))
    else localStorage.removeItem(KEY)
  } catch {}
}

const VERTEX_LAYERS = new Set(['osm-vertex', 'osm-vertex-dot', 'osm-vertex-icon', 'osm-vertex-shadow'])
const UNFILTERED = new Set(['osm-bg', 'osm-place', 'ore-highlight-slot'])

let current: FeatureGroup[] = []

function partFor(layerId: string, hidden: FeatureGroup[]): FilterSpecification | null {
  const base = layerId.startsWith('edit-') ? layerId.slice(5) : layerId
  if (UNFILTERED.has(base)) return null
  if (VERTEX_LAYERS.has(base)) return hidden.includes('buildings') ? (['!', ['boolean', ['get', '@bonly'], false]] as FilterSpecification) : null
  if (!hidden.length) return null
  return ['!', ['in', ['coalesce', ['get', FEATURE_PROP], ''], ['literal', hidden]]] as FilterSpecification
}

export function applyFeatureFilters(map: MlMap, hidden: FeatureGroup[] = current): void {
  current = [...hidden]
  for (const l of map.getStyle()?.layers ?? []) {
    if (!l.id.startsWith('osm-') && !l.id.startsWith('edit-osm-')) continue
    setFilterPart(map, l.id, 'features', partFor(l.id, current))
  }
}
