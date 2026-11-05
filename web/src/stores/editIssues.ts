import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import { rulesApi } from '@/api/rules'
import type { CheckSheet, EditIssue } from '@/editor/validation/issues'
import type { EditSession } from '@/editor/session'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useRuleFilesStore } from './ruleFiles'
import { useScansStore } from './scans'

const DEBOUNCE_MS = 350

export interface SaveSummary {
  errors: EditIssue[]
  warnings: EditIssue[]
  resolve: (save: boolean) => void
}

export const useEditIssuesStore = defineStore('editIssues', () => {
  const app = useAppStore()
  const mapStore = useMapStore()
  const ruleFiles = useRuleFilesStore()
  const scans = useScansStore()

  const issues = shallowRef<EditIssue[]>([])
  const running = ref(false)
  const panelOpen = ref(false)
  const focused = ref<string | null>(null)
  const sheetPaths = ref<string[]>([])
  const sheetErrors = ref<string[]>([])
  const summary = shallowRef<SaveSummary | null>(null)
  const ignored = new Set<string>()
  const sheetCache = new Map<string, CheckSheet | null>()
  let sheets: CheckSheet[] = []
  let session: EditSession | null = null
  let blocked: () => boolean = () => false
  let timer: ReturnType<typeof setTimeout> | null = null
  let seq = 0
  let mod: typeof import('@/editor/validation/issues') | null = null
  let layer: typeof import('@/editor/issueLayer') | null = null

  const errors = computed(() => issues.value.filter((i) => i.severity === 'error'))
  const warnings = computed(() => issues.value.filter((i) => i.severity === 'warning'))
  const others = computed(() => issues.value.filter((i) => i.severity !== 'error' && i.severity !== 'warning'))

  function activeCheckPaths(): string[] {
    const checks = ruleFiles.files.filter((f) => f.kind === 'check' && !f.diagnostics.some((d) => d.severity === 'error'))
    const picked = scans.selectedFiles.size ? checks.filter((f) => scans.selectedFiles.has(f.path)) : checks
    return picked.map((f) => f.path)
  }

  async function loadSheets() {
    if (!ruleFiles.loaded) await ruleFiles.load()
    mod ??= await import('@/editor/validation/issues')
    const paths = activeCheckPaths()
    const errs: string[] = []
    const next: CheckSheet[] = []
    for (const path of paths) {
      const info = ruleFiles.files.find((f) => f.path === path)
      const key = `${path}@${info?.hash ?? ''}`
      if (!sheetCache.has(key)) {
        try {
          const file = await rulesApi.read(path)
          sheetCache.set(key, mod.parseCheckSheet(path, file.file.hash, file.content))
        } catch (e) {
          errs.push(`${path}: ${e instanceof Error ? e.message : String(e)}`)
          continue
        }
      }
      const s = sheetCache.get(key)
      if (s) next.push(s)
    }
    sheets = next
    sheetPaths.value = next.map((s) => s.path)
    sheetErrors.value = errs
  }

  watch(
    () => ruleFiles.files.filter((f) => f.kind === 'check').map((f) => `${f.path}@${f.hash}`).join('|') + [...scans.selectedFiles].join('|'),
    () => {
      if (!session) return
      void loadSheets().then(() => schedule(0))
    },
  )

  async function attach(s: EditSession, isBlocked: () => boolean) {
    session = s
    blocked = isBlocked
    ignored.clear()
    issues.value = []
    focused.value = null
    layer ??= await import('@/editor/issueLayer')
    try {
      await loadSheets()
    } catch (e) {
      app.fail(e, 'Loading check files')
    }
  }

  function detach() {
    if (timer) clearTimeout(timer)
    timer = null
    const map = mapStore.instance()
    if (map && layer) layer.removeIssueMarkers(map)
    session = null
    issues.value = []
    focused.value = null
    panelOpen.value = false
    summary.value?.resolve(false)
    summary.value = null
    seq++
  }

  function schedule(ms = DEBOUNCE_MS) {
    if (!session) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      if (blocked()) {
        schedule()
        return
      }
      void run()
    }, ms)
  }

  function draw() {
    const map = mapStore.instance()
    if (map && layer && session) layer.setIssueMarkers(map, issues.value, focused.value)
  }

  async function run(): Promise<EditIssue[]> {
    if (!session) return []
    const s = session
    const my = ++seq
    running.value = true
    mod ??= await import('@/editor/validation/issues')
    try {
      const list = await mod.collectIssues(s.adapter, sheets, ignored)
      if (my !== seq || session !== s) return list
      issues.value = list
      if (focused.value && !list.some((i) => i.id === focused.value)) focused.value = null
      draw()
      return list
    } catch (e) {
      console.warn('validation', e)
      return issues.value
    } finally {
      if (my === seq) running.value = false
    }
  }

  function focus(issue: EditIssue, opts: { select?: boolean; fly?: boolean } = {}) {
    focused.value = issue.id
    panelOpen.value = true
    draw()
    const map = mapStore.instance()
    if (opts.fly !== false && map && issue.loc) {
      const b = map.getBounds()
      if (!b.contains(issue.loc)) map.easeTo({ center: issue.loc, duration: 500 })
    }
    if (opts.select !== false && session && issue.entityIds.length) {
      const g = session.adapter.graph()
      const ids = issue.entityIds.filter((id) => g.hasEntity(id)).slice(0, 1)
      if (ids.length) session.controller.select(ids)
    }
  }

  function focusById(id: string) {
    const issue = issues.value.find((i) => i.id === id)
    if (issue) focus(issue, { fly: false })
  }

  function applyFix(issue: EditIssue, fixId: string, after: () => void): boolean {
    if (!session || !mod) return false
    const ok = mod.applyIssueFix(session.adapter, issue, fixId, ignored)
    if (!ok) {
      app.toast('This fix is no longer available', 'error')
      return false
    }
    session.controller.afterUndoRedo()
    session.controller.select(session.adapter.selected)
    after()
    schedule(0)
    return true
  }

  async function confirmSave(): Promise<boolean> {
    if (!session) return true
    if (timer) clearTimeout(timer)
    timer = null
    const list = await run()
    const errs = list.filter((i) => i.severity === 'error')
    const warns = list.filter((i) => i.severity === 'warning')
    if (!errs.length && !warns.length) return true
    return new Promise<boolean>((resolve) => {
      summary.value = {
        errors: errs,
        warnings: warns,
        resolve: (save) => {
          summary.value = null
          resolve(save)
        },
      }
    })
  }

  function review() {
    const s = summary.value
    if (!s) return
    s.resolve(false)
    panelOpen.value = true
    const first = s.errors[0] ?? s.warnings[0]
    if (first) focus(first)
  }

  return {
    issues, running, panelOpen, focused, sheetPaths, sheetErrors, summary, errors, warnings, others,
    attach, detach, schedule, run, focus, focusById, applyFix, confirmSave, review,
  }
})
