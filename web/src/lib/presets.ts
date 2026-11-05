
export type FieldType = 'combo' | 'text' | 'check' | 'number'

export interface PresetField {
  key: string
  label: string
  type: FieldType
  options?: string[]
  placeholder?: string
}

export interface Preset {
  id: string
  name: string
  icon: string
  match: (tags: Record<string, string>) => boolean
  fields: PresetField[]
}

const surface: PresetField = {
  key: 'surface',
  label: 'Surface',
  type: 'combo',
  options: ['asphalt', 'concrete', 'paving_stones', 'sett', 'concrete:plates', 'compacted', 'fine_gravel', 'gravel', 'ground', 'grass', 'wood', 'metal'],
}
const smoothness: PresetField = {
  key: 'smoothness',
  label: 'Smoothness',
  type: 'combo',
  options: ['excellent', 'good', 'intermediate', 'bad', 'very_bad', 'horrible'],
}
const lit: PresetField = { key: 'lit', label: 'Lit', type: 'check' }
const width: PresetField = { key: 'width', label: 'Width (m)', type: 'number', placeholder: '2' }
const tactile: PresetField = { key: 'tactile_paving', label: 'Tactile paving', type: 'check' }
const wheelchair: PresetField = { key: 'wheelchair', label: 'Wheelchair', type: 'combo', options: ['yes', 'limited', 'no'] }
const name: PresetField = { key: 'name', label: 'Name', type: 'text' }
const ref: PresetField = { key: 'ref', label: 'Reference', type: 'text' }
const shelter: PresetField = { key: 'shelter', label: 'Shelter', type: 'check' }
const bench: PresetField = { key: 'bench', label: 'Bench', type: 'check' }
const kerb: PresetField = { key: 'kerb', label: 'Kerb', type: 'combo', options: ['flush', 'lowered', 'raised', 'no'] }

const roadClasses = new Set([
  'motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'unclassified', 'residential', 'service', 'living_street',
  'motorway_link', 'trunk_link', 'primary_link', 'secondary_link', 'tertiary_link', 'road',
])

export const presets: Preset[] = [
  {
    id: 'platform',
    name: 'Transit platform',
    icon: 'platform',
    match: (t) => t.public_transport === 'platform' || t.railway === 'platform',
    fields: [name, ref, surface, { key: 'height', label: 'Height (m)', type: 'number' }, tactile, wheelchair, shelter, lit],
  },
  {
    id: 'bus_stop',
    name: 'Bus stop',
    icon: 'bus',
    match: (t) => t.highway === 'bus_stop',
    fields: [name, ref, shelter, bench, tactile, wheelchair, lit],
  },
  {
    id: 'crossing',
    name: 'Crossing',
    icon: 'crossing',
    match: (t) => t.highway === 'crossing' || t.footway === 'crossing' || t.railway === 'crossing',
    fields: [
      { key: 'crossing', label: 'Type', type: 'combo', options: ['uncontrolled', 'traffic_signals', 'unmarked', 'marked', 'no'] },
      { key: 'crossing:markings', label: 'Markings', type: 'combo', options: ['yes', 'no', 'zebra', 'lines', 'dashes', 'surface'] },
      tactile,
      kerb,
      surface,
      lit,
    ],
  },
  {
    id: 'sidewalk',
    name: 'Sidewalk',
    icon: 'walk',
    match: (t) => t.highway === 'footway' && t.footway === 'sidewalk',
    fields: [surface, smoothness, width, lit, wheelchair],
  },
  {
    id: 'footway',
    name: 'Footway',
    icon: 'walk',
    match: (t) => t.highway === 'footway' || t.highway === 'pedestrian' || t.highway === 'path',
    fields: [name, surface, smoothness, width, lit, { key: 'bicycle', label: 'Bicycles', type: 'combo', options: ['yes', 'designated', 'dismount', 'no'] }],
  },
  {
    id: 'road',
    name: 'Road',
    icon: 'road',
    match: (t) => roadClasses.has(t.highway ?? ''),
    fields: [
      name,
      ref,
      surface,
      { key: 'maxspeed', label: 'Speed limit', type: 'text', placeholder: '50' },
      { key: 'lanes', label: 'Lanes', type: 'number' },
      { key: 'oneway', label: 'One way', type: 'combo', options: ['yes', 'no', '-1'] },
      { key: 'sidewalk', label: 'Sidewalks', type: 'combo', options: ['both', 'left', 'right', 'separate', 'no'] },
      lit,
    ],
  },
]

export function matchPreset(tags: Record<string, string>): Preset | null {
  return presets.find((p) => p.match(tags)) ?? null
}
