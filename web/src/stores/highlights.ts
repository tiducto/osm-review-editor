import { defineStore } from 'pinia'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import type { CoverageStats, HighlightEvaluation, HighlightFile, HighlightRule, JsonValue } from '@/generated/models'
import { mapCssMapLibreFilter, mapCssParse, mapCssRules } from 'osm-review-shared'
import { rulesApi } from '@/api/rules'
import { errorMessage, isAbort } from '@/api/client'
import { useMapStore } from './map'
import { useAppStore } from './app'
import { scopeOf, tilesFor } from '@/lib/scope'
import { padBBox } from '@/lib/geo'
import { parseTagFilter } from '@/lib/tagfilter'
import type { IdSets } from '@/map/highlightStyle'

export const FILTER_PATH = 'filter:quick'
export const EVAL_ZOOM = 13
export const EVAL_MIN_MAP_ZOOM = 12
const MAX_EVAL_TILES = 48
const MAX_TILES_PER_FILE = 160
const STATS_MIN_ZOOM = 11
const TILE_OPTIONS = { tagPrefix: '', typeProperty: '$type', closedProperty: '$closed' }

interface StatsEntry {
  key: string
  data: CoverageStats | null
  loading: boolean
  error: string | null
}

interface RuleInfo {
  index: number
  line: number
  hasStyle: boolean
  selectors: string[]
  style: Record<string, JsonValue>
}

export function compileLocal(path: string, source: string, title: string): { file: HighlightFile | null; error: string | null } {
  const parsed = mapCssParse(source, path)
  const diags = (parsed.diagnostics ?? []) as HighlightFile['diagnostics']
  const firstError = diags.find((d) => d.severity === 'error')
  if (!parsed.ok || firstError) return { file: null, error: firstError?.message ?? 'Cannot parse' }
  const meta = (parsed.meta ?? {}) as Record<string, JsonValue>
  const legend = (parsed.legend ?? []) as HighlightFile['legend']
  const rules: HighlightRule[] = (mapCssRules(parsed.stylesheet) as RuleInfo[])
    .filter((r) => r.hasStyle)
    .map((r) => {
      const filter = mapCssMapLibreFilter(parsed.stylesheet, r.index, TILE_OPTIONS) as JsonValue | null
      const l = legend.find((e) => e.rule === r.index)
      const color = l?.color ?? (typeof r.style.color === 'string' ? r.style.color : typeof r.style['fill-color'] === 'string' ? (r.style['fill-color'] as string) : null)
      return { index: r.index, line: r.line, selectors: r.selectors, legend: l?.label ?? null, color, compiled: filter !== null && filter !== undefined, filter: filter ?? null, style: r.style }
    })
  return {
    file: {
      path,
      hash: String(source.length) + ':' + source.slice(0, 64),
      title: typeof meta.title === 'string' ? meta.title : title,
      description: typeof meta.description === 'string' ? meta.description : null,
      dimOthers: meta['-ore-dim-others'] === true || meta['-ore-dim-others'] === 'true',
      stats: typeof meta['-ore-stats'] === 'string' ? (meta['-ore-stats'] as string) : null,
      legend,
      rules,
      allCompiled: rules.every((r) => r.compiled),
      goql: null,
      valid: true,
      readOnly: true,
      diagnostics: diags,
    },
    error: null,
  }
}

function quote(v: string): string {
  return /^[A-Za-z0-9_:.-]+$/.test(v) ? v : `"${v.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
}

function escapeRegex(v: string): string {
  return v.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
}

export function filterToMapCss(text: string, color = '#e11d48'): { selector: string; source: string } | { error: string } {
  const f = parseTagFilter(text)
  if (f.error) return { error: f.error }
  if (!f.conds.length) return { error: 'Type a condition like highway=footway !surface' }
  const conds = f.conds
    .map((c) => {
      const k = quote(c.key)
      switch (c.op) {
        case 'has':
          return `[${k}]`
        case 'not':
          return `[!${k}]`
        case 'eq':
          return c.values.length === 1 ? `[${k}=${quote(c.values[0])}]` : `[${k}=~/^(${c.values.map(escapeRegex).join('|')})$/]`
        case 'ne':
          return c.values.length === 1 ? `[${k}!=${quote(c.values[0])}]` : `[${k}!~/^(${c.values.map(escapeRegex).join('|')})$/]`
        case 'like':
          return `[${k}*=${quote(c.text)}]`
      }
    })
    .join('')
  const selector = `*${conds}`
  const source = `meta {
  title: "${text.replace(/"/g, "'")}";
  -ore-dim-others: true;
  -ore-stats: count;
}

