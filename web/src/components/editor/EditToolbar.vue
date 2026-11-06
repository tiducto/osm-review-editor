<script setup lang="ts">
import { computed } from 'vue'
import { useEditorStore } from '@/stores/editor'
import { useMapStore } from '@/stores/map'
import { useEditIssuesStore } from '@/stores/editIssues'
import { useI18n } from '@/i18n'
import Icon from '../Icon.vue'
import Spinner from '../Spinner.vue'

const editor = useEditorStore()
const mapStore = useMapStore()
const issues = useEditIssuesStore()
const { t, tn } = useI18n()

const issueTone = computed(() => (issues.errors.length ? 'text-error' : issues.warnings.length ? 'text-warning' : 'text-fg-3'))

const tools = [
  { mode: 'add-point', label: 'toolbar.point', key: '1', path: 'M12 21s6-5.4 6-10.5a6 6 0 1 0-12 0C6 15.6 12 21 12 21zM12 12.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z' },
  { mode: 'add-line', label: 'toolbar.line', key: '2', path: 'M4 19 10 9l5 6 5-11M4 19a1.5 1.5 0 1 0 0 .01M20 4a1.5 1.5 0 1 0 0 .01' },
  { mode: 'add-area', label: 'toolbar.area', key: '3', path: 'M5 6l12-2 3 10-9 6-6-5z' },
] as const

const drawingKind = computed(() => {
  if (editor.mode === 'draw') return t('toolbar.hint.draw')
  if (editor.mode === 'add-point') return t('toolbar.hint.addPoint')
  if (editor.mode === 'add-line') return t('toolbar.hint.addLine')
  if (editor.mode === 'add-area') return t('toolbar.hint.addArea')
  if (editor.mode === 'move') return t('toolbar.hint.move')
  if (editor.mode === 'rotate') return t('toolbar.hint.rotate')
  return null
})

const issuesTitle = computed(() =>
  t('toolbar.issues', { errors: tn('toolbar.errors', issues.errors.length), warnings: tn('toolbar.warnings', issues.warnings.length) }),
)

function isActive(mode: string) {
  return editor.mode === mode
}
</script>

<template>
  <div class="pointer-events-auto flex flex-col items-center gap-1.5" data-testid="edit-toolbar">
    <div v-if="!editor.active" class="flex items-center gap-2 rounded-lg border border-line bg-surface/95 p-1 shadow-panel backdrop-blur">
      <button
        class="btn btn-primary btn-sm"
        :disabled="editor.starting || mapStore.zoom < 15"
        :title="mapStore.zoom < 15 ? t('toolbar.zoomToEdit') : t('toolbar.enterTitle')"
        data-testid="edit-enter"
        @click="editor.enter()"
      >
        <Spinner v-if="editor.starting" :size="12" /><Icon v-else name="edit" :size="13" /> {{ t('toolbar.enter') }}
      </button>
    </div>
    <div v-else class="flex items-center gap-1 rounded-lg border border-accent/50 bg-surface/95 p-1 shadow-pop backdrop-blur">
      <button
        v-for="tool in tools"
        :key="tool.mode"
        class="btn btn-sm"
        :class="isActive(tool.mode) ? 'btn-primary active' : 'btn-ghost'"
        :aria-pressed="isActive(tool.mode)"
        :title="`${t(tool.label)} (${tool.key})`"
        :data-testid="`edit-${tool.mode}`"
        @click="editor.setMode(tool.mode)"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="tool.path" /></svg>
        <span class="hidden lg:inline">{{ t(tool.label) }}</span>
      </button>
      <div class="mx-1 h-5 w-px bg-line" />
      <button
        class="btn btn-ghost btn-icon btn-sm"
        :disabled="!editor.undoLabel"
        :title="editor.undoLabel ? t('toolbar.undo', { label: editor.undoLabel }) : t('toolbar.nothingToUndo')"
        data-testid="edit-undo"
        @click="editor.undo()"
      >
        <Icon name="undo" :size="14" />
      </button>
      <button
        class="btn btn-ghost btn-icon btn-sm"
        :disabled="!editor.redoLabel"
        :title="editor.redoLabel ? t('toolbar.redo', { label: editor.redoLabel }) : t('toolbar.nothingToRedo')"
        data-testid="edit-redo"
        @click="editor.redo()"
      >
        <Icon name="undo" :size="14" class="-scale-x-100" />
      </button>
      <button
        class="btn btn-sm"
        :class="issues.panelOpen ? 'btn-primary' : 'btn-ghost'"
        :title="issuesTitle"
        data-testid="edit-issues-toggle"
        @click="issues.panelOpen = !issues.panelOpen"
      >
        <Icon name="alert" :size="13" :class="issues.panelOpen ? '' : issueTone" />
        <span class="font-mono tabular-nums">{{ issues.errors.length + issues.warnings.length }}</span>
      </button>
      <span class="chip bg-surface-3 font-mono text-fg-2 tabular-nums" :title="tn('toolbar.editCount', editor.editCount)" data-testid="edit-count">
        {{ editor.editCount }}
      </span>
      <button class="btn btn-primary btn-sm" :disabled="editor.saving || (!editor.editCount && !editor.draftId)" :title="t('toolbar.saveTitle')" data-testid="edit-save" @click="editor.requestSave()">
        <Spinner v-if="editor.saving" :size="12" /><Icon v-else name="upload" :size="13" />
        {{ editor.draftId ? t('toolbar.saveDraft') : t('common.save') }}
      </button>
      <button class="btn btn-ghost btn-sm" :title="t('toolbar.exit')" :aria-label="t('toolbar.exit')" data-testid="edit-exit" @click="editor.exit()"><Icon name="x" :size="13" /></button>
    </div>
    <div
      v-if="editor.active && (drawingKind || editor.draftTitle || editor.loadingTiles || !editor.zoomOk)"
      class="flex max-w-[min(560px,calc(100vw-24px))] flex-wrap items-center justify-center gap-x-3 gap-y-1 rounded-full border border-line bg-surface/95 px-3 py-1 text-[12px] text-fg-2 shadow-panel backdrop-blur"
    >
      <span v-if="editor.draftTitle" class="font-medium text-fg">{{ t('toolbar.editing', { title: editor.draftTitle }) }}</span>
      <span v-if="drawingKind">{{ drawingKind }}</span>
      <span v-if="editor.loadingTiles" class="flex items-center gap-1.5"><Spinner :size="11" /> {{ t('toolbar.loading') }}</span>
      <span v-if="!editor.zoomOk">{{ t('toolbar.zoomForData') }}</span>
    </div>
  </div>
</template>
