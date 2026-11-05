import type { CategoryDef, FieldDef, Geometry, Locale, OptionLabel, PresetDef, Tags } from './types'

export const LOCALES: Locale[] = ['cs', 'en']

type Json = Record<string, any>

export interface RawSchema {
  presets: Record<string, Json>
  fields: Record<string, Json>
  categories: Record<string, Json>
  defaults: Record<string, string[]>
  translations: Partial<Record<Locale, Json>>
}

export interface UserPresetData {
  id: string
  name: string
  names?: Partial<Record<Locale, string>>
  geometry: Geometry[]
  tags: Tags
  addTags: Tags
  removeTags: Tags
  fields: string[]
  moreFields: string[]
  icon: string | null
  terms: string[]
  aliases: string[]
  matchScore: number
  searchable: boolean
  extends: string | null
  apply: 'merge' | 'replace' | 'create'
  file: string | null
}

export const GEOMETRIES: Geometry[] = ['point', 'vertex', 'line', 'area', 'relation']

const REGION_CODES = new Set(['001', '150', '151', 'cz', 'q46', 'eu', 'eea'])
const AREA_IGNORE = new Set(['barrier', 'highway', 'footway', 'railway', 'junction', 'type'])
const REF = /^\{(.+)\}$/

export function inRegion(locationSet: Json | undefined): boolean {
  if (!locationSet) return true
  const norm = (v: unknown) => String(v).toLowerCase().replace(/\.geojson$/, '')
  const exclude: unknown[] = locationSet.exclude ?? []
  if (exclude.some((v) => REGION_CODES.has(norm(v)))) return false
  const include: unknown[] | undefined = locationSet.include
  if (!include || !include.length) return true
  return include.some((v) => REGION_CODES.has(norm(v)))
}

export function fold(s: string): string {
  return s
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim()
}

function asList(v: unknown): string[] {
  if (Array.isArray(v)) return v.map(String)
  if (typeof v === 'string')
    return v
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  return []
}

function optionLabel(v: unknown): OptionLabel | null {
  if (typeof v === 'string') return { title: v }
  if (v && typeof v === 'object' && 'title' in v) return { title: String((v as Json).title), description: (v as Json).description ? String((v as Json).description) : undefined }
  return null
}

export function geometryOf(kind: 'node' | 'way' | 'relation', tags: Tags, opts: { closed?: boolean; vertex?: boolean; isArea?: (t: Tags) => boolean } = {}): Geometry {
  if (kind === 'node') return opts.vertex ? 'vertex' : 'point'
  if (kind === 'relation') return tags.type === 'multipolygon' ? 'area' : 'relation'
  if (!opts.closed) return 'line'
  return (opts.isArea ?? (() => false))(tags) ? 'area' : 'line'
}

export class PresetCatalog {
  readonly presets = new Map<string, PresetDef>()
  readonly userIds: string[] = []
  private fieldCache = new Map<string, FieldDef | null>()
  private resolvedFields = new Map<string, string[]>()
  private resolvedMore = new Map<string, string[]>()
  private index: Record<Geometry, Map<string, Map<string, PresetDef[]>>>
  private areaKeys: Record<string, Record<string, true>> = {}
  private folded = new Map<string, { names: string[]; en: string; aliases: string[]; terms: string[] }>()

  constructor(
    readonly raw: RawSchema,
    user: UserPresetData[] = [],
    readonly locale: Locale = 'en',
  ) {
    for (const [id, p] of Object.entries(raw.presets)) {
      if (!inRegion(p.locationSet)) continue
      this.presets.set(id, this.fromRaw(id, p))
    }
    for (const u of user) {
      this.userIds.push(u.id)
      this.presets.set(u.id, {
        id: u.id,
        origin: 'user',
        name: u.names?.[locale] ?? u.name,
        names: { ...(u.names ?? {}), [locale]: u.names?.[locale] ?? u.name },
        terms: u.terms,
        aliases: u.aliases,
        geometry: u.geometry,
        tags: u.tags,
        addTags: u.addTags,
        removeTags: u.removeTags,
        fields: u.fields,
        moreFields: u.moreFields,
        icon: u.icon,
        matchScore: u.matchScore,
        searchable: u.searchable,
        extends: u.extends,
        apply: u.apply,
        file: u.file,
      })
      this.resolvedFields.set(u.id, u.fields)
      this.resolvedMore.set(u.id, u.moreFields)
    }
    this.index = this.buildIndex()
    this.buildAreaKeys()
  }

