import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AccountStatus, AppStatus, Area, ImagerySource, MirrorStatus, OsmTarget } from '@/generated/models'
import { api, errorMessage } from '@/api/client'
import type { ConnectionState } from '@/api/events'
import { t } from '@/i18n'

export type ToastKind = 'info' | 'success' | 'error'

export interface ToastAction {
  label: string
  run: () => void
}

export interface Toast {
  id: number
  kind: ToastKind
  text: string
  action?: ToastAction
  testId?: string
}

export const useAppStore = defineStore('app', () => {
  const status = ref<AppStatus | null>(null)
  const account = ref<AccountStatus | null>(null)
  const imagery = ref<ImagerySource[]>([])
  const areas = ref<Area[]>([])
  const serverError = ref<string | null>(null)
  const connection = ref<ConnectionState>('connecting')

  const settingsOpen = ref(false)
  const mergeDraftId = ref<string | null>(null)

  const toasts = ref<Toast[]>([])
  let toastSeq = 0
  function toast(text: string, kind: ToastKind = 'info', ms = kind === 'error' ? 7000 : 3500, extra: { action?: ToastAction; testId?: string } = {}) {
    const id = ++toastSeq
    toasts.value.push({ id, kind, text, ...extra })
    setTimeout(() => dismiss(id), ms)
    return id
  }
  function dismiss(id: number) {
    toasts.value = toasts.value.filter((x) => x.id !== id)
  }
  function fail(e: unknown, what?: string) {
    const msg = errorMessage(e)
    toast(what ? `${what}: ${msg}` : msg, 'error')
  }

  const target = computed<OsmTarget>(() => account.value?.target ?? status.value?.account.target ?? 'dev')
  const mirror = computed<MirrorStatus | null>(() => status.value?.mirror ?? null)

  async function loadStatus() {
    try {
      status.value = await api.status()
      account.value = status.value.account
      serverError.value = null
    } catch (e) {
      serverError.value = errorMessage(e)
    }
  }

  async function loadAccount() {
    try {
      account.value = await api.account()
    } catch (e) {
      fail(e, t('app.account'))
    }
  }

  async function init() {
    await loadStatus()
    const [img, ar] = await Promise.allSettled([api.imagery(), api.areas()])
    if (img.status === 'fulfilled') imagery.value = img.value
    if (ar.status === 'fulfilled') areas.value = ar.value
  }

  async function setTarget(target: OsmTarget, confirm = false) {
    try {
      account.value = await api.setTarget({ target, confirm })
      toast(target === 'prod' ? t('app.targetProd') : t('app.targetDev'), 'success')
      await loadStatus()
    } catch (e) {
      fail(e, t('app.switchingTarget'))
    }
  }

  async function logout() {
    try {
      account.value = await api.logout()
      toast(t('app.disconnected'))
    } catch (e) {
      fail(e, t('app.disconnect'))
    }
  }

  function setMirror(m: MirrorStatus) {
    if (status.value) status.value = { ...status.value, mirror: m }
  }

  return {
    status, account, imagery, areas, serverError, connection, settingsOpen, mergeDraftId,
    toasts, toast, dismiss, fail,
    target, mirror,
    init, loadStatus, loadAccount, setTarget, logout, setMirror,
  }
})
