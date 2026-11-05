export type Tags = Record<string, string>

export interface Stroke {
  color: string
  w: number
  wl: number
  dash: number[] | null
  dashl: number[] | null
  cap: 'round' | 'butt'
  opacity: number
}

export interface LineStyle {
  casing: Stroke | null
  stroke: Stroke | null
  rank: number
}

const PATHS = new Set(['path', 'footway', 'cycleway', 'bridleway', 'corridor', 'ladder', 'via_ferrata', 'steps'])
const NARROW = new Set([
  'living_street', 'bus_guideway', 'service', 'track', 'road',
  'motorway_link', 'trunk_link', 'primary_link', 'secondary_link', 'tertiary_link',
])
const MAJOR: Record<string, [string, string]> = {
  motorway: ['#CF2081', '#70372f'],
  trunk: ['#DD2F22', '#70372f'],
  primary: ['#F99806', '#70372f'],
  secondary: ['#F3F312', '#70372f'],
  tertiary: ['#FFF9B3', '#70372f'],
}
const RANK: Record<string, number> = {
  motorway: 20, trunk: 19, primary: 18, secondary: 17, tertiary: 16, unclassified: 15, residential: 14,
  living_street: 13, pedestrian: 12, road: 11, service: 10, track: 9, cycleway: 6, bridleway: 6,
  footway: 5, path: 5, steps: 5, corridor: 4,
}
const PAVED = new Set(['paved', 'asphalt', 'concrete', 'chipseal', 'concrete:lanes', 'concrete:plates', 'metal', 'tiles'])
const SEMIPAVED = new Set([
  'bricks', 'cobblestone', 'unhewn_cobblestone', 'sett', 'paving_stones', 'grass_paver', 'metal_grid',
  'fibre_reinforced_polymer_grate', 'wood',
])

function s(color: string, w: number, wl: number, extra: Partial<Stroke> = {}): Stroke {
  return { color, w, wl, dash: null, dashl: null, cap: 'round', opacity: 1, ...extra }
}

