import { defineStore } from 'pinia'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import type { BBox, Issue, OsmRef, Rule, RuleIssuesRequest, RuleRunResult, Severity } from '@/generated/models'
import { api, errorMessage, isAbort } from '@/api/client'
import { useMapStore } from './map'
import { ruleColor } from '@/lib/colors'
import { matchesTags, parseTagFilter } from '@/lib/tagfilter'
import { sameRef } from '@/lib/osm'
import { containsBBox, padBBox } from '@/lib/geo'

export const ISSUE_MIN_ZOOM = 13

const HIDDEN_KEY = 'ore.hiddenRules'

export interface ShownIssue {
  issue: Issue
  color: string
}

function readHidden(): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(HIDDEN_KEY) ?? '[]') as string[])
  } catch {
    return new Set()
  }
}

export const useRulesStore = defineStore('rules', () => {
  const mapStore = useMapStore()

  const rules = ref<Rule[]>([])
  const loaded = ref(false)
  const hidden = reactive(readHidden())
  const results = shallowRef<Record<string, RuleRunResult>>({})
  const loading = reactive(new Set<string>())
  const errors = reactive(new Map<string, string>())
  const severities = reactive<Record<Severity, boolean>>({ info: true, warning: true, error: true })
  const tagFilterText = ref('')
  const tagFilter = computed(() => parseTagFilter(tagFilterText.value))

  const loadedBBox = ref<BBox | null>(null)
  const controllers = new Map<string, AbortController>()

  const colorOf = computed(() => {
    const m = new Map<string, string>()
    rules.value.forEach((r, i) => m.set(r.id, ruleColor(i)))
    return m
  })
  const ruleById = computed(() => new Map(rules.value.map((r) => [r.id, r])))

  function isVisible(id: string) {
    return !hidden.has(id)
  }

  function persistHidden() {
    try {
      localStorage.setItem(HIDDEN_KEY, JSON.stringify([...hidden]))
    } catch {}
  }

  function toggle(id: string, on?: boolean) {
    const show = on ?? hidden.has(id)
    if (show) hidden.delete(id)
    else hidden.add(id)
    persistHidden()
    if (show && !results.value[id]) void loadRule(id)
  }

  function showOnly(id: string) {
    for (const r of rules.value) if (r.id !== id) hidden.add(r.id)
    hidden.delete(id)
    persistHidden()
    if (!results.value[id]) void loadRule(id)
  }

  function showAll() {
    hidden.clear()
    persistHidden()
    void refresh()
  }

  async function load() {
    try {
      rules.value = await api.rules()
      loaded.value = true
    } catch (e) {
      console.warn('rules', e)
    }
  }

  const scope = computed<RuleIssuesRequest | null>(() => {
    if (mapStore.area) return { area: mapStore.area.name }
    if (mapStore.polygon) return { polygon: mapStore.polygon }
    if (mapStore.bbox && mapStore.zoom >= ISSUE_MIN_ZOOM) return { bbox: padBBox(mapStore.bbox, 0.25) }
    return null
  })

  const fixedScope = computed(() => !!mapStore.area || !!mapStore.polygon)
  const needsZoom = computed(() => !fixedScope.value && mapStore.zoom < ISSUE_MIN_ZOOM)

  async function loadRule(id: string) {
    const s = scope.value
    if (!s) return
    controllers.get(id)?.abort()
    const ctrl = new AbortController()
    controllers.set(id, ctrl)
    loading.add(id)
    errors.delete(id)
    try {
      const r = await api.issues(id, { ...s, limit: 2000 }, ctrl.signal)
      results.value = { ...results.value, [id]: r }
    } catch (e) {
      if (!isAbort(e)) errors.set(id, errorMessage(e))
    } finally {
      if (controllers.get(id) === ctrl) {
        loading.delete(id)
        controllers.delete(id)
      }
    }
  }

  async function refresh() {
    if (!scope.value) {
      results.value = {}
      return
    }
    loadedBBox.value = scope.value.bbox ?? null
    await Promise.all(rules.value.filter((r) => isVisible(r.id)).map((r) => loadRule(r.id)))
  }

  function onViewChanged() {
    if (fixedScope.value) return
    const b = mapStore.bbox
    if (!scope.value || !b) {
      if (Object.keys(results.value).length) results.value = {}
      loadedBBox.value = null
      return
    }
    if (loadedBBox.value && containsBBox(loadedBBox.value, b)) return
    void refresh()
  }

  watch(
    () => [mapStore.area?.name, mapStore.polygon],
    () => {
      results.value = {}
      void refresh()
    },
  )

  const shownIssues = computed<ShownIssue[]>(() => {
    const f = tagFilter.value
    const out: ShownIssue[] = []
    for (const r of rules.value) {
      if (!isVisible(r.id)) continue
      const res = results.value[r.id]
      if (!res) continue
      const color = colorOf.value.get(r.id) ?? '#888'
      for (const issue of res.issues) {
        if (!severities[issue.severity]) continue
        if (f.conds.length && !matchesTags(f, issue.tags)) continue
        out.push({ issue, color })
      }
    }
    return out
  })

  function countFor(id: string): { count: number; shown: number; truncated: boolean } | null {
    const res = results.value[id]
    if (!res) return null
    const f = tagFilter.value
    const shown = res.issues.filter((i) => severities[i.severity] && (!f.conds.length || matchesTags(f, i.tags))).length
    return { count: res.issueCount, shown, truncated: res.truncated }
  }

  function issuesFor(ref: OsmRef): Issue[] {
    const out: Issue[] = []
    for (const res of Object.values(results.value)) for (const i of res.issues) if (sameRef(i.ref, ref)) out.push(i)
    return out
  }

  async function findIssue(ruleId: string, ref: OsmRef, near: BBox | null): Promise<Issue | null> {
    const cached = results.value[ruleId]?.issues.find((i) => sameRef(i.ref, ref))
    if (cached || !near) return cached ?? null
    try {
      const r = await api.issues(ruleId, { bbox: near, limit: 200 })
      return r.issues.find((i) => sameRef(i.ref, ref)) ?? null
    } catch {
      return null
    }
  }

  return {
    rules, loaded, hidden, results, loading, errors, severities, tagFilterText, tagFilter, loadedBBox,
    colorOf, ruleById, scope, needsZoom, shownIssues,
    isVisible, toggle, showOnly, showAll, load, loadRule, refresh, onViewChanged, countFor, issuesFor, findIssue,
  }
})
