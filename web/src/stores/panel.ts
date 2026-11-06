import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useRuleFilesStore } from './ruleFiles'

export type PanelTab = 'map' | 'validate' | 'rules' | 'presets'

const KEY = 'ore.panelTab'

function read(): PanelTab {
  const q = new URLSearchParams(location.search).get('panel')
  if (q === 'map' || q === 'validate' || q === 'rules' || q === 'presets') return q
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'map' || v === 'validate' || v === 'rules' || v === 'presets') return v
  } catch {}
  return 'map'
}

export const usePanelStore = defineStore('panel', () => {
  const ruleFiles = useRuleFilesStore()
  const tab = ref<PanelTab>(read())
  const wide = computed(() => tab.value === 'rules' && !!ruleFiles.open)
  const open = ref(window.innerWidth >= 768)

  watch(tab, (t) => {
    try {
      localStorage.setItem(KEY, t)
    } catch {}
  })

  return { tab, wide, open }
})
