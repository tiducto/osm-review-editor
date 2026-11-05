import type { OsmRef, OsmType } from '@/generated/models'
import { t, type MessageKey } from '@/i18n'

const prefix: Record<OsmType, string> = { node: 'n', way: 'w', relation: 'r' }
const fromPrefix: Record<string, OsmType> = { n: 'node', w: 'way', r: 'relation' }

export function refString(ref: OsmRef): string {
  return `${prefix[ref.type]}${ref.id}`
}

export function parseRef(s: string): OsmRef | null {
  const t = s.trim().toLowerCase()
  const short = /^([nwr])(-?\d+)$/.exec(t)
  if (short) return { type: fromPrefix[short[1]], id: Number(short[2]) }
  const long = /^(node|way|relation)[\s/]+(-?\d+)$/.exec(t)
  if (long) return { type: long[1] as OsmType, id: Number(long[2]) }
  return null
}

export function sameRef(a: OsmRef | null | undefined, b: OsmRef | null | undefined): boolean {
  return !!a && !!b && a.type === b.type && a.id === b.id
}

const typeLabel: Record<OsmType, MessageKey> = { node: 'app.osm.node', way: 'app.osm.way', relation: 'app.osm.relation' }

export function refLabel(ref: OsmRef): string {
  return `${t(typeLabel[ref.type])} ${ref.id}`
}

type PresetNamer = (ref: OsmRef, tags: Record<string, string>) => string | null

let presetNamer: PresetNamer | null = null

export function setPresetNamer(fn: PresetNamer | null) {
  presetNamer = fn
}

function presetTypeLabel(ref: OsmRef, tags: Record<string, string>): string | null {
  if (presetNamer && Object.keys(tags).length) {
    const n = presetNamer(ref, tags)
    if (n) return n
  }
  return mainTagLabel(tags)
}

export function featureTitle(ref: OsmRef, tags: Record<string, string>): string {
  if (tags.name) return tags.name
  if (tags.ref) return `${presetTypeLabel(ref, tags) ?? refLabel(ref)} ${tags.ref}`
  return presetTypeLabel(ref, tags) ?? refLabel(ref)
}

const mainKeys = [
  'highway', 'footway', 'railway', 'public_transport', 'amenity', 'shop', 'building', 'landuse', 'natural',
  'leisure', 'barrier', 'man_made', 'waterway', 'place', 'boundary', 'route', 'type',
]

export function mainTagLabel(tags: Record<string, string>): string | null {
  for (const k of mainKeys) {
    const v = tags[k]
    if (v) return humanize(v === 'yes' ? k : `${v}`)
  }
  return null
}

export function humanize(s: string): string {
  const out = s.replace(/[_:]/g, ' ')
  return out.charAt(0).toUpperCase() + out.slice(1)
}
