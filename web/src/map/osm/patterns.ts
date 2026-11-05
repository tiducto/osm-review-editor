import type { Tags } from './classify'

type PatternRule = string | { when?: Tags; pattern: string }[]

const LEAF: { when?: Tags; pattern: string }[] = [
  { when: { leaf_type: 'broadleaved' }, pattern: 'forest_broadleaved' },
  { when: { leaf_type: 'needleleaved' }, pattern: 'forest_needleleaved' },
  { when: { leaf_type: 'leafless' }, pattern: 'forest_leafless' },
  { pattern: 'forest' },
]

const PATTERNS: Record<string, Record<string, PatternRule>> = {
  amenity: { grave_yard: 'cemetery', fountain: 'water_standing' },
  landuse: {
    cemetery: [
      { when: { religion: 'christian' }, pattern: 'cemetery_christian' },
      { when: { religion: 'buddhist' }, pattern: 'cemetery_buddhist' },
      { when: { religion: 'muslim' }, pattern: 'cemetery_muslim' },
      { when: { religion: 'jewish' }, pattern: 'cemetery_jewish' },
      { pattern: 'cemetery' },
    ],
    construction: 'construction',
    farmland: 'farmland',
    farmyard: 'farmyard',
    flowerbed: 'flowers',
    forest: LEAF,
    grave_yard: 'cemetery',
    grass: 'grass',
    landfill: 'landfill',
    meadow: 'meadow',
    military: 'construction',
    orchard: 'orchard',
    quarry: 'quarry',
    vineyard: 'vineyard',
  },
  leisure: { horse_riding: 'farmyard' },
  natural: {
    beach: 'beach',
    grassland: 'grass',
    sand: 'beach',
    scrub: 'scrub',
    water: [
      { when: { water: 'pond' }, pattern: 'pond' },
      { when: { water: 'reservoir' }, pattern: 'water_standing' },
      { pattern: 'waves' },
    ],
    wetland: [
      { when: { wetland: 'marsh' }, pattern: 'wetland_marsh' },
      { when: { wetland: 'swamp' }, pattern: 'wetland_swamp' },
      { when: { wetland: 'bog' }, pattern: 'wetland_bog' },
      { when: { wetland: 'reedbed' }, pattern: 'wetland_reedbed' },
      { pattern: 'wetland' },
    ],
    wood: LEAF,
  },
  golf: { green: 'golf_green', tee: 'grass', fairway: 'grass', rough: 'scrub' },
  surface: { grass: 'grass', sand: 'beach' },
}

export function areaPattern(t: Tags): string | null {
  if (t.building && t.building !== 'no') return null
  for (const [key, rules] of Object.entries(PATTERNS)) {
    const v = t[key]
    if (!v) continue
    const rule = rules[v]
    if (!rule) continue
    if (typeof rule === 'string') return rule
    for (const r of rule) {
      if (!r.when || Object.entries(r.when).every(([k, val]) => t[k] === val)) return r.pattern
    }
  }
  return null
}

const GREEN = 'rgba(140,208,95,0.3)'
const LIGHTGREEN = 'rgba(191,232,63,0.3)'
const BLUE = 'rgba(119,211,222,0.3)'
const SAND = 'rgba(255,255,148,0.2)'
const TEAL = 'rgba(153,225,170,0.3)'

export const PATTERN_IMAGES: Record<string, [string, string]> = {
  beach: ['dots', SAND],
  construction: ['construction', 'rgba(196,189,25,0.3)'],
  cemetery: ['cemetery', LIGHTGREEN],
  cemetery_christian: ['cemetery_christian', LIGHTGREEN],
  cemetery_buddhist: ['cemetery_buddhist', LIGHTGREEN],
  cemetery_muslim: ['cemetery_muslim', LIGHTGREEN],
  cemetery_jewish: ['cemetery_jewish', LIGHTGREEN],
  farmland: ['farmland', LIGHTGREEN],
  farmyard: ['farmyard', 'rgba(245,220,186,0.3)'],
  flowers: ['flowers', GREEN],
  forest: ['forest', GREEN],
  forest_broadleaved: ['forest_broadleaved', GREEN],
  forest_needleleaved: ['forest_needleleaved', GREEN],
  forest_leafless: ['forest_leafless', GREEN],
  golf_green: ['grass', LIGHTGREEN],
  grass: ['grass', GREEN],
  landfill: ['landfill', 'rgba(214,136,26,0.3)'],
  meadow: ['grass', LIGHTGREEN],
  orchard: ['orchard', LIGHTGREEN],
  pond: ['pond', BLUE],
  quarry: ['quarry', 'rgba(140,140,140,0.5)'],
  scrub: ['bushes', SAND],
  vineyard: ['vineyard', LIGHTGREEN],
  water_standing: ['lines', BLUE],
  waves: ['waves', BLUE],
  wetland: ['wetland', TEAL],
  wetland_marsh: ['wetland_marsh', TEAL],
  wetland_swamp: ['wetland_swamp', TEAL],
  wetland_bog: ['wetland_bog', TEAL],
  wetland_reedbed: ['wetland_reedbed', TEAL],
}