  withLocale(locale: Locale, user: UserPresetData[]): PresetCatalog {
    return new PresetCatalog(this.raw, user, locale)
  }

  private tr(locale: Locale): Json {
    return this.raw.translations[locale]?.[locale]?.presets ?? this.raw.translations[locale]?.presets ?? {}
  }

  private presetText(id: string, locale: Locale): Json | undefined {
    return this.tr(locale).presets?.[id]
  }

  private fromRaw(id: string, p: Json): PresetDef {
    const t = this.presetText(id, this.locale) ?? {}
    const en = this.presetText(id, 'en') ?? {}
    const names: Partial<Record<Locale, string>> = {}
    for (const l of LOCALES) {
      const n = this.presetText(id, l)?.name
      if (n) names[l] = n
    }
    const name = t.name ?? en.name ?? p.name ?? id
    const tags: Tags = p.tags ?? {}
    return {
      id,
      origin: 'id',
      name,
      names,
      terms: asList(t.terms ?? en.terms ?? p.terms),
      aliases: asList(t.aliases ?? en.aliases ?? p.aliases),
      geometry: p.geometry ?? [],
      tags,
      addTags: p.addTags ?? tags,
      removeTags: p.removeTags ?? p.addTags ?? tags,
      fields: p.fields ?? [],
      moreFields: p.moreFields ?? [],
      icon: p.icon ?? null,
      matchScore: p.matchScore ?? 1,
      searchable: p.searchable !== false,
      extends: null,
      apply: 'merge',
      file: null,
    }
  }

  private buildIndex() {
    const idx = {} as Record<Geometry, Map<string, Map<string, PresetDef[]>>>
    for (const g of GEOMETRIES) idx[g] = new Map()
    const ordered = [...this.userIds.map((id) => this.presets.get(id)!), ...[...this.presets.values()].filter((p) => p.origin === 'id')]
    for (const p of ordered) {
      for (const g of p.geometry) {
        const byKey = idx[g]
        if (!byKey) continue
        for (const [k, v] of Object.entries(p.tags)) {
          let byValue = byKey.get(k)
          if (!byValue) byKey.set(k, (byValue = new Map()))
          let list = byValue.get(v)
          if (!list) byValue.set(v, (list = []))
          list.push(p)
        }
      }
    }
    return idx
  }

  private buildAreaKeys() {
    const out: Record<string, Record<string, true>> = {}
    const all = [...this.presets.values()].filter((p) => !this.raw.presets[p.id]?.replacement)
    for (const p of all) {
      const key = Object.keys(p.tags)[0]
      if (!key || AREA_IGNORE.has(key)) continue
      if (p.geometry.includes('area')) out[key] ??= {}
    }
    for (const p of all) {
      for (const [k, v] of Object.entries(p.addTags)) {
        if (k in out && p.geometry.includes('line') && v !== '*') out[k][v] = true
      }
    }
    this.areaKeys = out
  }

  isArea(tags: Tags): boolean {
    if (tags.area === 'yes') return true
    if (tags.area === 'no') return false
    for (const [k, v] of Object.entries(tags)) {
      const black = this.areaKeys[k]
      if (black && !black[v]) return true
    }
    return false
  }

  preset(id: string): PresetDef | null {
    return this.presets.get(id) ?? null
  }

  fallback(geometry: Geometry): PresetDef {
    const id = geometry === 'vertex' ? 'vertex' : geometry
    return this.presets.get(id) ?? this.presets.get('point')!
  }

  isFallback(p: PresetDef): boolean {
    return Object.keys(p.tags).length === 0 && GEOMETRIES.includes(p.id as Geometry)
  }

