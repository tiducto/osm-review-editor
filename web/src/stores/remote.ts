import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { RemoteRequest } from '@/generated/models'
import { api, ApiError } from '@/api/client'
import { useAppStore } from './app'
import { t } from '@/i18n'

export const useRemoteStore = defineStore('remote', () => {
  const app = useAppStore()
  const pending = ref<RemoteRequest[]>([])
  const busy = ref(false)
  const current = computed(() => pending.value[0] ?? null)

  function add(r: RemoteRequest) {
    if (!pending.value.some((x) => x.id === r.id)) pending.value = [...pending.value, r]
    window.focus()
  }

  function remove(id: string) {
    pending.value = pending.value.filter((x) => x.id !== id)
  }

  async function load() {
    try {
      const list = await api.remotePending()
      pending.value = list
    } catch {}
  }

  async function decide(accept: boolean) {
    const r = current.value
    if (!r || busy.value) return
    busy.value = true
    try {
      await api.remoteDecide(r.id, { accept })
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) app.toast(t('remote.expired'), 'info')
      else app.fail(e)
    } finally {
      remove(r.id)
      busy.value = false
    }
  }

  return { pending, current, busy, add, remove, load, decide }
})
