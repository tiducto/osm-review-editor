import type { ExpressionSpecification, LayerSpecification } from 'maplibre-gl'
import type { HighlightFile, HighlightRule, JsonValue, OsmType } from '@/generated/models'
import { FONT } from './osm/style'

export const HL_SRC = 'ore-hl'
export const HL_DIM = 'ore-hl-dim'
export const HL_PREFIX = 'ore-hl-'
export const DETAIL_ZOOM = 15

export type IdSets = Partial<Record<OsmType, number[]>>

type Style = Record<string, JsonValue>
type Expr = ExpressionSpecification

const LINE_PROPS = ['color', 'width', 'dashes', 'opacity']
const FILL_PROPS = ['fill-color', 'fill-opacity']
const SYMBOL_PROPS = ['symbol-size', 'symbol-fill-color', 'symbol-stroke-color', 'symbol-shape', 'symbol-stroke-width']
const CASING_PROPS = ['casing-width', 'casing-color']
const DEFAULT_COLOR = '#e11d48'

function num(v: JsonValue | undefined): number | null {
  if (typeof v === 'number') return v
  if (typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) return Number(v)
  return null
}

function str(v: JsonValue | undefined): string | null {
  return typeof v === 'string' && v ? v : null
}

function has(style: Style, props: string[]): boolean {
  return props.some((p) => style[p] !== undefined)
}

export function ruleStyle(r: HighlightRule): Style {
  const s = ((r as HighlightRule & { style?: Style }).style ?? {}) as Style
  if (r.color && s.color === undefined && s['fill-color'] === undefined) return { ...s, color: r.color }
  return s
}

export function idFilter(ids: IdSets): Expr | boolean {
  const parts: Expr[] = []
  for (const type of ['node', 'way', 'relation'] as OsmType[]) {
    const list = ids[type]
    if (!list?.length) continue
    parts.push(['all', ['==', ['get', '$type'], type], ['match', ['get', '$id'], [...new Set(list)], true, false]] as unknown as Expr)
  }
  if (!parts.length) return false
  return parts.length === 1 ? parts[0] : (['any', ...parts] as unknown as Expr)
}

export interface RenderRule {
  index: number
  filter: Expr | boolean
  style: Style
}

export function renderRules(file: HighlightFile, evaluated: Map<number, IdSets> | undefined): RenderRule[] {
  return file.rules.map((r) => ({
    index: r.index,
    filter: r.compiled && r.filter !== null ? (r.filter as unknown as Expr | boolean) : idFilter(evaluated?.get(r.index) ?? {}),
    style: ruleStyle(r),
  }))
}

function cascade(rules: RenderRule[], value: (s: Style) => JsonValue | number | null | undefined, fallback: JsonValue | number): Expr | JsonValue | number {
  const parts: unknown[] = []
  for (let i = rules.length - 1; i >= 0; i--) {
    const v = value(rules[i].style)
    if (v === undefined || v === null) continue
    if (rules[i].filter === false) continue
    parts.push(rules[i].filter, Array.isArray(v) ? ['literal', v] : v)
  }
  if (!parts.length) return fallback
  return ['case', ...parts, Array.isArray(fallback) ? ['literal', fallback] : fallback] as unknown as Expr
}

function anyOf(rules: RenderRule[]): Expr | boolean {
  const live = rules.filter((r) => r.filter !== false)
  if (!live.length) return false
  if (live.some((r) => r.filter === true)) return true
  return live.length === 1 ? (live[0].filter as Expr) : (['any', ...live.map((r) => r.filter)] as unknown as Expr)
}

function zoomScaled(e: unknown, low = 0.6): Expr {
  return ['interpolate', ['linear'], ['zoom'], 12, ['*', low, e], 16, e] as unknown as Expr
}

function lineWidth(s: Style): number {
  return num(s.width) ?? 2
}

function dashes(s: Style): number[] | null {
  const d = s.dashes
  if (!Array.isArray(d)) return null
  const list = d.map((x) => num(x as JsonValue)).filter((x): x is number => x !== null && x >= 0)
  if (list.length < 2) return null
  const w = Math.max(lineWidth(s), 1)
  return list.map((x) => +(x / w).toFixed(3))
}

export interface TextOptions {
  key: string | null
  literal: string | null
}

function text(s: Style): TextOptions | null {
  const t = s.text
  if (t && typeof t === 'object' && !Array.isArray(t) && typeof (t as Record<string, JsonValue>).tag === 'string') {
    return { key: (t as Record<string, string>).tag, literal: null }
  }
  if (typeof t === 'string' && t) return { key: null, literal: t }
  return null
}

export interface HighlightLayersInput {
  file: HighlightFile
  slot: number
  evaluated?: Map<number, IdSets>
  baseSource: string
}