function highwayStyle(t: Tags): LineStyle | null {
  const hw = t.highway
  if (!hw) return null
  const base = hw.replace(/_link$/, '')
  let cw = 10, cwl = 7, sw = 8, swl = 5
  if (NARROW.has(hw)) { cw = 7; cwl = 5; sw = 5; swl = 3 }
  if (PATHS.has(hw)) { cw = 5; cwl = 3; sw = 3; swl = 1 }
  if (hw === 'service' && t.service === 'driveway') { cw = 6.25; cwl = 4.25; sw = 4.25; swl = 2.25 }
  let casing = s('#444', cw, cwl)
  let stroke = s('#ccc', sw, swl)
  if (MAJOR[base]) { stroke.color = MAJOR[base][0]; casing.color = MAJOR[base][1] }
  switch (hw) {
    case 'residential': stroke.color = '#fff'; break
    case 'unclassified': stroke.color = '#dca'; break
    case 'living_street': stroke.color = '#ccc'; casing.color = '#fff'; break
    case 'road': stroke.color = '#9e9e9e'; casing.color = '#666'; break
    case 'bus_guideway': stroke.color = '#dca'; casing.color = '#666'; break
    case 'service': {
      casing.color = '#666'
      stroke.color = t.service ? '#dca' : '#fff'
      if (t.service === 'parking_aisle') stroke.color = '#cccac7'
      if (t.service === 'driveway') stroke.color = '#fff6e4'
      if (t.service === 'emergency_access') stroke.color = '#ddb2aa'
      break
    }
    case 'track': {
      stroke.color = '#c5b59f'
      casing.color = '#ddd'
      casing.cap = 'butt'
      const g = t.tracktype
      const dash = g === 'grade2' ? [10, 4] : g === 'grade3' ? [6, 4] : g === 'grade4' ? [4, 6] : g === 'grade5' ? [3, 8] : g === 'grade1' ? null : [7, 3, 3, 3, 3, 3]
      casing.dash = dash
      casing.dashl = dash
      break
    }
    case 'pedestrian': {
      casing.color = '#988'
      stroke = s('#fff', 3.5, 2, { dash: [6, 6], dashl: [4, 4], cap: 'butt' })
      break
    }
    case 'corridor': {
      casing.color = '#8cd05f'
      stroke = s('#fff', sw, swl, { dash: [2, 8], dashl: [1, 4], cap: 'butt' })
      break
    }
    case 'steps':
    case 'ladder': {
      casing.color = '#fff'
      stroke.color = hw === 'steps' ? '#81d25c' : '#6f1f1f'
      stroke.dash = [3, 3]
      stroke.dashl = [2, 2]
      stroke.cap = 'butt'
      break
    }
    case 'path':
    case 'footway':
    case 'cycleway':
    case 'bridleway': {
      stroke.dash = [6, 6]
      stroke.dashl = [3, 3]
      stroke.cap = 'butt'
      casing.color = hw === 'path' ? '#dca' : '#fff'
      stroke.color = hw === 'cycleway' ? '#58a9ed' : hw === 'bridleway' ? '#e06d5f' : '#988'
      if (hw === 'footway' && t.footway === 'sidewalk') stroke.color = '#d4b4b4'
      const shared = (hw === 'cycleway' && t.foot === 'designated') || (hw === 'path' && t.bicycle === 'designated' && t.foot === 'designated')
      if (shared) { stroke.color = '#b458ed'; casing.color = '#fff' }
      if (t.crossing || t.footway === 'crossing' || t.cycleway === 'crossing') {
        casing.color = '#dca'
        const marked = t.crossing === 'marked' || t['crossing:markings'] === 'yes' || t.crossing === 'zebra' || t.crossing === 'uncontrolled' || t.crossing === 'traffic_signals'
        const unmarked = t.crossing === 'unmarked' || t['crossing:markings'] === 'no'
        if (marked) {
          stroke.dash = [6, 3]
          stroke.dashl = [3, 1.5]
          if (hw === 'footway' || hw === 'path') stroke.color = '#4c4444'
          if (hw === 'cycleway') stroke.color = '#446077'
        } else if (unmarked) {
          stroke.dash = [6, 4]
          stroke.dashl = [3, 2]
          if (hw === 'footway' || hw === 'path') stroke.color = '#776a6a'
        }
      }
      if (t.footway === 'access_aisle') { stroke.color = '#4c4444'; stroke.dash = [4, 2]; stroke.dashl = [2.5, 1.5]; casing.color = '#dca' }
      break
    }
  }
  if (!PATHS.has(hw) && hw !== 'track' && t.surface) {
    const surface = PAVED.has(t.surface) ? 'paved' : SEMIPAVED.has(t.surface) ? 'semipaved' : 'unpaved'
    if (surface === 'unpaved') { casing.dash = [4, 4]; casing.dashl = [3, 3]; casing.cap = 'butt' }
    if (surface === 'semipaved') { casing.dash = [6, 2]; casing.dashl = [5, 2]; casing.cap = 'butt' }
  }
  if (t.bridge && t.bridge !== 'no') {
    const narrow = NARROW.has(hw) || PATHS.has(hw) || hw === 'pedestrian'
    casing = s('#000', narrow ? 10 : 16, narrow ? 7 : 10, { opacity: 0.6, cap: 'butt' })
  }
  if ((t.tunnel && t.tunnel !== 'no') || t.location === 'underground') {
    stroke.opacity = 0.3
    casing.opacity = 0.5
    casing.cap = 'butt'
  }
  return { casing, stroke, rank: RANK[base] ?? 8 }
}