way${conds} {
  color: ${color};
  width: 4;
  -ore-legend: "${text.replace(/"/g, "'")}";
}

node${conds} {
  symbol-shape: circle;
  symbol-size: 10;
  symbol-fill-color: ${color};
}
`
  return { selector, source }
}

export const useHighlightsStore = defineStore('highlights', () => {
  const mapStore = useMapStore()
  const app = useAppStore()

  const files = ref<HighlightFile[]>([])
  const loaded = ref(false)
  const loadError = ref<string | null>(null)
  const active = ref<string[]>(readUrl())
  const quickText = ref(new URLSearchParams(location.search).get('hlq') ?? '')
  const quickFile = shallowRef<HighlightFile | null>(null)
  const quickError = ref<string | null>(null)
  const preview = shallowRef<HighlightFile | null>(null)

  const evaluations = new Map<string, { hash: string; tiles: Map<string, HighlightEvaluation> }>()
  const evalVersion = ref(0)
  const evaluating = ref(0)
  const evalTruncated = ref(false)
  const evalNeedsZoom = ref(false)
  const stats = reactive(new Map<string, StatsEntry>())
  const legendOpen = ref(true)
  let evalCtrl: AbortController | null = null
  let urlReady = false
  const statsCtrls = new Map<string, AbortController>()

  const byPath = computed(() => new Map(files.value.map((f) => [f.path, f])))

  const activeFiles = computed<HighlightFile[]>(() => {
    const out: HighlightFile[] = []
    for (const p of active.value) {
      if (preview.value && preview.value.path === p) {
        out.push(preview.value)
        continue
      }
      const f = byPath.value.get(p)
      if (f && f.valid) out.push(f)
    }
    if (preview.value && !active.value.includes(preview.value.path)) out.push(preview.value)
    if (quickFile.value) out.push(quickFile.value)
    return out
  })

  const dimOthers = computed(() => activeFiles.value.some((f) => f.dimOthers))

  const scope = computed(() => scopeOf(mapStore.area, (mapStore.polygon as unknown as JsonValue) ?? null, mapStore.bbox))

  function isActive(path: string) {
    return active.value.includes(path)
  }

  function toggle(path: string, on?: boolean) {
    const now = isActive(path)
    const want = on ?? !now
    if (want === now) return
    active.value = want ? [...active.value, path] : active.value.filter((p) => p !== path)
  }

  function only(path: string) {
    active.value = [path]
  }

  function clear() {
    active.value = []
    quickText.value = ''
  }

  async function load() {
    try {
      const list = await rulesApi.highlights()
      const old = byPath.value
      files.value = list.files
      loadError.value = null
      for (const f of list.files) {
        const prev = old.get(f.path)
        if (prev && prev.hash !== f.hash) {
          evaluations.delete(f.path)
          stats.delete(f.path)
        }
      }
      loaded.value = true
      evalVersion.value++
      void refreshEvaluations()
      void refreshStats()
    } catch (e) {
      loadError.value = errorMessage(e)
    }
  }

  watch(quickText, (t) => {
    writeUrl()
    const text = t.trim()
    if (!text) {
      quickFile.value = null
      quickError.value = null
      return
    }
    const m = filterToMapCss(text)
    if ('error' in m) {
      quickFile.value = null
      quickError.value = m.error
      return
    }
    const r = compileLocal(FILTER_PATH, m.source, text)
    quickFile.value = r.file
    quickError.value = r.error
  }, { immediate: true })

  function setPreview(path: string, source: string | null) {
    if (source === null) {
      preview.value = null
      return
    }
    const r = compileLocal(path, source, path)
    if (r.file) preview.value = { ...r.file, readOnly: false }
  }

  function needsEvaluation(f: HighlightFile) {
    return f.path !== FILTER_PATH && f.rules.some((r) => !r.compiled)
  }

  function evaluatedFor(path: string): Map<number, IdSets> {
    const out = new Map<number, IdSets>()
    const e = evaluations.get(path)
    if (!e) return out
    for (const ev of e.tiles.values()) {
      for (const feat of ev.features) {
        for (const idx of feat.style.rules) {
          const sets = out.get(idx) ?? {}
          ;(sets[feat.ref.type] ??= []).push(feat.ref.id)
          out.set(idx, sets)
        }
      }
    }
    return out
  }

  async function refreshEvaluations() {
    const targets = activeFiles.value.filter((f) => needsEvaluation(f) && byPath.value.has(f.path) && preview.value?.path !== f.path)
    evalCtrl?.abort()
    if (!targets.length || !mapStore.bbox) {
      evalNeedsZoom.value = false
      return
    }
    if (mapStore.zoom < EVAL_MIN_MAP_ZOOM) {
      evalNeedsZoom.value = true
      return
    }
    const tiles = tilesFor(padBBox(mapStore.bbox, 0.15), EVAL_ZOOM, MAX_EVAL_TILES)
    if (!tiles) {
      evalNeedsZoom.value = true
      return
    }
    evalNeedsZoom.value = false
    const ctrl = new AbortController()
    evalCtrl = ctrl
    const jobs: { file: HighlightFile; tile: string }[] = []
    for (const f of targets) {
      let e = evaluations.get(f.path)
      if (!e || e.hash !== f.hash) {
        e = { hash: f.hash, tiles: new Map() }
        evaluations.set(f.path, e)
      }
      for (const t of tiles) if (!e.tiles.has(t)) jobs.push({ file: f, tile: t })
    }
    let next = 0
    let since = 0
    const worker = async () => {
      while (next < jobs.length && !ctrl.signal.aborted) {
        const job = jobs[next++]
        evaluating.value++
        try {
          const r = await rulesApi.evaluateTile(job.file.path, job.tile, ctrl.signal)
          const e = evaluations.get(job.file.path)
          if (e && e.hash === r.hash) {
            e.tiles.set(job.tile, r)
            while (e.tiles.size > MAX_TILES_PER_FILE) e.tiles.delete(e.tiles.keys().next().value!)
            if (r.truncated) evalTruncated.value = true
            if (++since >= 4) {
              since = 0
              evalVersion.value++
            }
          }
        } catch (err) {
          if (!isAbort(err)) console.warn('evaluate', job.file.path, job.tile, err)
        } finally {
          evaluating.value--
        }
      }
    }
    await Promise.all([worker(), worker(), worker(), worker()])
    if (!ctrl.signal.aborted) evalVersion.value++
  }

  const statsFiles = computed(() => activeFiles.value.filter((f) => f.legend.length > 0 || f.stats))
  const statsNeedsZoom = computed(() => scope.value?.kind === 'view' && mapStore.zoom < STATS_MIN_ZOOM)

  async function refreshStats() {
    const s = scope.value
    for (const [path, c] of statsCtrls) {
      if (!statsFiles.value.some((f) => f.path === path)) {
        c.abort()
        statsCtrls.delete(path)
      }
    }
    if (!s || statsNeedsZoom.value) return
    await Promise.all(
      statsFiles.value.map(async (f) => {
        const key = `${s.key}#${f.hash}`
        const cur = stats.get(f.path)
        if (cur && cur.key === key && (cur.data || cur.loading)) return
        statsCtrls.get(f.path)?.abort()
        const ctrl = new AbortController()
        statsCtrls.set(f.path, ctrl)
        stats.set(f.path, { key, data: cur?.data ?? null, loading: true, error: null })
        try {
          const data = f.path === FILTER_PATH || f.path === preview.value?.path ? null : await rulesApi.stats({ path: f.path, ...s.body }, ctrl.signal)
          if (statsCtrls.get(f.path) === ctrl) stats.set(f.path, { key, data, loading: false, error: data ? null : 'Save the file to see coverage' })
        } catch (e) {
          if (isAbort(e)) return
          if (statsCtrls.get(f.path) === ctrl) stats.set(f.path, { key, data: null, loading: false, error: errorMessage(e) })
        }
      }),
    )
  }

  let viewTimer: ReturnType<typeof setTimeout> | null = null
  watch(
    () => [mapStore.bbox, mapStore.zoom],
    () => {
      if (viewTimer) clearTimeout(viewTimer)
      viewTimer = setTimeout(() => {
        void refreshEvaluations()
        if (scope.value?.kind === 'view') void refreshStats()
        writeUrl()
      }, 450)
    },
  )
  watch(
    () => [active.value.join('|'), scope.value?.key],
    () => {
      void refreshEvaluations()
      void refreshStats()
      writeUrl()
    },
  )

  function readUrl(): string[] {
    const v = new URLSearchParams(location.search).get('hl')
    return v ? v.split(',').filter(Boolean) : []
  }

  function writeUrl() {
    if (!urlReady) return
    const url = new URL(location.href)
    const on = active.value.length > 0 || !!quickText.value.trim()
    if (active.value.length) url.searchParams.set('hl', active.value.join(','))
    else url.searchParams.delete('hl')
    if (quickText.value.trim()) url.searchParams.set('hlq', quickText.value.trim())
    else url.searchParams.delete('hlq')
    if (on && mapStore.area) url.searchParams.set('area', mapStore.area.name)
    else url.searchParams.delete('area')
    const m = mapStore.instance()
    if (on && m) {
      const c = m.getCenter()
      url.searchParams.set('at', `${m.getZoom().toFixed(2)}/${c.lat.toFixed(5)}/${c.lng.toFixed(5)}`)
    } else {
      url.searchParams.delete('at')
    }
    if (url.href !== location.href) history.replaceState(history.state, '', url)
  }

  async function restoreFromUrl() {
    const q = new URLSearchParams(location.search)
    const at = q.get('at')
    const area = q.get('area')
    if (at) {
      const [z, lat, lng] = at.split('/').map(Number)
      const m = mapStore.instance()
      if (m && [z, lat, lng].every((n) => Number.isFinite(n))) m.jumpTo({ center: [lng, lat], zoom: z })
    }
    try {
      if (area) await mapStore.setArea(area, !at)
    } finally {
      urlReady = true
      writeUrl()
    }
  }

  async function createFromQuick(name: string) {
    const m = filterToMapCss(quickText.value)
    if ('error' in m) {
      app.toast(m.error, 'error')
      return null
    }
    const slug = name.trim().toLowerCase().replace(/[^a-z0-9_.-]+/g, '-').replace(/^-+|-+$/g, '') || 'filter'
    const path = `highlights/${slug}.mapcss`
    try {
      await rulesApi.create(path, m.source.replace(/title: "[^"]*"/, `title: "${name.replace(/"/g, "'")}"`))
      quickText.value = ''
      await load()
      toggle(path, true)
      app.toast(`Created ${path}`, 'success')
      return path
    } catch (e) {
      app.fail(e, 'New highlight')
      return null
    }
  }

  return {
    files, loaded, loadError, active, activeFiles, dimOthers, byPath, quickText, quickFile, quickError, preview,
    evalVersion, evaluating, evalTruncated, evalNeedsZoom, stats, statsFiles, statsNeedsZoom, legendOpen, scope,
    isActive, toggle, only, clear, load, setPreview, needsEvaluation, evaluatedFor, refreshEvaluations, refreshStats,
    restoreFromUrl, writeUrl, createFromQuick,
  }
})
