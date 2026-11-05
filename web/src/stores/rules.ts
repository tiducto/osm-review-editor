import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import type { BBox, Issue, OsmRef, Rule, RuleIssuesRequest } from '@/generated/models'
import { api } from '@/api/client'
import { useMapStore } from './map'
import { ruleColor } from '@/lib/colors'

export const ISSUE_MIN_ZOOM = 13

export interface ShownIssue {
  issue: Issue
  color: string
}

export const useRulesStore = defineStore('rules', () => {
  const mapStore = useMapStore()

  const rules = ref<Rule[]>([])
  const loaded = ref(false)
  const loading = reactive(new Set<string>())
  const needsZoom = computed(() => false)
  const shownIssues = computed<ShownIssue[]>(() => [])

  const colorOf = computed(() => {
    const m = new Map<string, string>()
    rules.value.forEach((r, i) => m.set(r.id, ruleColor(i)))
    return m
  })
  const ruleById = computed(() => new Map(rules.value.map((r) => [r.id, r])))

  const scope = computed<RuleIssuesRequest | null>(() => {
    if (mapStore.area) return { area: mapStore.area.name }
    if (mapStore.polygon) return { polygon: mapStore.polygon }
    if (mapStore.bbox && mapStore.zoom >= ISSUE_MIN_ZOOM) return { bbox: mapStore.bbox }
    return null
  })

  async function load() {
    try {
      rules.value = await api.rules()
      loaded.value = true
    } catch (e) {
      console.warn('rules', e)
    }
  }

  async function refresh() {}

  function onViewChanged() {}

  function issuesFor(_ref: OsmRef): Issue[] {
    return []
  }

  async function findIssue(ruleId: string, ref: OsmRef, near: BBox | null): Promise<Issue | null> {
    if (!near || !ruleById.value.has(ruleId)) return null
    try {
      const r = await api.issues(ruleId, { bbox: near, limit: 200 })
      return r.issues.find((i) => i.ref.type === ref.type && i.ref.id === ref.id) ?? null
    } catch {
      return null
    }
  }

  return { rules, loaded, loading, needsZoom, shownIssues, colorOf, ruleById, scope, load, refresh, onViewChanged, issuesFor, findIssue }
})