  scoreOf(p: PresetDef, entityTags: Tags): number {
    const seen = new Set<string>()
    let score = 0
    for (const [k, v] of Object.entries(p.tags)) {
      seen.add(k)
      if (entityTags[k] === v) score += p.matchScore
      else if (v === '*' && k in entityTags) score += p.matchScore / 2
      else return -1
    }
    for (const [k, v] of Object.entries(p.addTags)) {
      if (!seen.has(k) && entityTags[k] === v) score += p.matchScore
    }
    if (!p.searchable) score *= 0.999
    return score
  }

  match(tags: Tags, geometry: Geometry): PresetDef {
    const byKey = this.index[geometry]
    let best: PresetDef | null = null
    let bestScore = -1
    for (const k of Object.keys(tags)) {
      const byValue = byKey?.get(k)
      if (!byValue) continue
      const candidates = [...(byValue.get(tags[k]) ?? []), ...(byValue.get('*') ?? [])]
      for (const c of candidates) {
        const s = this.scoreOf(c, tags)
        if (s === -1) continue
        if (s > bestScore || (s === bestScore && best && best.origin === 'id' && c.origin === 'user')) {
          bestScore = s
          best = c
        }
      }
    }
    if (!best && Object.keys(tags).some((k) => k.startsWith('addr:'))) {
      const addr = byKey?.get('addr:*')?.get('*')?.[0]
      if (addr) best = addr
    }
    return best ?? this.fallback(geometry)
  }

  field(id: string): FieldDef | null {
    if (this.fieldCache.has(id)) return this.fieldCache.get(id)!
    const raw = this.raw.fields[id]
    let def: FieldDef | null = null
    if (raw && inRegion(raw.locationSet)) {
      const t = this.tr(this.locale).fields?.[id] ?? {}
      const en = this.tr('en').fields?.[id] ?? {}
      const options: string[] | undefined = raw.options ?? (t.options ? Object.keys(t.options) : en.options ? Object.keys(en.options) : undefined)
      const optionLabels: Record<string, OptionLabel> = {}
      for (const src of [en.options ?? {}, t.options ?? {}]) {
        for (const [k, v] of Object.entries(src)) {
          const l = optionLabel(v)
          if (l) optionLabels[k] = l
        }
      }
      def = {
        id,
        type: raw.type ?? 'text',
        key: raw.key,
        keys: raw.keys,
        label: t.label ?? en.label ?? raw.label ?? id,
        placeholder: t.placeholder ?? en.placeholder,
        placeholders: { ...(en.placeholders ?? {}), ...(t.placeholders ?? {}) },
        labels: { ...(en.labels ?? {}), ...(t.labels ?? {}) },
        types: { ...(en.types ?? {}), ...(t.types ?? {}) },
        options,
        optionLabels,
        terms: asList(t.terms ?? en.terms),
        default: raw.default,
        universal: raw.universal,
        geometry: raw.geometry,
        prerequisiteTag: raw.prerequisiteTag,
        snake_case: raw.snake_case,
        caseSensitive: raw.caseSensitive,
        customValues: raw.customValues,
        autoSuggestions: raw.autoSuggestions,
        minValue: raw.minValue,
        maxValue: raw.maxValue,
        increment: raw.increment,
        pattern: raw.pattern,
        urlFormat: raw.urlFormat,
        icons: raw.icons,
        reference: raw.reference,
      }
    }
    this.fieldCache.set(id, def)
    return def
  }

  fieldKeys(f: FieldDef): string[] {
    const out = new Set<string>()
    if (f.key) out.add(f.key)
    for (const k of f.keys ?? []) out.add(k)
    return [...out]
  }

  allKeys(f: FieldDef, tags: Tags): string[] {
    const keys = this.fieldKeys(f)
    if (f.type === 'multiCombo' && f.key) for (const k of Object.keys(tags)) if (k.startsWith(f.key)) keys.push(k)
    if (f.type === 'localized' && f.key) for (const k of Object.keys(tags)) if (k.startsWith(`${f.key}:`)) keys.push(k)
    if (f.type === 'address') for (const k of Object.keys(tags)) if (k.startsWith('addr:')) keys.push(k)
    return [...new Set(keys)]
  }