export function lineStyle(t: Tags): LineStyle {
  const hw = highwayStyle(t)
  if (hw) return hw
  if (t.railway) {
    const disused = ['disused', 'abandoned', 'construction', 'proposed', 'razed'].includes(t.railway)
    if (t.railway === 'platform' || t.railway === 'platform_edge') {
      return { casing: null, stroke: s('#dca', 2, 2), rank: 3 }
    }
    const status = disused || ['abandoned', 'disused', 'construction', 'proposed'].some((k) => t[k + ':railway'])
    const casing = s(t.railway === 'disused' ? '#808080' : status ? '#999' : t.service ? '#666' : '#555', 7, 5)
    const stroke = s(t.service ? '#dca' : '#eee', 2, 2, { dash: [10, 8], dashl: [6, 6], cap: 'butt' })
    if (t.railway === 'subway') { casing.color = '#222'; stroke.color = '#bbb' }
    if (status && t.railway !== 'disused') {
      casing.dash = [7, 3]
      casing.dashl = [5, 2]
      casing.cap = 'butt'
      stroke.dash = [7, 3]
      stroke.dashl = [5, 2]
    }
    return { casing, stroke, rank: 7 }
  }
  if (t.waterway && t.waterway !== 'dam') {
    const wide = t.waterway === 'river' || t.waterway === 'flowline'
    const color = t.waterway === 'ditch' || t.waterway === 'drain' ? '#39a' : '#7dd'
    return { casing: s('#444', wide ? 10 : 7, wide ? 7 : 5), stroke: s(color, wide ? 8 : 5, wide ? 5 : 3), rank: 2 }
  }
  if (t.barrier) {
    const wall = ['wall', 'retaining_wall', 'city_wall'].includes(t.barrier)
    const color = t.barrier === 'hedge' ? 'rgb(140,208,95)' : '#ddd'
    return {
      casing: null,
      stroke: s(color, 3, 2, {
        dash: wall ? [16, 3, 9, 3] : [15, 5, 1, 5],
        dashl: wall ? [8, 1, 4, 1] : [8, 2, 2, 2],
        cap: 'butt',
      }),
      rank: 1,
    }
  }
  if (t.power) return { casing: null, stroke: s('#939393', 2, 2), rank: 1 }
  if (t.aerialway) return { casing: s('#444', 7, 5), stroke: s('#c55', 5, 3), rank: 1 }
  if (t.natural) return { casing: null, stroke: s('rgb(140,208,95)', 2, 2), rank: 1 }
  if (t.man_made === 'embankment') return { casing: null, stroke: s('#444', 2, 2), rank: 1 }
  return { casing: null, stroke: s('#fff', 2, 2), rank: 0 }
}

export interface AreaStyle {
  stroke: string
  fill: string
}

const GREEN: AreaStyle = { stroke: 'rgb(140,208,95)', fill: 'rgba(140,208,95,0.3)' }
const BLUE: AreaStyle = { stroke: 'rgb(119,211,222)', fill: 'rgba(119,211,222,0.3)' }
const YELLOW: AreaStyle = { stroke: 'rgba(255,255,148,0.75)', fill: 'rgba(255,255,148,0.25)' }
const GOLD: AreaStyle = { stroke: 'rgb(196,189,25)', fill: 'rgba(196,189,25,0.3)' }
const ORANGE: AreaStyle = { stroke: 'rgb(214,136,26)', fill: 'rgba(214,136,26,0.3)' }
const PINK: AreaStyle = { stroke: 'rgb(228,164,245)', fill: 'rgba(228,164,245,0.3)' }
const TEAL: AreaStyle = { stroke: 'rgb(153,225,170)', fill: 'rgba(153,225,170,0.3)' }
const LIGHTGREEN: AreaStyle = { stroke: 'rgb(191,232,63)', fill: 'rgba(191,232,63,0.3)' }
const TAN: AreaStyle = { stroke: 'rgb(245,220,186)', fill: 'rgba(245,220,186,0.3)' }
const DARKGRAY: AreaStyle = { stroke: 'rgb(170,170,170)', fill: 'rgba(140,140,140,0.5)' }
const BUILDING: AreaStyle = { stroke: 'rgb(224,110,95)', fill: 'rgba(224,110,95,0.3)' }
const DEFAULT: AreaStyle = { stroke: 'rgb(170,170,170)', fill: 'rgba(255,255,255,0.3)' }

