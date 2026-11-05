import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useRuleFilesStore } from './ruleFiles'

export const PANES = ['background', 'map-data', 'validation', 'rules', 'presets', 'drafts', 'settings'] as const
export type PaneName = (typeof PANES)[number]

const LEGACY: Record<string, PaneName> = { map: 'map-data', validate: 'validation', rules: 'rules', presets: 'presets' }

function isPane(v: string | null): v is PaneName {
  return !!v && (PANES as readonly string[]).includes(v)
}

function fromUrl(): PaneName | null {
  const q = new URLSearchParams(location.search)
  const pane = q.get('pane')
  if (isPane(pane)) return pane
  const legacy = q.get('panel')
  return legacy ? (LEGACY[legacy] ?? null) : null
}

export const usePanelStore = defineStore('panel', () => {
  const ruleFiles = useRuleFilesStore()
  const pane = ref<PaneName | null>(fromUrl())
  const last = ref<PaneName>(pane.value ?? 'map-data')
  const wide = computed(() => pane.value === 'rules' && !!ruleFiles.open)

  function open(name: PaneName) {
    pane.value = name
    last.value = name
  }

  function close() {
    pane.value = null
  }

  function toggle(name?: PaneName) {
    const target = name ?? last.value
    if (pane.value === target || (!name && pane.value)) close()
    else open(target)
  }

  return { pane, last, wide, open, close, toggle }
})
