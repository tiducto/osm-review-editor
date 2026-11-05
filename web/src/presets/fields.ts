import type { FieldDef, FieldTags, Geometry, Locale, TagPatch, TagValue } from './types'
import { tr } from './i18n'

export const SUPPORTED_FIELD_TYPES = [
  'text',
  'number',
  'integer',
  'textarea',
  'tel',
  'email',
  'url',
  'identifier',
  'combo',
  'typeCombo',
  'networkCombo',
  'multiCombo',
  'semiCombo',
  'manyCombo',
  'check',
  'defaultCheck',
  'onewayCheck',
  'radio',
  'structureRadio',
  'access',
  'address',
  'localized',
  'wikidata',
  'wikipedia',
  'date',
  'colour',
  'directionalCombo',
  'cycleway',
  'roadspeed',
  'roadheight',
] as const

export type SupportedFieldType = (typeof SUPPORTED_FIELD_TYPES)[number]

const SUPPORTED = new Set<string>(SUPPORTED_FIELD_TYPES)

export function isSupportedType(type: string): boolean {
  return SUPPORTED.has(type)
}

export type FieldFamily =
  | 'input'
  | 'combo'
  | 'multi'
  | 'check'
  | 'radio'
  | 'access'
  | 'address'
  | 'localized'
  | 'wikidata'
  | 'wikipedia'
  | 'directional'
  | 'roadspeed'
  | 'roadheight'
  | 'fallback'

export function fieldFamily(field: FieldDef): FieldFamily {
  switch (field.type) {
    case 'text':
    case 'number':
    case 'integer':
    case 'textarea':
    case 'tel':
    case 'email':
    case 'url':
    case 'identifier':
    case 'date':
    case 'colour':
      return 'input'
    case 'combo':
    case 'typeCombo':
    case 'networkCombo':
      return 'combo'
    case 'multiCombo':
    case 'semiCombo':
    case 'manyCombo':
      return 'multi'
    case 'check':
    case 'defaultCheck':
    case 'onewayCheck':
      return 'check'
    case 'radio':
    case 'structureRadio':
      return 'radio'
    case 'access':
      return 'access'
    case 'address':
      return 'address'
    case 'localized':
      return 'localized'
    case 'wikidata':
      return 'wikidata'
    case 'wikipedia':
      return 'wikipedia'
    case 'directionalCombo':
    case 'cycleway':
      return 'directional'
    case 'roadspeed':
      return 'roadspeed'
    case 'roadheight':
      return 'roadheight'
    default:
      return field.key && !field.keys?.length ? 'input' : 'fallback'
  }
}

export function isFallbackField(field: FieldDef): boolean {
  return !SUPPORTED.has(field.type)
}

export interface ValueState {
  value: string
  mixed: string[] | null
}

export function uniq<T>(arr: T[]): T[] {
  return [...new Set(arr)]
}

export function valueState(v: TagValue): ValueState {
  if (Array.isArray(v)) {
    const vals = uniq(v.filter((x): x is string => typeof x === 'string' && x !== ''))
    const hasEmpty = v.some((x) => x === undefined || x === '' || x === null)
    if (vals.length === 1 && !hasEmpty) return { value: vals[0], mixed: null }
    if (vals.length === 0) return { value: '', mixed: null }
    return { value: '', mixed: vals }
  }
  return { value: v ?? '', mixed: null }
}

export function stringTag(tags: FieldTags, key: string): string | undefined {
  const v = tags[key]
  return typeof v === 'string' && v !== '' ? v : undefined
}

function hasTag(tags: FieldTags, key: string): boolean {
  const v = tags[key]
  if (Array.isArray(v)) return v.some((x) => typeof x === 'string' && x !== '')
  return v !== undefined && v !== ''
}

function isMixedTag(tags: FieldTags, key: string): boolean {
  return Array.isArray(tags[key])
}

