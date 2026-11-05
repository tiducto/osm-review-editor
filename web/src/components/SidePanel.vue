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
import { useI18n, type MessageKey } from '@/i18n'

const { t } = useI18n()
const panel = usePanelStore()
const hl = useHighlightsStore()
const ruleFiles = useRuleFilesStore()
const scans = useScansStore()
const mapStore = useMapStore()

const tabs: { id: PanelTab; label: MessageKey; icon: 'layers' | 'rule' | 'edit' | 'sparkle' }[] = [
  { id: 'map', label: 'panel.map', icon: 'layers' },
  { id: 'validate', label: 'panel.validate', icon: 'rule' },
  { id: 'rules', label: 'panel.rules', icon: 'edit' },
  { id: 'presets', label: 'panel.presets', icon: 'sparkle' },
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
  (v) => {
    scans.active = v === 'validate'
  },
  { immediate: true },
)

onMounted(() => {
  void hl.load()
  void ruleFiles.load()
})
</script>

<template>
  <div class="flex h-full flex-col bg-surface-2">
    <HighlightMapLayers />
    <ScanMapLayers />
    <nav class="flex shrink-0 items-center border-b border-line-strong bg-surface p-2" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        role="tab"
        class="-ml-px flex h-8 min-w-0 flex-auto items-center justify-center gap-1 border border-line-strong px-1 text-[11.5px] font-bold transition-colors first:ml-0 first:rounded-l-md last:rounded-r-md"
        :class="panel.tab === tab.id ? 'relative z-10 border-accent bg-accent text-white' : 'bg-surface text-fg hover:bg-surface-3'"
        :aria-selected="panel.tab === tab.id"
        :data-testid="`tab-${tab.id}`"
        @click="panel.tab = tab.id"
      >
        <Icon :name="tab.icon" :size="13" class="hidden shrink-0" /> <span class="truncate">{{ t(tab.label) }}</span>
        <span
          v-if="tab.id === 'map' && hl.active.length"
          class="rounded-sm px-1 text-[10px] leading-4"
          :class="panel.tab === tab.id ? 'bg-white text-accent' : 'bg-accent text-white'"
        >{{ hl.active.length }}</span>
        <span
          v-if="tab.id === 'validate' && scans.report"
          class="rounded-sm px-1 text-[10px] leading-4"
          :class="panel.tab === tab.id ? 'bg-white/90 text-fg' : 'bg-surface-3 text-fg-2'"
        >{{ scans.report.scan.issueCount }}</span>
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