export function highlightLayers({ file, slot, evaluated, baseSource }: HighlightLayersInput): LayerSpecification[] {
  const rules = renderRules(file, evaluated)
  const lineRules = rules.filter((r) => has(r.style, LINE_PROPS))
  const fillRules = rules.filter((r) => has(r.style, FILL_PROPS))
  const casingRules = lineRules.filter((r) => has(r.style, CASING_PROPS))
  const symbolRules = rules.filter((r) => has(r.style, SYMBOL_PROPS) || (has(r.style, ['color']) && !has(r.style, ['width'])))
  const textRules = rules.filter((r) => text(r.style))
  const out: LayerSpecification[] = []
  const id = (name: string, src: 'lo' | 'hi', sl: string) => `${HL_PREFIX}${slot}-${name}-${sl}-${src}`

  const sources: { src: 'lo' | 'hi'; source: string; range: { minzoom?: number; maxzoom?: number } }[] = [
    { src: 'lo', source: HL_SRC, range: { maxzoom: DETAIL_ZOOM } },
    { src: 'hi', source: baseSource, range: { minzoom: DETAIL_ZOOM } },
  ]

  const width = cascade(lineRules, (s) => num(s.width), 2)
  const casingExtra = cascade(casingRules, (s) => num(s['casing-width']), 0)

  for (const { src, source, range } of sources) {
    const fillFilter = anyOf(fillRules)
    if (fillFilter !== false) {
      out.push({
        id: id('fill', src, 'area'),
        type: 'fill',
        source,
        'source-layer': 'area',
        ...range,
        filter: fillFilter as Expr,
        paint: {
          'fill-color': cascade(fillRules, (s) => str(s['fill-color']) ?? str(s.color), DEFAULT_COLOR) as Expr,
          'fill-opacity': cascade(fillRules, (s) => num(s['fill-opacity']), 0.35) as Expr,
        },
      })
    }
    const casingFilter = anyOf(casingRules)
    for (const sl of ['area', 'line']) {
      if (casingFilter === false) break
      out.push({
        id: id('casing', src, sl),
        type: 'line',
        source,
        'source-layer': sl,
        ...range,
        filter: casingFilter as Expr,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': cascade(casingRules, (s) => str(s['casing-color']), '#ffffff') as Expr,
          'line-width': zoomScaled(['+', width, ['*', 2, casingExtra]]),
        },
      })
    }
    const lineFilter = anyOf(lineRules)
    for (const sl of ['area', 'line']) {
      if (lineFilter === false) break
      out.push({
        id: id('line', src, sl),
        type: 'line',
        source,
        'source-layer': sl,
        ...range,
        filter: lineFilter as Expr,
        layout: { 'line-cap': 'butt', 'line-join': 'round' },
        paint: {
          'line-color': cascade(lineRules, (s) => str(s.color), DEFAULT_COLOR) as Expr,
          'line-width': zoomScaled(width),
          'line-opacity': cascade(lineRules, (s) => num(s.opacity), 1) as Expr,
          'line-dasharray': cascade(lineRules, (s) => dashes(s), [1, 0]) as Expr,
        },
      })
    }
    const symbolFilter = anyOf(symbolRules)
    for (const sl of ['point', 'vertex']) {
      if (symbolFilter === false) break
      out.push({
        id: id('circle', src, sl),
        type: 'circle',
        source,
        'source-layer': sl,
        ...range,
        filter: symbolFilter as Expr,
        paint: {
          'circle-radius': cascade(symbolRules, (s) => {
            const size = num(s['symbol-size'])
            return size !== null ? Math.max(2, size / 2) : num(s.width) !== null ? Math.max(3, num(s.width)!) : null
          }, 5) as Expr,
          'circle-color': cascade(symbolRules, (s) => str(s['symbol-fill-color']) ?? str(s.color), DEFAULT_COLOR) as Expr,
          'circle-stroke-color': cascade(symbolRules, (s) => str(s['symbol-stroke-color']), '#ffffff') as Expr,
          'circle-stroke-width': cascade(symbolRules, (s) => num(s['symbol-stroke-width']), 1.5) as Expr,
        },
      })
    }
    const textFilter = anyOf(textRules)
    for (const sl of ['line', 'point', 'area']) {
      if (textFilter === false) break
      const label = cascade(
        textRules,
        (s) => {
          const t = text(s)
          if (!t) return null
          return (t.key ? ['to-string', ['coalesce', ['get', t.key], '']] : t.literal) as unknown as JsonValue
        },
        '',
      )
      out.push({
        id: id('text', src, sl),
        type: 'symbol',
        source,
        'source-layer': sl,
        ...range,
        filter: textFilter as Expr,
        layout: {
          'text-field': label as Expr,
          'text-font': FONT,
          'text-size': cascade(textRules, (s) => num(s['font-size']), 12) as Expr,
          'symbol-placement': sl === 'line' ? 'line' : 'point',
          'text-offset': sl === 'line' ? [0, 0] : [0, 1.1],
          'text-max-width': 10,
        },
        paint: {
          'text-color': cascade(textRules, (s) => str(s['text-color']) ?? str(s.color), '#1f2937') as Expr,
          'text-halo-color': cascade(textRules, (s) => str(s['text-halo-color']), 'rgba(255,255,255,0.9)') as Expr,
          'text-halo-width': 1.5,
        },
      })
    }
  }
  return out
}

export function dimLayer(color: string): LayerSpecification {
  return { id: HL_DIM, type: 'background', paint: { 'background-color': color, 'background-opacity': 0.72 } }
}

export function filterKeys(files: HighlightFile[]): string[] {
  const keys = new Set<string>()
  const walk = (e: unknown) => {
    if (!Array.isArray(e)) return
    if ((e[0] === 'get' || e[0] === 'has') && typeof e[1] === 'string' && e.length === 2) {
      if (!e[1].startsWith('$')) keys.add(e[1])
      return
    }
    for (const x of e) walk(x)
  }
  for (const f of files) {
    for (const r of f.rules) {
      if (r.compiled) walk(r.filter)
      const t = text(ruleStyle(r))
      if (t?.key) keys.add(t.key)
    }
  }
  return [...keys].sort()
}
