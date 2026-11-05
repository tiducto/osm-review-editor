import type { OsmRef, OsmType } from '@/generated/models'

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

const typeLabel: Record<OsmType, string> = { node: 'Node', way: 'Way', relation: 'Relation' }

export function refLabel(ref: OsmRef): string {
  return `${typeLabel[ref.type]} ${ref.id}`
}

export function featureTitle(ref: OsmRef, tags: Record<string, string>): string {
  if (tags.name) return tags.name
  if (tags.ref) return `${mainTagLabel(tags) ?? refLabel(ref)} ${tags.ref}`
  return mainTagLabel(tags) ?? refLabel(ref)
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
  const t = s.replace(/[_:]/g, ' ')
  return t.charAt(0).toUpperCase() + t.slice(1)
}