export function cleanValue(v: string): string {
  return v
    .replace(/[​-‏﻿]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

export const LANGUAGE_SUFFIX_REGEX = /^(.*):([a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?(?:-[a-z][a-z0-9]{4,7}|-[0-9][a-z0-9]{3})?)$/

function multiPrefix(field: FieldDef): string {
  const k = field.key ?? ''
  return k && !k.endsWith(':') ? k + ':' : k
}

export function directionalKeys(field: FieldDef): { commonKey: string; otherCommonKey: string; fallbackKey: string; bothKey: string; keys: string[] } {
  let key = field.key ?? ''
  let keys = field.keys ?? []
  if (field.type === 'cycleway' && !field.key && keys.length) {
    key = keys[0]
    keys = keys.slice(1)
  }
  const commonKey = key
  const otherCommonKey = key.includes(':both') ? key.replace(/:both(:|$)/, '$1') : `${key}:both`
  const fallbackKey = commonKey.includes(':both') ? otherCommonKey : commonKey
  const bothKey = fallbackKey !== commonKey ? commonKey : otherCommonKey
  return { commonKey, otherCommonKey, fallbackKey, bothKey, keys }
}

export function fieldKeys(field: FieldDef, tags: FieldTags = {}): string[] {
  const out = new Set<string>()
  if (field.type === 'multiCombo' || field.type === 'manyCombo') {
    if (field.key) {
      const prefix = multiPrefix(field)
      for (const k of Object.keys(tags)) if (k.startsWith(prefix)) out.add(k)
    }
    for (const k of field.keys ?? []) out.add(k)
    return [...out]
  }
  if (field.key) out.add(field.key)
  for (const k of field.keys ?? []) out.add(k)
  if ((field.type === 'directionalCombo' || field.type === 'cycleway') && field.key) {
    const base = field.key.replace(/:both$/, '')
    out.add(base)
    out.add(`${base}:both`)
  }
  if (field.type === 'localized' && field.key) {
    const prefix = `${field.key}:`
    for (const k of Object.keys(tags)) if (k.startsWith(prefix) && LANGUAGE_SUFFIX_REGEX.test(k)) out.add(k)
  }
  if (field.type === 'address' && field.key) {
    const prefix = `${field.key}:`
    for (const k of Object.keys(tags)) if (k.startsWith(prefix)) out.add(k)
  }
  if (field.type === 'structureRadio') {
    const sel = readRadio(field, tags).selected
    if (sel === 'bridge' || sel === 'tunnel' || hasTag(tags, 'layer')) out.add('layer')
  }
  return [...out]
}

export function isFieldPresent(field: FieldDef, tags: FieldTags): boolean {
  if (field.type === 'multiCombo' && field.key) {
    const prefix = multiPrefix(field)
    return Object.keys(tags).some((k) => k.startsWith(prefix) && hasTag(tags, k))
  }
  return fieldKeys(field, tags).some((k) => (field.type === 'structureRadio' && k === 'layer' ? false : hasTag(tags, k)))
}

function tagValues(tags: FieldTags, key: string): string[] {
  const v = tags[key]
  if (Array.isArray(v)) return v.map((x) => x ?? '')
  return [v ?? '']
}

function prerequisiteOk(field: FieldDef, tags: FieldTags): boolean {
  const p = field.prerequisiteTag
  if (!p) return true
  if (p.key) {
    return tagValues(tags, p.key).every((value) => {
      if (p.valuesNot) return !p.valuesNot.includes(value)
      if (p.valueNot) return p.valueNot !== value
      if (p.values) return p.values.includes(value)
      if (p.value) return p.value === value
      return !!value
    })
  }
  if (p.keyNot) return tagValues(tags, p.keyNot).every((v) => !v)
  return true
}

export function matchAllGeometry(field: FieldDef, geometries: Geometry[]): boolean {
  return !field.geometry || geometries.every((g) => field.geometry!.includes(g))
}

const NO_MULTISELECTION = new Set(['wikipedia', 'restrictions'])

export function isFieldAllowed(field: FieldDef, tags: FieldTags, geometries: Geometry[], multiSelection = false): boolean {
  if (multiSelection && NO_MULTISELECTION.has(field.type)) return false
  if (!matchAllGeometry(field, geometries)) return false
  if (field.prerequisiteTag && !isFieldPresent(field, tags) && !prerequisiteOk(field, tags)) return false
  return true
}

export interface VisibleFieldsInput {
  fields: FieldDef[]
  moreFields: FieldDef[]
  tags: FieldTags
  geometries: Geometry[]
  shownIds?: Iterable<string>
  locale?: Locale
  multiSelection?: boolean
}

export interface VisibleFieldsResult {
  shown: FieldDef[]
  addable: FieldDef[]
}

export function visibleFields(input: VisibleFieldsInput): VisibleFieldsResult {
  const { tags, geometries, locale = 'en', multiSelection = false } = input
  const userShown = new Set(input.shownIds ?? [])
  const seen = new Set<string>()
  const main: FieldDef[] = []
  for (const f of input.fields) {
    if (seen.has(f.id)) continue
    seen.add(f.id)
    if (matchAllGeometry(f, geometries)) main.push(f)
  }
  const more: FieldDef[] = []
  for (const f of input.moreFields) {
    if (seen.has(f.id)) continue
    seen.add(f.id)
    if (matchAllGeometry(f, geometries)) more.push(f)
  }
  more.sort((a, b) => a.label.localeCompare(b.label, locale))
  const shown: FieldDef[] = []
  const notShown: FieldDef[] = []
  for (const f of main) if (isFieldAllowed(f, tags, geometries, multiSelection)) shown.push(f)
  for (const f of more) {
    if (!isFieldAllowed(f, tags, geometries, multiSelection)) continue
    if (userShown.has(f.id) || isFieldPresent(f, tags)) shown.push(f)
    else notShown.push(f)
  }
  const addable = [...notShown.filter((f) => !f.universal), ...notShown.filter((f) => f.universal)]
  return { shown, addable }
}

export function removeFieldPatch(field: FieldDef, tags: FieldTags): TagPatch {
  const patch: TagPatch = {}
  for (const k of fieldKeys(field, tags)) if (k in tags) patch[k] = undefined
  return patch
}

export function patchIsEmpty(patch: TagPatch, tags: FieldTags): boolean {
  return Object.entries(patch).every(([k, v]) => {
    const cur = tags[k]
    if (Array.isArray(cur)) return false
    return (cur ?? undefined) === v || (cur === '' && v === undefined)
  })
}

export function optionTitle(field: FieldDef, value: string): string | undefined {
  return field.optionLabels?.[value]?.title
}

export function optionDescription(field: FieldDef, value: string): string | undefined {
  return field.optionLabels?.[value]?.description
}

export function fieldPlaceholder(field: FieldDef, locale: Locale, mixed?: string[] | null): string {
  if (mixed && mixed.length) return tr(locale, 'field.multiple')
  return field.placeholder ?? ''
}

function inputVals(field: FieldDef, tags: FieldTags): ValueState {
  const key = field.key ?? field.keys?.[0]
  if (!key) return { value: '', mixed: null }
  if (!field.keys?.length) return valueState(tags[key])
  const vals = new Set<string>()
  for (const k of uniq([key, ...field.keys])) {
    for (const v of tagValues(tags, k)) if (v) vals.add(v)
  }
  const anyMixed = uniq([key, ...field.keys]).some((k) => isMixedTag(tags, k))
  if (vals.size === 0) return { value: '', mixed: null }
  if (vals.size === 1 && !anyMixed) return { value: [...vals][0], mixed: null }
  if (vals.size === 1) return valueState(tags[uniq([key, ...field.keys]).find((k) => hasTag(tags, k))!])
  return { value: '', mixed: [...vals] }
}

export function readInput(field: FieldDef, tags: FieldTags): ValueState {
  return inputVals(field, tags)
}

export function writeInput(field: FieldDef, tags: FieldTags, raw: string): TagPatch {
  const key = field.key ?? field.keys?.[0]
  if (!key) return {}
  const val = field.type === 'textarea' ? raw.trim() : cleanValue(raw)
  const state = inputVals(field, tags)
  if (!val && state.mixed) return {}
  const out = val || undefined
  if (field.keys?.length) {
    const existing = uniq([key, ...field.keys]).filter((k) => hasTag(tags, k))
    const patch: TagPatch = {}
    if (existing.length) for (const k of existing) patch[k] = out
    else patch[key] = out
    return patch
  }
  return { [key]: out }
}

const RAW_NUMBER = /^-?(?:\d+(?:\.\d*)?|\.\d+)$/

function intlLocale(locale: Locale): string {
  return locale === 'cs' ? 'cs-CZ' : 'en-US'
}

export function formatNumber(locale: Locale, num: number, fractionDigits?: number): string {
  const opts: Intl.NumberFormatOptions = { useGrouping: false, maximumFractionDigits: 20 }
  if (fractionDigits !== undefined) {
    opts.minimumFractionDigits = fractionDigits
    opts.maximumFractionDigits = fractionDigits
  }
  return new Intl.NumberFormat(intlLocale(locale), opts).format(num).replace(/−/g, '-')
}

export function parseNumber(s: string): number {
  const v = s.trim()
  if (!v) return NaN
  if (RAW_NUMBER.test(v)) return parseFloat(v)
  const normalized = v.replace(/[\s  ]/g, '').replace(/−/g, '-').replace(',', '.')
  return RAW_NUMBER.test(normalized) ? parseFloat(normalized) : NaN
}

function fractionDigitsOf(s: string): number {
  const v = s.trim().replace(',', '.')
  const i = v.indexOf('.')
  return i >= 0 ? v.length - i - 1 : 0
}

function clamp(field: FieldDef, n: number): number {
  if (field.minValue !== undefined) n = Math.max(n, field.minValue)
  if (field.maxValue !== undefined) n = Math.min(n, field.maxValue)
  return n
}

export function readNumber(field: FieldDef, tags: FieldTags, locale: Locale): ValueState {
  const st = inputVals(field, tags)
  if (st.mixed || !st.value) return st
  const value = st.value
    .split(';')
    .map((v) => {
      const t = v.trim()
      const n = Number(t)
      if (t === '' || !isFinite(n)) return t
      return formatNumber(locale, n, fractionDigitsOf(t))
    })
    .join(';')
  return { value, mixed: null }
}

export function normalizeNumber(field: FieldDef, raw: string): string {
  return raw
    .split(';')
    .map((v) => {
      const t = v.trim()
      const n = parseNumber(t)
      if (!isFinite(n)) return cleanValue(t)
      return clamp(field, n).toFixed(fractionDigitsOf(t))
    })
    .filter((v, _i, a) => v !== '' || a.length === 1)
    .join(';')
}

export function writeNumber(field: FieldDef, tags: FieldTags, raw: string): TagPatch {
  return writeInput(field, tags, normalizeNumber(field, raw))
}

export function canStepNumber(field: FieldDef, tags: FieldTags): boolean {
  const st = inputVals(field, tags)
  if (st.mixed) return false
  return (st.value || '0').split(';').some((v) => isFinite(Number(v.trim())) && v.trim() !== '')
}

export function stepNumber(field: FieldDef, tags: FieldTags, direction: 1 | -1): TagPatch {
  const st = inputVals(field, tags)
  if (st.mixed) return {}
  const inc = (field.increment ?? 1) * direction
  const raw = st.value || '0'
  const next = raw
    .split(';')
    .map((v) => {
      const t = v.trim()
      const n = Number(t)
      if (t === '' || !isFinite(n)) return t
      const digits = Math.max(fractionDigitsOf(t), fractionDigitsOf(String(field.increment ?? 1)))
      return clamp(field, n + inc).toFixed(digits)
    })
    .join(';')
  return writeInput(field, tags, next)
}

export function isValidDate(v: string): boolean {
  if (!v) return true
  const m = /^(-?\d{4})(?:-(\d{2})(?:-(\d{2}))?)?$/.exec(v.trim())
  if (!m) return false
  if (m[2] !== undefined) {
    const mo = Number(m[2])
    if (mo < 1 || mo > 12) return false
  }
  if (m[3] !== undefined) {
    const d = Number(m[3])
    const days = new Date(Date.UTC(Number(m[1]), Number(m[2]), 0)).getUTCDate()
    if (d < 1 || d > days) return false
  }
  return true
}

const NAMED_COLOURS: Record<string, string> = {
  black: '#000000',
  white: '#ffffff',
  red: '#ff0000',
  green: '#008000',
  blue: '#0000ff',
  yellow: '#ffff00',
  orange: '#ffa500',
  purple: '#800080',
  brown: '#a52a2a',
  grey: '#808080',
  gray: '#808080',
  silver: '#c0c0c0',
  gold: '#ffd700',
  pink: '#ffc0cb',
  maroon: '#800000',
  navy: '#000080',
  olive: '#808000',
  teal: '#008080',
  lime: '#00ff00',
  aqua: '#00ffff',
  cyan: '#00ffff',
  magenta: '#ff00ff',
  fuchsia: '#ff00ff',
  violet: '#ee82ee',
  beige: '#f5f5dc',
  darkgreen: '#006400',
  darkblue: '#00008b',
  darkred: '#8b0000',
  lightblue: '#add8e6',
  lightgreen: '#90ee90',
  lightgrey: '#d3d3d3',
  darkgrey: '#a9a9a9',
}

export function colourHex(v: string): string | null {
  const s = v.trim().toLowerCase()
  if (!s) return null
  if (/^#[0-9a-f]{6}$/.test(s)) return s
  if (/^#[0-9a-f]{3}$/.test(s)) return '#' + [...s.slice(1)].map((c) => c + c).join('')
  return NAMED_COLOURS[s] ?? null
}

export function isValidColour(v: string): boolean {
  return !v.trim() || colourHex(v) !== null
}

export function writeColourPicker(field: FieldDef, tags: FieldTags, hex: string): TagPatch {
  return writeInput(field, tags, hex.toLowerCase())
}

export function isValidUrl(v: string): boolean {
  return /^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(v.trim())
}

export function urlLink(field: FieldDef, value: string): string | null {
  const v = value.trim()
  if (!v) return null
  if (isValidUrl(v)) return v
  if (field.type === 'url' && /^[\w-]+(\.[\w-]+)+(\/.*)?$/.test(v)) return `https://${v}`
  return null
}

export function identifierLink(field: FieldDef, value: string): string | null {
  const v = value.trim()
  if (!v || !field.urlFormat) return null
  if (isValidUrl(v)) return v
  let id: string | undefined = v
  if (field.pattern) {
    try {
      id = v.match(new RegExp(field.pattern))?.[0]
    } catch {
      id = v
    }
  }
  return id ? field.urlFormat.replace(/{value}/, encodeURIComponent(id)) : null
}

export function isValidIdentifier(field: FieldDef, value: string): boolean {
  if (!value || !field.pattern) return true
  try {
    return new RegExp(field.pattern).test(value)
  } catch {
    return true
  }
}

export interface ComboOption {
  value: string
  title: string
  description?: string
  raw?: boolean
  terms?: string[]
}

export function comboOptionValues(field: FieldDef): string[] {
  if (field.options?.length) return field.options
  return Object.keys(field.optionLabels ?? {}).filter((k) => k !== 'undefined')
}

export function comboOptions(field: FieldDef): ComboOption[] {
  return comboOptionValues(field).map((v) => {
    const title = optionTitle(field, v)
    return { value: v, title: title ?? v, description: optionDescription(field, v), raw: title === undefined }
  })
}

export function allowsCustomValues(field: FieldDef): boolean {
  return field.type !== 'manyCombo' && field.customValues !== false
}

export function isSnakeCase(field: FieldDef): boolean {
  return field.snake_case || field.snake_case === undefined
}

export function restrictSpelling(field: FieldDef, v: string): string {
  let out = v
  if (isSnakeCase(field)) out = out.replace(/\s+/g, '_')
  if (!field.caseSensitive && !(field.key === 'type' && out === 'associatedStreet')) out = out.toLowerCase()
  return out
}

function splitSemi(s: string | undefined): string[] {
  return (s ?? '')
    .split(';')
    .map((x) => x.trim())
    .filter(Boolean)
}

function cleanSemi(s: string): string {
  return splitSemi(s).join(';')
}

export function comboTagValue(field: FieldDef, display: string): string | undefined {
  const d = cleanSemi(cleanValue(display ?? ''))
  const lower = d.toLowerCase()
  const found = comboOptions(field).find((o) => cleanSemi(o.title).toLowerCase() === lower || o.value.toLowerCase() === lower)
  if (found) return found.value
  if (field.type === 'typeCombo' && !d) return 'yes'
  return restrictSpelling(field, d) || undefined
}

export function comboDisplay(field: FieldDef, value: string): string {
  if (!value) return ''
  const title = optionTitle(field, value)
  if (title !== undefined) return title
  if (field.type === 'typeCombo' && value.toLowerCase() === 'yes') return ''
  return value
}

export interface ComboState {
  value: string
  display: string
  mixed: string[] | null
  raw: boolean
}

export function readCombo(field: FieldDef, tags: FieldTags): ComboState {
  const st = valueState(field.key ? tags[field.key] : undefined)
  if (st.mixed) return { value: '', display: '', mixed: st.mixed.map((v) => comboDisplay(field, v) || v), raw: false }
  const display = comboDisplay(field, st.value)
  return { value: st.value, display, mixed: null, raw: !!st.value && optionTitle(field, st.value) === undefined && display !== '' }
}

export function writeCombo(field: FieldDef, tags: FieldTags, display: string): TagPatch {
  if (!field.key) return {}
  if (!cleanValue(display) && isMixedTag(tags, field.key)) return {}
  return { [field.key]: comboTagValue(field, display) }
}

export function writeComboValue(field: FieldDef, value: string | undefined): TagPatch {
  if (!field.key) return {}
  return { [field.key]: value || undefined }
}

export interface MultiItem {
  key: string
  value: string
  title: string
  state: string
  mixed: boolean
  index: number
}

export interface MultiState {
  items: MultiItem[]
  available: ComboOption[]
}

export function readMulti(field: FieldDef, tags: FieldTags): MultiState {
  const items: MultiItem[] = []
  if (field.type === 'semiCombo') {
    const key = field.key ?? ''
    const raw = tags[key]
    let all: string[]
    let common: string[]
    if (Array.isArray(raw)) {
      const lists = raw.map((v) => splitSemi(v))
      all = uniq(lists.flat())
      common = all.filter((v) => lists.every((l) => l.includes(v)))
    } else {
      all = uniq(splitSemi(raw))
      common = all
    }
    all.forEach((v, i) => items.push({ key: v, value: v, title: comboDisplay(field, v) || v, state: 'yes', mixed: !common.includes(v), index: i }))
    const present = new Set(all)
    return { items, available: comboOptions(field).filter((o) => !present.has(o.value)) }
  }
  const prefix = field.type === 'multiCombo' ? multiPrefix(field) : ''
  const keys = field.type === 'multiCombo' && prefix ? Object.keys(tags).filter((k) => k.startsWith(prefix)) : (field.keys ?? comboOptionValues(field))
  let i = 0
  for (const k of keys) {
    const v = tags[k]
    if (v === undefined || v === '') continue
    if (Array.isArray(v) && !v.some((x) => x)) continue
    const suffix = prefix ? k.slice(prefix.length) : k
    if (!suffix) continue
    const state = typeof v === 'string' ? v.toLowerCase() : ''
    if (field.type === 'manyCombo' && state === 'no') continue
    items.push({ key: k, value: suffix, title: comboDisplay(field, suffix) || suffix, state, mixed: Array.isArray(v), index: i++ })
  }
  const present = new Set(items.map((it) => it.value))
  return { items, available: comboOptions(field).filter((o) => !present.has(o.value)) }
}

export function addMulti(field: FieldDef, tags: FieldTags, display: string): TagPatch {
  if (field.type === 'semiCombo') {
    const key = field.key
    if (!key) return {}
    const vals = display
      .split(/[;,]/)
      .map((part) => (part.trim() ? comboTagValue(field, part) : undefined))
      .filter((v): v is string => !!v)
    if (!vals.length) return {}
    const current = readMulti(field, tags).items.map((it) => it.value)
    return { [key]: uniq([...current, ...vals]).join(';') }
  }
  const v = comboTagValue(field, display)
  if (!v) return {}
  if (field.type === 'manyCombo') {
    const opt = comboOptions(field).find((o) => o.value === v || o.title.toLowerCase() === display.trim().toLowerCase())
    if (!opt) return {}
    const old = tags[opt.value]
    if (typeof old === 'string' && old && old.toLowerCase() !== 'no') return {}
    return { [opt.value]: 'yes' }
  }
  const key = multiPrefix(field) + v
  const old = tags[key]
  if (typeof old === 'string' && old && old.toLowerCase() !== 'no') return {}
  return { [key]: 'yes' }
}

export function removeMulti(field: FieldDef, tags: FieldTags, item: Pick<MultiItem, 'key' | 'index'>): TagPatch {
  if (field.type === 'semiCombo') {
    const key = field.key
    if (!key) return {}
    const arr = readMulti(field, tags).items.map((it) => it.value)
    arr.splice(item.index, 1)
    const next = uniq(arr)
    return { [key]: next.length ? next.join(';') : undefined }
  }
  return { [item.key]: undefined }
}

export function toggleMulti(field: FieldDef, tags: FieldTags, item: Pick<MultiItem, 'key'>): TagPatch {
  if (field.type !== 'multiCombo') return {}
  return { [item.key]: tags[item.key] === 'yes' ? 'no' : 'yes' }
}

export type CheckValue = string | undefined

export function checkValues(field: FieldDef): CheckValue[] {
  if (field.options?.length) return field.options.map((v) => (v === 'undefined' ? undefined : v))
  const vals: CheckValue[] = [undefined, 'yes']
  if (field.type !== 'defaultCheck') vals.push('no')
  return vals
}

export interface CheckState {
  value: CheckValue
  raw: CheckValue
  mixed: string[] | null
  checked: boolean
  indeterminate: boolean
  reversed: boolean
  canReverse: boolean
}

export function readCheck(field: FieldDef, tags: FieldTags): CheckState {
  const key = field.key ?? ''
  const tag = tags[key]
  const mixed = Array.isArray(tag) ? uniq(tag.map((v) => v ?? '')) : null
  let value: CheckValue = !mixed && tag ? (tag as string).toLowerCase() : undefined
  const raw = value
  let reversed = false
  if (field.type === 'onewayCheck' && (value === '1' || value === '-1')) {
    reversed = value === '-1'
    value = 'yes'
  }
  const checked = value !== 'no' && value !== '' && value !== undefined
  const canReverse = field.type === 'onewayCheck' && !mixed && value === 'yes'
  return { value, raw, mixed, checked, indeterminate: !!mixed || (field.type !== 'defaultCheck' && !value), reversed, canReverse }
}

export function checkText(field: FieldDef, state: CheckState, locale: Locale): string {
  if (state.mixed) return tr(locale, 'field.multiple')
  if (field.type === 'onewayCheck' && state.raw === '-1') return tr(locale, 'field.onewayReversed')
  const v = state.value
  const values = checkValues(field)
  const label = optionTitle(field, v === undefined ? 'undefined' : v)
  if (label) return label
  if (v === undefined) return tr(locale, 'field.unknown')
  if (v === 'yes') return tr(locale, 'field.yes')
  if (v === 'no') return tr(locale, 'field.no')
  if (v === 'reversible') return tr(locale, 'field.reversible')
  if (v === 'alternating') return tr(locale, 'field.alternating')
  return values.includes(v) ? v : `"${state.raw ?? v}"`
}

export function nextCheck(field: FieldDef, tags: FieldTags): TagPatch {
  const key = field.key
  if (!key) return {}
  const values = checkValues(field)
  const st = readCheck(field, tags)
  let next: CheckValue
  if (st.mixed) next = values.includes('yes') ? 'yes' : values[0]
  else next = values[(values.indexOf(st.value) + 1) % values.length]
  if (next === 'reversible' || next === 'alternating') next = values[0]
  return { [key]: next }
}

export function reverseOneway(field: FieldDef, tags: FieldTags): TagPatch {
  const key = field.key
  if (!key) return {}
  const st = readCheck(field, tags)
  if (!st.canReverse) return {}
  return { [key]: st.raw === '-1' ? 'yes' : '-1' }
}

export interface RadioOption {
  value: string
  title: string
  description?: string
  raw: boolean
}

export interface RadioState {
  options: RadioOption[]
  selected: string | null
  active: string[]
  mixed: string[]
}

function radioActive(field: FieldDef, tags: FieldTags, d: string): boolean {
  if (field.key) {
    const v = tags[field.key]
    return Array.isArray(v) ? v.includes(d) : v === d
  }
  const v = tags[d]
  if (Array.isArray(v)) return v.some((x) => typeof x === 'string' && x !== '' && x.toLowerCase() !== 'no')
  return typeof v === 'string' && v !== '' && v.toLowerCase() !== 'no'
}

function radioMixed(field: FieldDef, tags: FieldTags, d: string): boolean {
  if (field.key) {
    const v = tags[field.key]
    return Array.isArray(v) && v.includes(d)
  }
  return Array.isArray(tags[d])
}

export function readRadio(field: FieldDef, tags: FieldTags): RadioState {
  const values = [...(field.options ?? field.keys ?? [])]
  const cur = field.key ? tags[field.key] : undefined
  if (typeof cur === 'string' && cur && !values.includes(cur)) values.push(cur)
  const options = values.map((v) => {
    const title = optionTitle(field, v)
    return { value: v, title: title ?? (field.key && !(field.options ?? []).includes(v) ? `"${v}"` : v), description: optionDescription(field, v), raw: title === undefined }
  })
  const active = values.filter((v) => radioActive(field, tags, v))
  const mixed = values.filter((v) => radioMixed(field, tags, v))
  let selected: string | null = null
  if (field.key) selected = typeof cur === 'string' && cur ? cur : null
  else {
    const strict = values.filter((v) => typeof tags[v] === 'string' && tags[v] !== '' && (tags[v] as string).toLowerCase() !== 'no')
    if (strict.length === 1) selected = strict[0]
  }
  return { options, selected, active, mixed }
}

function structureOldType(tags: FieldTags, key: string): string | undefined {
  const v = stringTag(tags, key)
  if (v && v.toLowerCase() !== 'no') return v
  if (hasTag(tags, 'waterway')) {
    if (key === 'tunnel') return 'culvert'
    if (key === 'bridge') return 'aqueduct'
  }
  return undefined
}

export function writeRadio(field: FieldDef, tags: FieldTags, option: string | null): TagPatch {
  const patch: TagPatch = {}
  if (field.key) {
    patch[field.key] = option ?? undefined
    return patch
  }
  const keys = field.options ?? field.keys ?? []
  for (const k of keys) {
    if (k === option) patch[k] = structureOldType(tags, k) ?? 'yes'
    else if (k in tags) patch[k] = undefined
  }
  if (field.type === 'structureRadio') {
    const layer = stringTag(tags, 'layer')
    const n = layer !== undefined ? Number(layer) : NaN
    if (option === 'bridge') patch.layer = !Number.isNaN(n) && n > 0 ? layer : '1'
    else if (option === 'tunnel' && patch.tunnel !== 'building_passage') patch.layer = !Number.isNaN(n) && n < 0 ? layer : '-1'
    else if (hasTag(tags, 'layer')) patch.layer = undefined
  }
  return patch
}

export function writeStructureType(tags: FieldTags, key: string, typeField: FieldDef | null, display: string): TagPatch {
  const f: FieldDef = typeField ?? { id: key, type: 'typeCombo', key, label: key, optionLabels: {}, terms: [] }
  const val = comboTagValue({ ...f, type: 'typeCombo', key }, display) ?? 'yes'
  const patch: TagPatch = { [key]: val }
  if (val === 'no' || (key !== 'bridge' && key !== 'tunnel') || (key === 'tunnel' && val === 'building_passage')) {
    if (hasTag(tags, 'layer')) patch.layer = undefined
  } else if (!hasTag(tags, 'layer')) {
    if (key === 'bridge') patch.layer = '1'
    if (key === 'tunnel') patch.layer = '-1'
  }
  return patch
}

export function structureTypeField(key: string, lookup?: (id: string) => FieldDef | null | undefined): FieldDef {
  const f = lookup?.(key)
  return f ? { ...f, type: 'typeCombo', key } : { id: key, type: 'typeCombo', key, label: key, optionLabels: {}, terms: [] }
}

export function showsLayer(field: FieldDef, tags: FieldTags): boolean {
  if (field.type !== 'structureRadio') return false
  const sel = readRadio(field, tags).selected
  return sel === 'bridge' || sel === 'tunnel' || hasTag(tags, 'layer')
}

export const LAYER_FIELD: FieldDef = { id: 'layer', type: 'integer', key: 'layer', label: 'Layer', placeholder: '0', optionLabels: {}, terms: [] }

const ACCESS_PLACEHOLDERS: Record<string, Record<string, Record<string, string>>> = {
  highway: {
    footway: { foot: 'designated', motor_vehicle: 'no' },
    steps: { foot: 'yes', motor_vehicle: 'no', bicycle: 'no', horse: 'no' },
    ladder: { foot: 'yes', motor_vehicle: 'no', bicycle: 'no', horse: 'no' },
    pedestrian: { foot: 'yes', motor_vehicle: 'no' },
    living_street: { foot: 'yes' },
    cycleway: { motor_vehicle: 'no', bicycle: 'designated' },
    bridleway: { motor_vehicle: 'no', horse: 'designated' },
    path: { foot: 'yes', motor_vehicle: 'no', bicycle: 'yes', horse: 'yes' },
    motorway: { foot: 'no', motor_vehicle: 'yes', bicycle: 'no', horse: 'no' },
    trunk: { motor_vehicle: 'yes' },
    primary: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    secondary: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    tertiary: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    residential: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    unclassified: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    service: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    motorway_link: { foot: 'no', motor_vehicle: 'yes', bicycle: 'no', horse: 'no' },
    trunk_link: { motor_vehicle: 'yes' },
    primary_link: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    secondary_link: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    tertiary_link: { foot: 'yes', motor_vehicle: 'yes', bicycle: 'yes', horse: 'yes' },
    construction: { access: 'no' },
    busway: { access: 'no', bus: 'designated', emergency: 'yes' },
  },
  barrier: {
    bollard: { access: 'no', bicycle: 'yes', foot: 'yes' },
    bus_trap: { motor_vehicle: 'no', psv: 'yes', foot: 'yes', bicycle: 'yes' },
    city_wall: { access: 'no' },
    coupure: { access: 'yes' },
    cycle_barrier: { motor_vehicle: 'no' },
    ditch: { access: 'no' },
    entrance: { access: 'yes' },
    fence: { access: 'no' },
    hedge: { access: 'no' },
    jersey_barrier: { access: 'no' },
    motorcycle_barrier: { motor_vehicle: 'no' },
    rail_guard: { access: 'no' },
  },
}

export function accessOptions(field: FieldDef, type: string): ComboOption[] {
  let options = ['yes', 'no', 'designated', 'permissive', 'destination', 'customers', 'private', 'permit', 'unknown']
  if (type === 'access') options = options.filter((v) => v !== 'yes' && v !== 'designated')
  if (type === 'bicycle') options.splice(options.length - 4, 0, 'dismount')
  return options.map((v) => {
    const title = optionTitle(field, v)
    return { value: v, title: title ?? v, description: optionDescription(field, v), raw: title === undefined }
  })
}

function impliedAccess(tags: FieldTags, key: string): string {
  const s = (k: string) => stringTag(tags, k)
  if (s(key)) return s(key)!
  if (s('motorroad') === 'yes' && (key === 'foot' || key === 'bicycle' || key === 'horse')) return 'no'
  if (s('vehicle') && (key === 'bicycle' || key === 'motor_vehicle')) return s('vehicle')!
  if (s('access')) return s('access')!
  for (const k of Object.keys(ACCESS_PLACEHOLDERS)) {
    const v = s(k)
    if (v && ACCESS_PLACEHOLDERS[k][v]?.[key]) return ACCESS_PLACEHOLDERS[k][v][key]
  }
  if (key === 'access' && !s('barrier')) return 'yes'
  return ''
}

export interface AccessRow {
  key: string
  label: string
  value: string
  display: string
  mixed: string[] | null
  placeholder: string
  placeholderValue: string
}

export function readAccess(field: FieldDef, tags: FieldTags, locale: Locale): AccessRow[] {
  return (field.keys ?? []).map((key) => {
    const st = valueState(tags[key])
    const implied = st.value ? '' : impliedAccess(tags, key)
    const placeholder = st.mixed ? tr(locale, 'field.multiple') : implied ? (optionTitle(field, implied) ?? implied) : (field.placeholder ?? '')
    return {
      key,
      label: field.types?.[key] ?? key,
      value: st.value,
      display: st.value ? (optionTitle(field, st.value) ?? st.value) : '',
      mixed: st.mixed,
      placeholder,
      placeholderValue: implied,
    }
  })
}

export function writeAccess(field: FieldDef, tags: FieldTags, key: string, display: string): TagPatch {
  const raw = cleanValue(display)
  if (!raw && typeof tags[key] !== 'string') return {}
  if (!raw) return { [key]: undefined }
  const opt = accessOptions(field, key).find((o) => o.title.toLowerCase() === raw.toLowerCase() || o.value === raw.toLowerCase())
  return { [key]: opt ? opt.value : raw }
}

export interface AddressSubfield {
  id: string
  key: string
  label: string
  placeholder: string
  width: number
}

export const CZ_ADDRESS_FORMAT: string[][] = [
  ['street', 'housenumber'],
  ['place', 'conscriptionnumber'],
  ['postcode', 'city'],
]

const ADDRESS_WIDTHS: Record<string, number> = { housenumber: 1 / 3, conscriptionnumber: 1 / 3, postcode: 1 / 3, streetnumber: 1 / 3 }

function addrText(field: FieldDef, id: string, kind: 'labels' | 'placeholders'): string | undefined {
  const dict = field[kind]
  return dict?.[`${id}!cz`] ?? dict?.[id]
}

export function addressRows(field: FieldDef, tags: FieldTags, locale: Locale): AddressSubfield[][] {
  const prefix = (field.key ?? 'addr') + ':'
  const mk = (id: string): AddressSubfield => ({
    id,
    key: prefix + id,
    label: addrText(field, id, 'labels') ?? addrText(field, id, 'placeholders') ?? tr(locale, `field.addr.${id}`),
    placeholder: addrText(field, id, 'placeholders') ?? tr(locale, `field.addr.${id}`),
    width: ADDRESS_WIDTHS[id] ?? 2 / 3,
  })
  const rows = CZ_ADDRESS_FORMAT.map((r) => r.map(mk))
  const used = new Set(rows.flat().map((s) => s.key))
  const known = uniq([...(field.keys ?? []), ...Object.keys(tags).filter((k) => k.startsWith(prefix))])
  for (const k of known) {
    if (used.has(k) || !k.startsWith(prefix) || !hasTag(tags, k)) continue
    used.add(k)
    rows.push([{ ...mk(k.slice(prefix.length)), width: 1 }])
  }
  return rows
}

export function readAddress(field: FieldDef, tags: FieldTags): Record<string, ValueState> {
  const prefix = (field.key ?? 'addr') + ':'
  const out: Record<string, ValueState> = {}
  for (const k of fieldKeys(field, tags)) if (k.startsWith(prefix)) out[k] = valueState(tags[k])
  return out
}

export function writeAddress(tags: FieldTags, key: string, raw: string): TagPatch {
  const v = cleanValue(raw)
  if (!v && isMixedTag(tags, key)) return {}
  return { [key]: v || undefined }
}

export interface LanguageInfo {
  code: string
  native: string
}

export const LANGUAGES: LanguageInfo[] = [
  { code: 'cs', native: 'čeština' },
  { code: 'en', native: 'English' },
  { code: 'de', native: 'Deutsch' },
  { code: 'pl', native: 'polski' },
  { code: 'sk', native: 'slovenčina' },
  { code: 'uk', native: 'українська' },
  { code: 'ru', native: 'русский' },
  { code: 'hu', native: 'magyar' },
  { code: 'fr', native: 'français' },
  { code: 'it', native: 'italiano' },
  { code: 'es', native: 'español' },
  { code: 'vi', native: 'Tiếng Việt' },
  { code: 'la', native: 'Latina' },
]

export function languageName(code: string, locale: Locale): string {
  const key = `lang.${code}`
  const name = tr(locale, key)
  return name === key ? code : name
}

export function isLanguageCode(code: string): boolean {
  return LANGUAGE_SUFFIX_REGEX.test(`x:${code}`)
}

export interface LocalizedVariant {
  lang: string
  key: string
  value: string
  mixed: string[] | null
}

export interface LocalizedState {
  name: ValueState
  variants: LocalizedVariant[]
}

export function readLocalized(field: FieldDef, tags: FieldTags): LocalizedState {
  const key = field.key ?? 'name'
  const variants: LocalizedVariant[] = []
  for (const k of Object.keys(tags)) {
    const m = k.match(LANGUAGE_SUFFIX_REGEX)
    if (!m || m[1] !== key || !m[2] || !hasTag(tags, k)) continue
    const st = valueState(tags[k])
    variants.push({ lang: m[2], key: k, value: st.value, mixed: st.mixed })
  }
  const order = new Map(LANGUAGES.map((l, i) => [l.code, i]))
  variants.sort((a, b) => (order.get(a.lang) ?? 99) - (order.get(b.lang) ?? 99) || a.lang.localeCompare(b.lang))
  return { name: valueState(tags[key]), variants }
}

export function writeLocalizedName(field: FieldDef, tags: FieldTags, raw: string): TagPatch {
  const key = field.key ?? 'name'
  const v = cleanValue(raw)
  if (!v && isMixedTag(tags, key)) return {}
  return { [key]: v || undefined }
}

export function writeLocalizedVariant(field: FieldDef, tags: FieldTags, lang: string, raw: string): TagPatch {
  const key = `${field.key ?? 'name'}:${lang}`
  const v = cleanValue(raw)
  if (!v && isMixedTag(tags, key)) return {}
  return { [key]: v || undefined }
}

export function renameLocalizedVariant(field: FieldDef, tags: FieldTags, fromLang: string, toLang: string): TagPatch {
  const base = field.key ?? 'name'
  const from = `${base}:${fromLang}`
  const to = `${base}:${toLang}`
  if (from === to || !isLanguageCode(toLang)) return {}
  const v = tags[from]
  const value = typeof v === 'string' ? v : undefined
  if (!value) return {}
  return { [from]: undefined, [to]: value }
}

export function normalizeQid(raw: string): string | undefined {
  const v = raw.trim()
  if (!v) return undefined
  const m = /(?:^|\/|=)(q\d+)\s*$/i.exec(v) ?? /^(q\d+)$/i.exec(v)
  return m ? m[1].toUpperCase() : v
}

export function isValidQid(v: string): boolean {
  return !v || /^Q\d+$/.test(v)
}

export function readWikidata(field: FieldDef, tags: FieldTags): ValueState {
  return valueState(tags[field.key ?? 'wikidata'])
}

export function writeWikidata(field: FieldDef, tags: FieldTags, raw: string): TagPatch {
  const key = field.key ?? 'wikidata'
  const v = normalizeQid(raw)
  if (!v && isMixedTag(tags, key)) return {}
  return { [key]: v }
}

export function wikidataLink(value: string): string | null {
  return /^Q\d+$/.test(value) ? `https://www.wikidata.org/wiki/${value}` : null
}

export interface WikipediaState {
  lang: string
  title: string
  mixed: string[] | null
}

export function readWikipedia(field: FieldDef, tags: FieldTags, defaultLang: string): WikipediaState {
  const st = valueState(tags[field.key ?? 'wikipedia'])
  if (st.mixed) return { lang: defaultLang, title: '', mixed: st.mixed }
  const m = /^([a-z][a-z-]{1,11}):(.*)$/.exec(st.value)
  if (m) return { lang: m[1], title: m[2], mixed: null }
  return { lang: defaultLang, title: st.value, mixed: null }
}

export function parseWikipediaUrl(raw: string): { lang: string; title: string } | null {
  const m = raw.match(/https?:\/\/([-a-z]+)\.(?:m\.)?wikipedia\.org\/(?:wiki|\1-[-a-z]+)\/([^#?]+)(?:#(.+))?/)
  if (!m) return null
  let title: string
  try {
    title = decodeURIComponent(m[2]).replace(/_/g, ' ')
    if (m[3]) title += '#' + decodeURIComponent(m[3]).replace(/_/g, ' ')
  } catch {
    title = m[2].replace(/_/g, ' ')
  }
  title = title.slice(0, 1).toUpperCase() + title.slice(1)
  return { lang: m[1], title }
}

export function writeWikipedia(field: FieldDef, tags: FieldTags, lang: string, rawTitle: string): TagPatch {
  const key = field.key ?? 'wikipedia'
  const parsed = parseWikipediaUrl(rawTitle)
  const l = parsed?.lang ?? (lang.trim() || 'cs')
  let title = cleanValue(parsed?.title ?? rawTitle)
  const prefixed = /^([a-z][a-z-]{1,11}):(.+)$/.exec(title)
  let finalLang = l
  if (prefixed && !parsed && LANGUAGES.some((x) => x.code === prefixed[1])) {
    finalLang = prefixed[1]
    title = prefixed[2]
  }
  if (!title) {
    if (isMixedTag(tags, key)) return {}
    return { [key]: undefined }
  }
  return { [key]: `${finalLang}:${title}` }
}

export function wikipediaLink(state: { lang: string; title: string }): string | null {
  if (!state.title || !state.lang) return null
  const [page, anchor] = state.title.split('#')
  const enc = (s: string) => encodeURIComponent(s.replace(/ /g, '_'))
  return `https://${state.lang}.wikipedia.org/wiki/${enc(page)}${anchor ? '#' + enc(anchor) : ''}`
}

export function readDirectional(field: FieldDef, tags: FieldTags): Record<string, TagValue> {
  const { fallbackKey, bothKey, keys } = directionalKeys(field)
  const variants = (k: string): (string | undefined)[] => {
    const v = tags[k]
    if (Array.isArray(v)) return uniq(v.map((x) => x || undefined))
    return [v || undefined]
  }
  const out: Record<string, TagValue> = {}
  for (const key of keys) {
    const vals = new Set<string | undefined>()
    for (const fb of variants(fallbackKey)) {
      for (const both of variants(bothKey)) {
        for (const own of variants(key)) {
          let hadCommon = false
          if (fb === 'both') {
            vals.add('yes')
            hadCommon = true
          } else if (fb) {
            const re = new RegExp(`:${fb}(:|$)`)
            if (keys.some((k) => re.test(k))) vals.add(re.test(key) ? 'yes' : 'no')
            else vals.add(fb)
            hadCommon = true
          }
          if (both) {
            vals.add(both)
            hadCommon = true
          }
          if (own || !hadCommon) vals.add(own)
        }
      }
    }
    const list = [...vals]
    out[key] = list.length > 1 ? list.map((v) => v ?? '') : list[0]
  }
  return out
}

export function directionalSubfield(field: FieldDef, key: string): FieldDef {
  return { ...field, type: 'combo', key, keys: undefined, label: field.types?.[key] ?? key }
}

export function writeDirectional(field: FieldDef, tags: FieldTags, key: string, newValue: string | undefined): TagPatch {
  const { commonKey, otherCommonKey, fallbackKey, keys } = directionalKeys(field)
  const otherKey = key === keys[0] ? keys[1] : keys[0]
  const involved = uniq([commonKey, otherCommonKey, fallbackKey, ...keys])
  const cur: Record<string, string | undefined> = {}
  for (const k of involved) cur[k] = stringTag(tags, k)
  const next = { ...cur }
  let otherValue = cur[otherKey] || cur[commonKey] || cur[otherCommonKey]
  const read = readDirectional(field, tags)
  if (Array.isArray(read[otherKey])) otherValue = undefined
  if (cur[fallbackKey] === 'both' && !cur[otherKey]) otherValue = 'yes'
  else if (cur[fallbackKey] && !cur[otherKey]) {
    const re = new RegExp(`:${cur[fallbackKey]}(:|$)`)
    if (re.test(otherKey)) otherValue = 'yes'
    else if (re.test(key)) otherValue = 'no'
  }
  if (newValue === otherValue) {
    next[commonKey] = newValue
    next[key] = undefined
    next[otherKey] = undefined
    next[otherCommonKey] = undefined
  } else {
    next[key] = newValue
    next[commonKey] = undefined
    next[otherCommonKey] = undefined
    next[otherKey] = otherValue
  }
  const patch: TagPatch = {}
  for (const k of involved) if (next[k] !== cur[k] || Array.isArray(tags[k])) patch[k] = next[k]
  return patch
}

export type SpeedUnit = 'km/h' | 'mph'

export const ROADSPEED_SPECIAL = ['none', 'walk', 'signals']

export interface RoadspeedState {
  value: string
  unit: SpeedUnit
  mixed: string[] | null
  special: string | null
}

export function readRoadspeed(field: FieldDef, tags: FieldTags, locale: Locale): RoadspeedState {
  const st = valueState(tags[field.key ?? 'maxspeed'])
  if (st.mixed) return { value: '', unit: 'km/h', mixed: st.mixed, special: null }
  const raw = st.value
  if (!raw) return { value: '', unit: 'km/h', mixed: null, special: null }
  const unit: SpeedUnit = raw.includes('mph') ? 'mph' : 'km/h'
  if (ROADSPEED_SPECIAL.includes(raw)) return { value: raw, unit, mixed: null, special: raw }
  const n = parseFloat(raw)
  if (isNaN(n) || !/^\s*-?[\d.]+\s*(mph|km\/h|kmh)?\s*$/.test(raw)) return { value: raw, unit, mixed: null, special: null }
  return { value: formatNumber(locale, n, fractionDigitsOf(raw.replace(/[^\d.-]/g, ''))), unit, mixed: null, special: null }
}

export function writeRoadspeed(field: FieldDef, tags: FieldTags, raw: string, unit: SpeedUnit): TagPatch {
  const key = field.key ?? 'maxspeed'
  const v = cleanValue(raw)
  if (!v && isMixedTag(tags, key)) return {}
  if (!v) return { [key]: undefined }
  const special = ROADSPEED_SPECIAL.find((s) => s === v.toLowerCase())
  if (special) return { [key]: special }
  const n = parseNumber(v)
  if (!isFinite(n)) return { [key]: v }
  const num = n.toFixed(fractionDigitsOf(v))
  return { [key]: unit === 'mph' ? `${num} mph` : num }
}

export function roadspeedSuggestions(unit: SpeedUnit): number[] {
  return unit === 'mph' ? [5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80] : [20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130]
}

export type HeightUnit = 'm' | 'ft'

export const ROADHEIGHT_SPECIAL = ['default', 'none', 'below_default']

export interface RoadheightState {
  primary: string
  secondary: string
  unit: HeightUnit
  mixed: string[] | null
  special: string | null
}

export function readRoadheight(field: FieldDef, tags: FieldTags, locale: Locale): RoadheightState {
  const st = valueState(tags[field.key ?? 'maxheight'])
  if (st.mixed) return { primary: '', secondary: '', unit: 'm', mixed: st.mixed, special: null }
  const raw = st.value
  if (!raw) return { primary: '', secondary: '', unit: 'm', mixed: null, special: null }
  if (ROADHEIGHT_SPECIAL.includes(raw)) return { primary: raw, secondary: '', unit: 'm', mixed: null, special: raw }
  if (raw.includes("'") || raw.includes('"')) {
    const ft = raw.match(/(-?[\d.]+)'/)
    const inch = raw.match(/(-?[\d.]+)"/)
    return {
      primary: ft ? formatNumber(locale, parseFloat(ft[1]), fractionDigitsOf(ft[1])) : '',
      secondary: inch ? formatNumber(locale, parseFloat(inch[1]), fractionDigitsOf(inch[1])) : '',
      unit: 'ft',
      mixed: null,
      special: null,
    }
  }
  const n = parseFloat(raw)
  if (isNaN(n) || !RAW_NUMBER.test(raw.trim())) return { primary: raw, secondary: '', unit: 'm', mixed: null, special: null }
  return { primary: formatNumber(locale, n, fractionDigitsOf(raw)), secondary: '', unit: 'm', mixed: null, special: null }
}

export function writeRoadheight(field: FieldDef, tags: FieldTags, primary: string, secondary: string, unit: HeightUnit): TagPatch {
  const key = field.key ?? 'maxheight'
  const p = cleanValue(primary)
  const s = cleanValue(secondary)
  if (!p && !s && isMixedTag(tags, key)) return {}
  if (!p && !s) return { [key]: undefined }
  const special = ROADHEIGHT_SPECIAL.find((x) => x === p.toLowerCase())
  if (special) return { [key]: special }
  const pn = p ? parseNumber(p) : NaN
  const sn = s ? parseNumber(s) : NaN
  if (unit === 'm' || (p && !isFinite(pn)) || (s && !isFinite(sn))) {
    if (!isFinite(pn)) return { [key]: p || undefined }
    return { [key]: pn.toFixed(fractionDigitsOf(p)) }
  }
  const ft = p ? `${pn.toFixed(fractionDigitsOf(p))}'` : ''
  const inch = s ? `${sn.toFixed(fractionDigitsOf(s))}"` : ''
  return { [key]: ft + inch }
}
