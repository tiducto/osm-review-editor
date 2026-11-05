import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import type { MirrorStatus, ReplicationStatus, SidecarStatus } from '@/generated/models'
import { ApiError, apiRequest, errorMessage } from '@/api/client'
import { t } from '@/i18n'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useEditorStore } from './editor'
import { useDraftsStore } from './drafts'

export type MirrorHealth = 'ok' | 'behind' | 'stale' | 'unknown'

export const SIDECAR_BUSY = ['rebuilding', 'updating', 'checking', 'swapping'] as const
export const CLIPPER_BUSY = ['clipping', 'rebasing'] as const

const OK_LAG_SECONDS = 10 * 60
const BEHIND_LAG_SECONDS = 2 * 60 * 60

type Partial<T> = { [K in keyof T]?: T[K] | null }

export const useMirrorStore = defineStore('mirror', () => {
  const app = useAppStore()
  const live = shallowRef<Partial<MirrorStatus> | null>(null)
  const now = ref(Date.now())
  const pendingReload = ref(false)
  const updateSupported = ref(true)
  const requesting = ref(false)
  const overlayVersion = ref(0)
  const timer = setInterval(() => (now.value = Date.now()), 15_000)
  onScopeDispose(() => clearInterval(timer))

  const status = computed<Partial<MirrorStatus>>(() => ({ ...((app.mirror ?? {}) as Partial<MirrorStatus>), ...(live.value ?? {}) }))
  const sidecar = computed<Partial<SidecarStatus> | null>(() => status.value.sidecar ?? null)
  const replication = computed<Partial<ReplicationStatus> | null>(() => status.value.replication ?? null)

  const osmTime = computed(() => status.value.dataTimestamp ?? sidecar.value?.replicationTimestamp ?? null)
  const fileTime = computed(() => status.value.openedAt ?? null)
  const dataTime = computed(() => osmTime.value ?? fileTime.value)
  const isFileTime = computed(() => !osmTime.value && !!fileTime.value)
  const sequence = computed(() => status.value.dataSequence ?? sidecar.value?.replicationSequence ?? null)

  const lagSeconds = computed<number | null>(() => {
    const ts = osmTime.value ? Date.parse(osmTime.value) : NaN
    if (!Number.isNaN(ts)) return Math.max(0, Math.round((now.value - ts) / 1000))
    return status.value.lagSeconds ?? null
  })

  const sidecarPhase = computed(() => sidecar.value?.phase ?? null)
  const clipperPhase = computed(() => replication.value?.phase ?? null)
  const sidecarBusy = computed(() => !!sidecarPhase.value && (SIDECAR_BUSY as readonly string[]).includes(sidecarPhase.value))
  const clipperBusy = computed(() => !!clipperPhase.value && (CLIPPER_BUSY as readonly string[]).includes(clipperPhase.value))
  const busy = computed(() => sidecarBusy.value || clipperBusy.value)
  const state = computed(() => (sidecarBusy.value ? sidecarPhase.value : clipperBusy.value ? clipperPhase.value : null))

  const lastResult = computed(() => sidecar.value?.result ?? null)
  const lastRunAt = computed(() => sidecar.value?.lastRunAt ?? replication.value?.lastRunAt ?? null)
  const lastCycleSeconds = computed(() => sidecar.value?.lastCycleSeconds ?? replication.value?.lastRunSeconds ?? null)
  const nextCycleAt = computed(() => sidecar.value?.nextCycleAt ?? replication.value?.nextRunAt ?? null)
  const nextRebuildAt = computed(() => sidecar.value?.nextFullBuildDue ?? null)
  const available = computed(() => status.value.available !== false)
  const lastError = computed(() => {
    if (lastResult.value === 'failed' && sidecar.value?.error) return sidecar.value.error
    if (replication.value?.error) return replication.value.error
    if (!available.value && status.value.message) return status.value.message
    return sidecar.value?.error ?? null
  })
  const failing = computed(() => !available.value || lastResult.value === 'failed' || !!replication.value?.error)

  const health = computed<MirrorHealth>(() => {
    if (failing.value) return 'stale'
    const lag = lagSeconds.value
    if (lag == null || isFileTime.value) return 'unknown'
    if (lag < OK_LAG_SECONDS) return 'ok'
    if (lag < BEHIND_LAG_SECONDS) return 'behind'
    return 'stale'
  })

  function onStatus(s: MirrorStatus) {
    live.value = s
    overlayVersion.value++
  }

  function onSwapped(s: MirrorStatus) {
    live.value = s
    overlayVersion.value++
    const editing = useEditorStore().active || useDraftsStore().reviewing
    pendingReload.value = editing
    return !editing
  }

  function reloadMapData() {
    pendingReload.value = false
    useMapStore().refreshTiles()
  }

  async function updateNow() {
    if (sidecarBusy.value || requesting.value || !updateSupported.value) return
    requesting.value = true
    try {
      const s = await apiRequest<MirrorStatus>('POST', '/api/mirror/update-now')
      if (s && typeof s === 'object' && 'available' in s) live.value = s
      app.toast(t('shell.mirror.updateStarted'))
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 405)) updateSupported.value = false
      else app.toast(errorMessage(e), 'error')
    } finally {
      requesting.value = false
    }
  }

  return {
    status, sidecar, replication, sequence, osmTime, fileTime, dataTime, isFileTime, lagSeconds, state, busy, sidecarBusy,
    lastResult, lastError, lastRunAt, lastCycleSeconds, nextCycleAt, nextRebuildAt, available, health, pendingReload,
    updateSupported, requesting, overlayVersion, onStatus, onSwapped, reloadMapData, updateNow,
  }
})
