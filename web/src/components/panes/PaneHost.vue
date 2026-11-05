<script setup lang="ts">
import type { PaneName } from '@/stores/panel'
import { usePanelStore } from '@/stores/panel'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import HighlightsPanel from '../HighlightsPanel.vue'
import ValidationPanel from '../ValidationPanel.vue'
import RuleFilesPanel from '../RuleFilesPanel.vue'
import RuleEditor from '../RuleEditor.vue'
import PresetsLibrary from '../presets/PresetsLibrary.vue'
import SettingsPane from '../SettingsPane.vue'
import BackgroundPane from './BackgroundPane.vue'
import DraftsPane from './DraftsPane.vue'
import Icon from '../Icon.vue'
import { useI18n } from '@/i18n'

withDefaults(defineProps<{ name: PaneName; header?: boolean }>(), { header: true })
const { t } = useI18n()
const panel = usePanelStore()
const ruleFiles = useRuleFilesStore()

function editRule(path: string) {
  panel.open('rules')
  void ruleFiles.openFile(path)
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col bg-surface-2">
    <div v-if="header" class="panel-header h-12 pr-0 pl-4">
      <h2 class="min-w-0 flex-1 truncate text-[16px] leading-5 font-bold text-fg">{{ t(`shell.pane.${name}`) }}</h2>
      <button class="grid h-full w-12 shrink-0 place-items-center text-fg hover:bg-surface-3" :title="t('shell.pane.close')" data-testid="pane-close" @click="panel.close()">
        <Icon name="x" :size="16" />
      </button>
    </div>
    <div class="min-h-0 flex-1">
      <BackgroundPane v-if="name === 'background'" />
      <div v-else-if="name === 'map-data'" class="scroll-thin h-full overflow-y-auto"><HighlightsPanel @edit="editRule" /></div>
      <ValidationPanel v-else-if="name === 'validation'" />
      <template v-else-if="name === 'rules'">
        <RuleEditor v-if="ruleFiles.open" />
        <RuleFilesPanel v-else />
      </template>
      <PresetsLibrary v-else-if="name === 'presets'" />
      <DraftsPane v-else-if="name === 'drafts'" />
      <SettingsPane v-else-if="name === 'settings'" />
    </div>
  </div>
</template>
