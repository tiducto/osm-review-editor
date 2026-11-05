<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { usePanelStore, type PanelTab } from '@/stores/panel'
import { useHighlightsStore } from '@/stores/highlights'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import { useScansStore } from '@/stores/scans'
import { useMapStore } from '@/stores/map'
import HighlightsPanel from './HighlightsPanel.vue'
import ValidationPanel from './ValidationPanel.vue'
import RuleFilesPanel from './RuleFilesPanel.vue'
import RuleEditor from './RuleEditor.vue'
import PresetsLibrary from './presets/PresetsLibrary.vue'
import HighlightMapLayers from '@/map/HighlightMapLayers.vue'
import ScanMapLayers from '@/map/ScanMapLayers.vue'
import Icon from './Icon.vue'

const panel = usePanelStore()
const hl = useHighlightsStore()
const ruleFiles = useRuleFilesStore()
const scans = useScansStore()
const mapStore = useMapStore()

const tabs: { id: PanelTab; label: string; icon: 'layers' | 'rule' | 'edit' | 'sparkle' }[] = [
  { id: 'map', label: 'Map', icon: 'layers' },
  { id: 'validate', label: 'Validate', icon: 'rule' },
  { id: 'rules', label: 'Rules', icon: 'edit' },
  { id: 'presets', label: 'Presets', icon: 'sparkle' },
]

function edit(path: string) {
  panel.tab = 'rules'
  void ruleFiles.openFile(path)
}

let restored = false
watch(
  () => mapStore.ready,
  (r) => {
    if (!r || restored) return
    restored = true
    void hl.restoreFromUrl()
  },
  { immediate: true },
)

watch(
  () => panel.tab,
  (t) => {
    scans.active = t === 'validate'
  },
  { immediate: true },
)

onMounted(() => {
  void hl.load()
  void ruleFiles.load()
})
</script>

<template>
  <div class="flex h-full flex-col">
    <HighlightMapLayers />
    <ScanMapLayers />
    <nav class="flex h-10 shrink-0 items-stretch gap-0.5 border-b border-line px-1.5" role="tablist">
      <button
        v-for="t in tabs"
        :key="t.id"
        role="tab"
        class="flex items-center gap-1 border-b-2 px-1.5 text-[12.5px] font-medium transition-colors"
        :class="panel.tab === t.id ? 'border-accent text-fg' : 'border-transparent text-fg-3 hover:text-fg-2'"
        :aria-selected="panel.tab === t.id"
        :data-testid="`tab-${t.id}`"
        @click="panel.tab = t.id"
      >
        <Icon :name="t.icon" :size="13" /> {{ t.label }}
        <span v-if="t.id === 'map' && hl.active.length" class="rounded-full bg-accent px-1.5 text-[10px] text-white">{{ hl.active.length }}</span>
        <span v-if="t.id === 'validate' && scans.report" class="rounded-full bg-surface-3 px-1.5 text-[10px] text-fg-2">{{ scans.report.scan.issueCount }}</span>
      </button>
    </nav>
    <div class="min-h-0 flex-1">
      <div v-if="panel.tab === 'map'" class="scroll-thin h-full overflow-y-auto"><HighlightsPanel @edit="edit" /></div>
      <ValidationPanel v-else-if="panel.tab === 'validate'" />
      <PresetsLibrary v-else-if="panel.tab === 'presets'" />
      <template v-else>
        <RuleEditor v-if="ruleFiles.open" />
        <RuleFilesPanel v-else />
      </template>
    </div>
  </div>
</template>
