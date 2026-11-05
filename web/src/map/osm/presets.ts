import presetsUrl from '@openstreetmap/id-tagging-schema/dist/presets.min.json?url'
import type { Tags } from './classify'

export type Geometry = 'point' | 'vertex' | 'line' | 'area' | 'relation'

interface RawPreset {
  tags: Tags
  addTags?: Tags
  geometry: Geometry[]
  icon?: string
  matchScore?: number
  searchable?: boolean
  locationSet?: { include?: string[] }
}

export interface Preset {
  id: string
  tags: Tags
  addTags: Tags
  icon: string | null
  score: number
  searchable: boolean
  regional: boolean
}

type Index = Record<Geometry, Record<string, Record<string, Preset[]>>>

export class PresetMatcher {
  private index: Index = { point: {}, vertex: {}, line: {}, area: {}, relation: {} }
  private addressByGeometry: Partial<Record<Geometry, Preset>> = {}

  constructor(raw: Record<string, RawPreset>) {
    for (const [id, p] of Object.entries(raw)) {
      const include = p.locationSet?.include ?? []
      const preset: Preset = {
        id,
        tags: p.tags ?? {},
        addTags: p.addTags ?? p.tags ?? {},
        icon: p.icon ?? null,
        score: p.matchScore ?? 1,
        searchable: p.searchable !== false,
        regional: include.length > 0 && !include.some((c) => /^(001|Q2|150|cz|CZ|Q213|Q46)$/.test(String(c))),
      }
      for (const g of p.geometry ?? []) {
        const byKey = this.index[g]
        for (const [k, v] of Object.entries(preset.tags)) {
          ;((byKey[k] ??= {})[v] ??= []).push(preset)
        }
        if (id === 'address') this.addressByGeometry[g] = preset
      }
    }
  }

  private score(p: Preset, tags: Tags): number {
    let score = 0
    const seen = new Set<string>()
    for (const [k, v] of Object.entries(p.tags)) {
      seen.add(k)
      if (tags[k] === v) score += p.score
      else if (v === '*' && k in tags) score += p.score / 2
      else return -1
    }
    for (const [k, v] of Object.entries(p.addTags)) {
      if (!seen.has(k) && tags[k] === v) score += p.score
    }
    if (!p.searchable) score *= 0.999
    return score
  }

  match(tags: Tags, geometry: Geometry): Preset | null {
    const byKey = this.index[geometry]
    let best: Preset | null = null
    let bestScore = -1
    for (const k of Object.keys(tags)) {
      const byValue = byKey[k]
      if (!byValue) continue
      const candidates = [...(byValue[tags[k]] ?? []), ...(byValue['*'] ?? [])]
      for (const c of candidates) {
        if (c.regional) continue
        const sc = this.score(c, tags)
        if (sc > bestScore) {
          bestScore = sc
          best = c
        }
      }
    }
    const fallback = !best || Object.keys(best.tags).length === 0
    if (fallback && Object.keys(tags).some((k) => k.startsWith('addr:'))) {
      best = this.addressByGeometry[geometry] ?? best
    }
    return best
  }
}

export async function loadPresets(): Promise<PresetMatcher> {
  const raw = await (await fetch(presetsUrl)).json()
  return new PresetMatcher(raw)
}
