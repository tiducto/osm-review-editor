import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useRuleFilesStore } from './ruleFiles'

export type PanelTab = 'map' | 'validate' | 'rules'

const KEY = 'ore.panelTab'

function read(): PanelTab {
  const q = new URLSearchParams(location.search).get('panel')
  if (q === 'map' || q === 'validate' || q === 'rules') return q
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'map' || v === 'validate' || v === 'rules') return v
  } catch {}
  return 'map'
}

export const usePanelStore = defineStore('panel', () => {
  const ruleFiles = useRuleFilesStore()
  const tab = ref<PanelTab>(read())
  const wide = computed(() => tab.value === 'rules' && !!ruleFiles.open)

  watch(tab, (t) => {
    try {
      localStorage.setItem(KEY, t)
    } catch {}
  })

  return { tab, wide }
})