const AREA_RULES: [string, string | null, AreaStyle][] = [
  ['landuse', 'flowerbed', GREEN], ['landuse', 'forest', GREEN], ['landuse', 'grass', GREEN],
  ['landuse', 'recreation_ground', GREEN], ['landuse', 'village_green', GREEN], ['leisure', 'garden', GREEN],
  ['leisure', 'golf_course', GREEN], ['leisure', 'nature_reserve', GREEN], ['leisure', 'park', GREEN],
  ['leisure', 'pitch', GREEN], ['natural', 'wood', GREEN],
  ['amenity', 'fountain', BLUE], ['landuse', 'basin', BLUE], ['leisure', 'swimming_pool', BLUE],
  ['natural', 'water', BLUE], ['natural', 'bay', BLUE], ['natural', 'strait', BLUE],
  ['leisure', 'track', YELLOW], ['natural', 'beach', YELLOW], ['natural', 'sand', YELLOW], ['natural', 'scrub', YELLOW],
  ['amenity', 'childcare', YELLOW], ['amenity', 'kindergarten', YELLOW], ['amenity', 'school', YELLOW],
  ['amenity', 'college', YELLOW], ['amenity', 'university', YELLOW], ['amenity', 'research_institute', YELLOW],
  ['landuse', 'residential', GOLD],
  ['landuse', 'retail', ORANGE], ['landuse', 'commercial', ORANGE], ['landuse', 'landfill', ORANGE],
  ['landuse', 'military', ORANGE], ['military', null, ORANGE],
  ['landuse', 'industrial', PINK], ['power', 'plant', PINK],
  ['natural', 'wetland', TEAL],
  ['landuse', 'cemetery', LIGHTGREEN], ['landuse', 'farmland', LIGHTGREEN], ['landuse', 'meadow', LIGHTGREEN],
  ['landuse', 'orchard', LIGHTGREEN], ['landuse', 'plant_nursery', LIGHTGREEN], ['landuse', 'vineyard', LIGHTGREEN],
  ['landuse', 'farmyard', TAN], ['leisure', 'horse_riding', TAN],
  ['amenity', 'parking', DARKGRAY], ['landuse', 'railway', DARKGRAY], ['landuse', 'quarry', DARKGRAY],
  ['natural', 'bare_rock', DARKGRAY], ['natural', 'scree', DARKGRAY], ['natural', 'shingle', DARKGRAY],
  ['natural', 'rock', DARKGRAY], ['natural', 'stone', DARKGRAY], ['waterway', 'dam', DARKGRAY], ['waterway', 'weir', DARKGRAY],
  ['natural', null, GREEN],
]

export function areaStyle(t: Tags): AreaStyle {
  if (t.building && t.building !== 'no') return BUILDING
  if (t['building:part'] && t['building:part'] !== 'no') return BUILDING
  if (t.leisure === 'pitch' && ['basketball', 'skateboard'].includes(t.sport)) return DARKGRAY
  if (t.leisure === 'pitch' && ['beachvolleyball', 'baseball', 'softball'].includes(t.sport)) return YELLOW
  if (t.status === 'construction' || t.landuse === 'construction') return GOLD
  for (const [k, v, st] of AREA_RULES) {
    if (t[k] !== undefined && (v === null || t[k] === v)) return st
  }
  return DEFAULT
}

const NON_PRIMARY = new Set(['building:flats', 'check_date', 'fixme', 'layer', 'level', 'note', 'start_date', 'ele'])
const NON_PRIMARY_RE = /^(ref|survey|note|([^:]+:|old_|alt_)addr):/
const UNINTERESTING = new Set(['attribution', 'created_by', 'import_uuid', 'lat', 'latitude', 'lon', 'longitude', 'source', 'source_ref', 'odbl', 'odbl:note'])
const UNINTERESTING_RE = /^(source(_ref)?|at_bev|geobase|hcpaogis|KSJ2|mvdgis|nvdb|nysgissam|tiger):|:(identifier|ref|ref_id|id)$/

export function isInteresting(key: string): boolean {
  return !UNINTERESTING.has(key) && !UNINTERESTING_RE.test(key)
}

export function hasInterestingTags(t: Tags): boolean {
  return Object.keys(t).some(isInteresting)
}

export function isAddressPoint(t: Tags): boolean {
  const keys = Object.keys(t).filter((k) => isInteresting(k) && !NON_PRIMARY.has(k) && !NON_PRIMARY_RE.test(k))
  return keys.length > 0 && keys.every((k) => k.startsWith('addr:'))
}

export function isWikidata(t: Tags): boolean {
  return !!(t.wikidata || t['flag:wikidata'] || t['brand:wikidata'] || t['network:wikidata'] || t['operator:wikidata'])
}