  private shouldInherit(p: PresetDef, f: FieldDef): boolean {
    if (f.key && p.tags[f.key] !== undefined && !['multiCombo', 'semiCombo', 'manyCombo', 'check'].includes(f.type)) return false
    if (f.key && p.fields.some((id) => this.field(id)?.key === f.key)) return false
    return true
  }

  private resolve(p: PresetDef, which: 'fields' | 'moreFields', depth = 0): string[] {
    const cache = which === 'fields' ? this.resolvedFields : this.resolvedMore
    const hit = cache.get(p.id)
    if (hit) return hit
    if (depth > 10) return []
    const out: string[] = []
    for (const id of which === 'fields' ? p.fields : p.moreFields) {
      const m = REF.exec(id)
      if (m) {
        const parent = this.presets.get(m[1])
        if (parent) out.push(...this.resolve(parent, which, depth + 1).filter((fid) => this.field(fid) && this.shouldInherit(p, this.field(fid)!)))
      } else if (this.field(id)) out.push(id)
    }
    if (!out.length) {
      let parentId = p.id
      while (parentId.includes('/')) {
        parentId = parentId.slice(0, parentId.lastIndexOf('/'))
        const parent = this.presets.get(parentId)
        if (parent) {
          out.push(...this.resolve(parent, which, depth + 1).filter((fid) => this.shouldInherit(p, this.field(fid)!)))
          break
        }
      }
    }
    const uniq = [...new Set(out)]
    cache.set(p.id, uniq)
    return uniq
  }

  fieldsOf(p: PresetDef): FieldDef[] {
    return this.resolve(p, 'fields')
      .map((id) => this.field(id))
      .filter((f): f is FieldDef => !!f)
  }

  moreFieldsOf(p: PresetDef): FieldDef[] {
    return this.resolve(p, 'moreFields')
      .map((id) => this.field(id))
      .filter((f): f is FieldDef => !!f)
  }

  universalFields(): FieldDef[] {
    return Object.keys(this.raw.fields)
      .filter((id) => this.raw.fields[id].universal)
      .map((id) => this.field(id))
      .filter((f): f is FieldDef => !!f)
  }

  categoryName(id: string): string {
    return this.tr(this.locale).categories?.[id]?.name ?? this.tr('en').categories?.[id]?.name ?? id
  }

  categories(geometry?: Geometry): CategoryDef[] {
    const out: CategoryDef[] = []
    for (const [id, c] of Object.entries(this.raw.categories)) {
      const members = (c.members as string[]).filter((m) => {
        const p = this.presets.get(m)
        return p && p.searchable && (!geometry || p.geometry.includes(geometry))
      })
      if (!members.length) continue
      out.push({ id, name: this.categoryName(id), icon: c.icon ?? null, members })
    }
    return out.sort((a, b) => a.name.localeCompare(b.name, this.locale))
  }

  category(id: string, geometry?: Geometry): CategoryDef | null {
    return this.categories(geometry).find((c) => c.id === id) ?? null
  }

  defaults(geometry: Geometry): (PresetDef | CategoryDef)[] {
    const out: (PresetDef | CategoryDef)[] = []
    for (const id of this.raw.defaults[geometry] ?? []) {
      if (id.startsWith('category-')) {
        const c = this.category(id, geometry)
        if (c) out.push(c)
      } else {
        const p = this.presets.get(id)
        if (p && p.geometry.includes(geometry)) out.push(p)
      }
    }
    return out
  }

  private foldedOf(p: PresetDef) {
    let f = this.folded.get(p.id)
    if (!f) {
      f = {
        names: [p.name, ...Object.values(p.names)].filter((x): x is string => !!x).map(fold),
        en: fold(p.names.en ?? ''),
        aliases: p.aliases.map(fold),
        terms: p.terms.map(fold),
      }
      this.folded.set(p.id, f)
    }
    return f
  }

