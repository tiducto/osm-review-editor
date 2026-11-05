import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { AccountStatus, AppStatus, Area, ImagerySource, MirrorStatus, OsmTarget } from '@/generated/models'
import { api, errorMessage } from '@/api/client'
import type { ConnectionState } from '@/api/events'

export type ThemePref = 'system' | 'light' | 'dark'
export type ToastKind = 'info' | 'success' | 'error'

export interface Toast {
  id: number
  kind: ToastKind
  text: string
}

const THEME_KEY = 'ore.theme'

function readTheme(): ThemePref {
  try {
    const t = localStorage.getItem(THEME_KEY)
    return t === 'light' || t === 'dark' ? t : 'system'
  } catch {
    return 'system'
  }
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

  const themePref = ref<ThemePref>(readTheme())
  const media = matchMedia('(prefers-color-scheme: dark)')
  const systemDark = ref(media.matches)
  media.addEventListener('change', (e) => (systemDark.value = e.matches))
  const isDark = computed(() => (themePref.value === 'system' ? systemDark.value : themePref.value === 'dark'))
  watch(
    isDark,
    (d) => document.documentElement.classList.toggle('dark', d),
    { immediate: true },
  )
  function setTheme(t: ThemePref) {
    themePref.value = t
    try {
      if (t === 'system') localStorage.removeItem(THEME_KEY)
      else localStorage.setItem(THEME_KEY, t)
    } catch {}
  }
  function cycleTheme() {
    setTheme(isDark.value ? 'light' : 'dark')
  }

  const toasts = ref<Toast[]>([])
  let toastSeq = 0
  function toast(text: string, kind: ToastKind = 'info', ms = kind === 'error' ? 7000 : 3500) {
    const id = ++toastSeq
    toasts.value.push({ id, kind, text })
    setTimeout(() => dismiss(id), ms)
  }
  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
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
      fail(e, 'Account')
    }
  }

  async function init() {
    await loadStatus()
    const [img, ar] = await Promise.allSettled([api.imagery(), api.areas()])
    if (img.status === 'fulfilled') imagery.value = img.value
    if (ar.status === 'fulfilled') areas.value = ar.value
  }

  async function setTarget(t: OsmTarget, confirm = false) {
    try {
      account.value = await api.setTarget({ target: t, confirm })
      toast(`API target switched to ${t === 'prod' ? 'production' : 'development'}`, 'success')
      await loadStatus()
    } catch (e) {
      fail(e, 'Switching target')
    }
  }

  async function logout() {
    try {
      account.value = await api.logout()
      toast('OSM account disconnected')
    } catch (e) {
      fail(e, 'Disconnect')
    }
  }

  function setMirror(m: MirrorStatus) {
    if (status.value) status.value = { ...status.value, mirror: m }
  }

  return {
    status, account, imagery, areas, serverError, connection, settingsOpen, mergeDraftId,
    themePref, isDark, setTheme, cycleTheme,
    toasts, toast, dismiss, fail,
    target, mirror,
    init, loadStatus, loadAccount, setTarget, logout, setMirror,
  }
})
