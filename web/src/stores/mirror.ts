import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import { ApiError, apiRequest, errorMessage } from '@/api/client'
import { t } from '@/i18n'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useEditorStore } from './editor'
import { useDraftsStore } from './drafts'

export type MirrorHealth = 'ok' | 'behind' | 'stale' | 'unknown'

export const BUSY_STATES = ['clipping', 'updating', 'checking', 'swapping', 'rebuilding', 'building'] as const

const OK_LAG_SECONDS = 10 * 60
const BEHIND_LAG_SECONDS = 2 * 60 * 60

type Json = Record<string, unknown>

function str(o: Json | null | undefined, ...keys: string[]): string | null {
  for (const k of keys) {
    const v = o?.[k]
    if (typeof v === 'string' && v) return v
  }
  return null
}

function num(o: Json | null | undefined, ...keys: string[]): number | null {
  for (const k of keys) {
    const v = o?.[k]
    if (typeof v === 'number' && Number.isFinite(v)) return v
  }
  return null
}

function obj(o: Json | null | undefined, key: string): Json | null {
  const v = o?.[key]
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null
}

export const useMirrorStore = defineStore('mirror', () => {
  const app = useAppStore()
  const live = shallowRef<Json | null>(null)
  const now = ref(Date.now())
  const pendingReload = ref(false)
  const updateSupported = ref(true)
  const requesting = ref(false)
  const timer = setInterval(() => (now.value = Date.now()), 30_000)
  onScopeDispose(() => clearInterval(timer))

  const merged = computed<Json>(() => {
    const base = (app.mirror ?? {}) as unknown as Json
    return live.value ? { ...base, ...live.value, sidecar: obj(live.value, 'sidecar') ?? obj(base, 'sidecar') } : base
  })
  const sidecar = computed(() => obj(merged.value, 'sidecar'))
  const lastCycle = computed(() => obj(merged.value, 'lastCycle'))

  const sequence = computed(() => num(merged.value, 'dataSequence') ?? num(sidecar.value, 'replicationSequence'))
  const osmTime = computed(() => {
    if (num(merged.value, 'dataSequence') != null) return str(merged.value, 'dataTimestamp')
    return str(sidecar.value, 'replicationTimestamp')
  })
  const fileTime = computed(() => str(merged.value, 'dataTimestamp', 'openedAt'))
  const dataTime = computed(() => osmTime.value ?? fileTime.value)
  const isFileTime = computed(() => !osmTime.value && !!fileTime.value)

  const lagSeconds = computed<number | null>(() => {
    const l = num(merged.value, 'lagSeconds')
    if (l != null) return l
    const ts = dataTime.value ? Date.parse(dataTime.value) : NaN
    return Number.isNaN(ts) ? null : Math.max(0, Math.round((now.value - ts) / 1000))
  })

  const state = computed(() => str(merged.value, 'state', 'phase'))
  const busy = computed(() => !!state.value && (BUSY_STATES as readonly string[]).includes(state.value))
  const lastResult = computed(() => str(lastCycle.value, 'result') ?? str(sidecar.value, 'result'))
  const lastError = computed(() => str(lastCycle.value, 'error') ?? str(sidecar.value, 'error') ?? str(merged.value, 'error'))
  const lastRunAt = computed(() => str(lastCycle.value, 'finishedAt', 'startedAt') ?? str(sidecar.value, 'lastRunAt'))
  const lastDurationMs = computed(() => num(lastCycle.value, 'durationMs', 'tookMs'))
  const nextCycleAt = computed(() => str(merged.value, 'nextCycleAt', 'nextUpdateAt'))
  const nextRebuildAt = computed(() => str(merged.value, 'nextFullBuildDue') ?? str(sidecar.value, 'nextFullBuildDue'))
  const available = computed(() => merged.value.available !== false)

  const health = computed<MirrorHealth>(() => {
    if (!available.value || lastResult.value === 'failed' || lastResult.value === 'error') return 'stale'
    const lag = lagSeconds.value
    if (lag == null) return 'unknown'
    if (lag < OK_LAG_SECONDS) return 'ok'
    if (lag < BEHIND_LAG_SECONDS) return 'behind'
    return 'stale'
  })

  function onStatusEvent(e: Json) {
    const body = obj(e, 'status') ?? e
    const { type: _type, ...rest } = body
    void _type
    live.value = rest
  }

  function onSwapped() {
    live.value = null
    const editing = useEditorStore().active || useDraftsStore().reviewing
    if (editing) {
      pendingReload.value = true
      return false
    }
    pendingReload.value = false
    return true
  }

  function reloadMapData() {
    pendingReload.value = false
    useMapStore().refreshTiles()
  }

  async function updateNow() {
    if (busy.value || requesting.value || !updateSupported.value) return
    requesting.value = true
    try {
      await apiRequest('POST', '/api/mirror/update-now')
      app.toast(t('shell.mirror.updateStarted'))
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 405)) updateSupported.value = false
      else app.toast(errorMessage(e), 'error')
    } finally {
      requesting.value = false
    }
  }

  return {
    sequence, osmTime, fileTime, dataTime, isFileTime, lagSeconds, state, busy, lastResult, lastError, lastRunAt, lastDurationMs,
    nextCycleAt, nextRebuildAt, available, health, pendingReload, updateSupported, requesting,
    onStatusEvent, onSwapped, reloadMapData, updateNow,
  }
})