  search(query: string, geometry?: Geometry | Geometry[], limit = 50): PresetDef[] {
    const q = fold(query)
    const geoms = geometry ? (Array.isArray(geometry) ? geometry : [geometry]) : null
    const eq = /^([^=\s]+)=(.*)$/.exec(query.trim())
    const ranked: { p: PresetDef; r: number }[] = []
    for (const p of this.presets.values()) {
      if (!p.searchable || this.isFallback(p) && q) continue
      if (geoms && !geoms.every((g) => p.geometry.includes(g))) continue
      let r: number | null = null
      if (eq) {
        const [, k, v] = eq
        if (p.tags[k] !== undefined && (!v || p.tags[k] === v)) r = Object.keys(p.tags).length
      } else if (!q) r = 0
      else {
        const f = this.foldedOf(p)
        const name = fold(p.name)
        const words = name.split(/[\s/,()-]+/)
        if (name === q) r = 0
        else if (name.startsWith(q)) r = 1
        else if (f.aliases.some((a) => a === q || a.startsWith(q))) r = 2
        else if (words.some((w) => w.startsWith(q))) r = 3
        else if (f.terms.some((t) => t === q || t.startsWith(q))) r = 4
        else if (name.includes(q)) r = 5
        else if (f.en && this.locale !== 'en' && (f.en === q || f.en.startsWith(q))) r = 6
        else if (f.en && this.locale !== 'en' && f.en.includes(q)) r = 7
        else if (p.id.includes(q)) r = 8
      }
      if (r == null) continue
      if (p.origin === 'user') r -= 0.5
      ranked.push({ p, r })
    }
    ranked.sort((a, b) => a.r - b.r || a.p.name.length - b.p.name.length || a.p.name.localeCompare(b.p.name))
    return ranked.slice(0, limit).map((x) => x.p)
  }

  unsetTags(p: PresetDef, tags: Tags, geometry: Geometry, ignoringKeys: string[] = []): Tags {
    const out = { ...tags }
    for (const k of Object.keys(p.removeTags)) if (!ignoringKeys.includes(k)) delete out[k]
    for (const f of this.fieldsOf(p)) {
      if (f.key && f.default !== undefined && out[f.key] === f.default && (!f.geometry || f.geometry.includes(geometry)) && !ignoringKeys.includes(f.key)) delete out[f.key]
    }
    delete out.area
    return out
  }

  setTags(p: PresetDef, tags: Tags, geometry: Geometry): Tags {
    const out = { ...tags }
    for (const [k, v] of Object.entries(p.addTags)) {
      if (v === '*') {
        if (p.tags[k] || !out[k]) out[k] = 'yes'
      } else out[k] = v
    }
    if (!('area' in p.addTags)) {
      delete out.area
      if (geometry === 'area') {
        let needsArea = true
        for (const k of Object.keys(p.addTags)) {
          if (!p.geometry.includes('line') && k in this.areaKeys) {
            needsArea = false
            break
          }
        }
        if (needsArea) out.area = 'yes'
      }
    }
    for (const f of this.fieldsOf(p)) {
      if (f.key && !out[f.key] && f.default && (!f.geometry || f.geometry.includes(geometry))) out[f.key] = f.default
    }
    return out
  }

  changePreset(tags: Tags, geometry: Geometry, from: PresetDef | null, to: PresetDef): Tags {
    if (to.apply === 'replace' || (to.apply === 'create' && !Object.keys(tags).length)) return this.setTags(to, {}, geometry)
    let current = { ...tags }
    let preserve: string[] = Object.keys(to.addTags)
    if (from) {
      const wasSub = from.id.startsWith(to.id)
      const keep = [...this.fieldsOf(to), ...this.moreFieldsOf(to)]
        .filter((f) => !f.geometry || f.geometry.includes(geometry))
        .flatMap((f) => this.allKeys(f, tags))
        .filter((k) => !wasSub || from.tags[k] === undefined)
      preserve = [...preserve, ...keep]
      if (from.id !== to.id) {
        const oldKeys = [...this.fieldsOf(from), ...this.moreFieldsOf(from)].flatMap((f) => this.allKeys(f, tags))
        const drop = oldKeys.filter((k) => !preserve.includes(k))
        let reduced = { ...tags }
        for (const k of drop) delete reduced[k]
        reduced = this.unsetTags(from, reduced, geometry, preserve)
        reduced = this.setTags(to, reduced, geometry)
        if (this.scoreOf(from, reduced) === -1) current = reduced
      }
      current = this.unsetTags(from, current, geometry, preserve)
    }
    return this.setTags(to, current, geometry)
  }
}
