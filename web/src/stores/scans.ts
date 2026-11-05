import { defineStore } from 'pinia'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import type { JsonValue, Rule, Scan, ScanDiff, ScanIssue, ScanReport, Severity } from '@/generated/models'
import { rulesApi } from '@/api/rules'
import { api, errorMessage, isAbort } from '@/api/client'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useDraftsStore } from './drafts'
import { useRuleFilesStore } from './ruleFiles'
import { scopeOf } from '@/lib/scope'
import { formatNumber, t, tn } from '@/i18n'

export type DiffView = 'all' | 'new' | 'resolved'
export type IssueState = 'new' | 'unchanged' | 'resolved'

export interface ListedIssue {
  issue: ScanIssue
  state: IssueState
}

export const useScansStore = defineStore('scans', () => {
  const app = useAppStore()
  const mapStore = useMapStore()
  const drafts = useDraftsStore()
  const ruleFiles = useRuleFilesStore()

  const active = ref(false)
  const builtIn = ref<Rule[]>([])
  const selectedFiles = reactive(new Set<string>())
  const selectedBuiltIn = reactive(new Set<string>())
  const filesTouched = ref(false)
  const running = ref(false)
  const runStarted = ref(0)
  const runError = ref<string | null>(null)
  const history = ref<Scan[]>([])
  const report = shallowRef<ScanReport | null>(null)
  const diff = shallowRef<ScanDiff | null>(null)
  const loadingReport = ref(false)
  const view = ref<DiffView>('all')
  const severities = reactive<Record<Severity, boolean>>({ error: true, warning: true, info: true })
  const group = ref<string | null>(null)
  const text = ref('')
  const picked = reactive(new Set<number>())
  const creatingDraft = ref(false)
  const focused = ref<number | null>(null)
  const lastEvent = ref<{ scanId: string; scope: string; issueCount: number } | null>(null)
  let runCtrl: AbortController | null = null

  const scope = computed(() => scopeOf(mapStore.area, (mapStore.polygon as unknown as JsonValue) ?? null, mapStore.zoom >= 13 ? mapStore.bbox : null))
  const checkFiles = computed(() => ruleFiles.files.filter((f) => f.kind === 'check'))

  function syncDefaults() {
    if (filesTouched.value) return
    selectedFiles.clear()
    for (const f of checkFiles.value) if (!f.diagnostics.some((d) => d.severity === 'error')) selectedFiles.add(f.path)
    if (!selectedBuiltIn.size) for (const r of builtIn.value) if (r.enabled) selectedBuiltIn.add(r.id)
  }

  watch(checkFiles, syncDefaults)

  async function init() {
    try {
      builtIn.value = await api.rules()
    } catch (e) {
      console.warn('rules', e)
    }
    if (!ruleFiles.loaded) await ruleFiles.load()
    syncDefaults()
    await loadHistory()
  }

  async function loadHistory() {
    try {
      history.value = await rulesApi.scans(undefined, 30)
    } catch (e) {
      console.warn('scans', e)
    }
  }

  function toggleFile(path: string) {
    filesTouched.value = true
    if (selectedFiles.has(path)) selectedFiles.delete(path)
    else selectedFiles.add(path)
  }

  function toggleBuiltIn(id: string) {
    filesTouched.value = true
    if (selectedBuiltIn.has(id)) selectedBuiltIn.delete(id)
    else selectedBuiltIn.add(id)
  }

  async function run() {
    const s = scope.value
    if (!s || running.value) return
    running.value = true
    runError.value = null
    runStarted.value = Date.now()
    runCtrl = new AbortController()
    try {
      const scan = await rulesApi.runScan({ ...s.body, files: [...selectedFiles], builtIn: [...selectedBuiltIn] }, runCtrl.signal)
      await loadHistory()
      await openScan(scan.id)
      app.toast(tn('scan.finished', scan.issueCount, { seconds: formatNumber((scan.tookMs ?? 0) / 1000, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }), 'success')
    } catch (e) {
      if (!isAbort(e)) runError.value = errorMessage(e)
    } finally {
      running.value = false
      runCtrl = null
    }
  }

  function cancelWaiting() {
    runCtrl?.abort()
  }

  async function openScan(id: string) {
    loadingReport.value = true
    picked.clear()
    focused.value = null
    try {
      const [r, d] = await Promise.all([rulesApi.scan(id, { limit: 5000 }), rulesApi.diff(id, undefined, 5000).catch(() => null)])
      report.value = r
      diff.value = d
      group.value = null
      if (r.scan.geometry && !mapStore.area && !mapStore.polygon) {
        const g = r.scan.geometry as { type?: string }
        if (g.type === 'Polygon' && r.scan.areaName) void mapStore.setArea(r.scan.areaName, false)
      }
    } catch (e) {
      app.fail(e, t('scan.reportError'))
    } finally {
      loadingReport.value = false
    }
  }

  function closeReport() {
    report.value = null
    diff.value = null
    picked.clear()
  }

  const newIds = computed(() => new Set(diff.value?.new.map((i) => i.id) ?? []))

  const listed = computed<ListedIssue[]>(() => {
    const r = report.value
    if (!r) return []
    const t = text.value.trim().toLowerCase()
    const keep = (i: ScanIssue) =>
      severities[i.severity] &&
      (!group.value || (i.group ?? i.rule) === group.value) &&
      (!t || i.message.toLowerCase().includes(t) || i.rule.toLowerCase().includes(t) || `${i.ref.type[0]}${i.ref.id}`.includes(t))
    const out: ListedIssue[] = []
    if (view.value !== 'resolved') {
      for (const i of r.issues) {
        const state: IssueState = diff.value && diff.value.against ? (newIds.value.has(i.id) ? 'new' : 'unchanged') : 'new'
        if (view.value === 'new' && state !== 'new') continue
        if (keep(i)) out.push({ issue: i, state })
      }
    }
    if (view.value !== 'new' && diff.value) {
      if (view.value === 'resolved' || view.value === 'all') {
        for (const i of diff.value.resolved) if (view.value === 'resolved' && keep(i)) out.push({ issue: i, state: 'resolved' })
      }
    }
    return out
  })

  const groups = computed(() => {
    const r = report.value
    if (!r) return []
    const m = new Map<string, { group: string; error: number; warning: number; info: number; total: number }>()
    for (const c of r.counts) {
      const g = m.get(c.group) ?? { group: c.group, error: 0, warning: 0, info: 0, total: 0 }
      g[c.severity] += c.count
      g.total += c.count
      m.set(c.group, g)
    }
    return [...m.values()].sort((a, b) => b.error - a.error || b.warning - a.warning || b.total - a.total)
  })

  const fixable = computed(() => listed.value.filter((l) => l.state !== 'resolved' && (l.issue.fix || l.issue.deleteObject)))

  function pickAllFixable() {
    for (const l of fixable.value) picked.add(l.issue.id)
  }

  function focus(i: ScanIssue) {
    focused.value = i.id
    mapStore.flyTo(i.centroid, Math.max(mapStore.zoom, 17))
    void mapStore.select(i.ref)
  }

  async function draftFromPicked() {
    const r = report.value
    if (!r || !picked.size) return
    creatingDraft.value = true
    try {
      const res = await rulesApi.draftFromScan(r.scan.id, { issueIds: [...picked], title: t('scan.fixesTitle', { area: r.scan.areaName ?? t('scan.mapArea') }) })
      picked.clear()
      app.toast(`${tn('scan.draftCreated', res.draft.changes.length)}${res.skipped ? tn('scan.skipped', res.skipped) : ''}`, 'success')
      void drafts.loadDrafts()
      await drafts.open(res.draft.draft.id, { review: true })
    } catch (e) {
      app.fail(e, t('scan.draftError'))
    } finally {
      creatingDraft.value = false
    }
  }

  async function onScanFinished(e: { scanId: string; scope: string; issueCount: number }) {
    lastEvent.value = e
    await loadHistory()
    if (!running.value) app.toast(tn('scan.finishedScope', e.issueCount, { scope: e.scope.replace(/#.*$/, '') }), 'info', 6000)
  }

  return {
    active, builtIn, selectedFiles, selectedBuiltIn, running, runStarted, runError, history, report, diff, loadingReport, view, severities, group, text,
    picked, creatingDraft, focused, lastEvent, scope, checkFiles, listed, groups, fixable,
    init, loadHistory, toggleFile, toggleBuiltIn, run, cancelWaiting, openScan, closeReport, pickAllFixable, focus, draftFromPicked, onScanFinished,
  }
})
