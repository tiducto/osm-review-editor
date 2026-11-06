<script setup lang="ts">
import { useI18n } from '@/i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useEditorStore, type NeedsPreset } from '@/stores/editor'
import { useEditPresetsStore } from '@/stores/presetEdit'
import { usePresetsStore } from '@/stores/presets'
import type { EntitySnapshot } from '@/editor/types'
import type { SmartPreset } from '@/generated/models'
import PresetEditor, { type EditableEntity } from '../presets/PresetEditor.vue'
import SmartPresetPanel from '../presets/SmartPresetPanel.vue'
import Icon from '../Icon.vue'
import EditIssues from './EditIssues.vue'
import { useEditIssuesStore } from '@/stores/editIssues'

const editor = useEditorStore()
const presetEdit = useEditPresetsStore()
const presets = usePresetsStore()
const t = presets.t
const { t: tt, tn } = useI18n()
const editIssues = useEditIssuesStore()
const picking = ref(false)

const entities = computed<EditableEntity[]>(() =>
  editor.selection.map((s: EntitySnapshot) => ({ id: s.id, tags: s.tags, geometry: s.geometry, baseTags: s.baseTags, title: s.displayName })),
)
const ids = computed(() => editor.selection.map((s) => s.id))
const single = computed<EntitySnapshot | null>(() => (editor.selection.length === 1 ? editor.selection[0] : null))
const smart = computed<SmartPreset[]>(() => presetEdit.smartFor(editor.selection))
const drawName = computed(() => {
  const d = presetEdit.drawPreset
  return d ? (presets.catalog?.preset(d.id)?.name ?? d.id) : null
})

const shortcuts = computed<[string, string][]>(() => [
  ['1 2 3', tt('shell.edit.key.draw')],
  ['X D C V', tt('shell.edit.key.split')],
  ['Q O S', tt('shell.edit.key.shape')],
  ['M R A', tt('shell.edit.key.move')],
  ['Del', tt('shell.edit.key.delete')],
  ['⌘Z ⇧⌘Z', tt('shell.edit.key.undo')],
  ['Alt', tt('shell.edit.key.snap')],
])

function kindLabel(kind: string): string {
  return kind === 'node' ? tt('shell.edit.kind.node') : kind === 'way' ? tt('shell.edit.kind.way') : tt('shell.edit.kind.relation')
}

function geomLabel(g: string): string {
  const key = `shell.edit.geom.${g}` as const
  return key === 'shell.edit.geom.point' || key === 'shell.edit.geom.vertex' || key === 'shell.edit.geom.line' || key === 'shell.edit.geom.area' || key === 'shell.edit.geom.relation' ? tt(key) : g
}

function shortRef(s: EntitySnapshot): string {
  return `${s.id[0]}${s.osmId}`
}

const prompted = ref<string | null>(null)

function prompt(req: NeedsPreset | null) {
  if (!req || !single.value || single.value.id !== req.entityId) return
  prompted.value = req.entityId
  picking.value = true
}

let unsubscribe: (() => void) | null = null
onMounted(() => {
  unsubscribe = editor.onNeedsPreset((req) => queueMicrotask(() => prompt(req)))
  prompt(editor.needsPreset)
})
onBeforeUnmount(() => unsubscribe?.())

watch(
  () => single.value?.id ?? null,
  (id) => {
    if (prompted.value && id !== prompted.value) {
      prompted.value = null
      picking.value = false
    }
    if (id && editor.needsPreset?.entityId === id && !picking.value) prompt(editor.needsPreset)
  },
)

watch(picking, (open) => {
  if (open || !prompted.value) return
  prompted.value = null
  editor.clearNeedsPreset()
})

function onPreset(id: string) {
  void presetEdit.changePreset(ids.value, id)
  if (prompted.value) {
    prompted.value = null
    editor.clearNeedsPreset()
  }
}

function onSmart(p: SmartPreset) {
  if (single.value) presetEdit.openSmart(p, single.value.id)
}
</script>

<template>
  <div class="flex h-full flex-col" data-testid="edit-inspector">
    <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line px-3">
      <h2 class="text-[13px] font-semibold">{{ t('inspector.edit') }}</h2>
      <span class="chip bg-accent-soft text-accent">iD {{ editor.version }}</span>
      <span v-if="editor.selection.length > 1" class="chip bg-surface-3 text-fg-2">{{ t('inspector.selected', { n: editor.selection.length }) }}</span>
      <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" :title="tt('shell.edit.deselect')" @click="editor.select([])"><Icon name="x" :size="14" /></button>
    </div>

    <div v-if="drawName" class="flex items-center gap-2 border-b border-accent/40 bg-accent-soft px-4 py-2 text-[12px]" data-testid="draw-preset">
      <Icon name="edit" :size="13" class="text-accent" />
      <span class="flex-1">{{ t('draw.hint', { name: drawName }) }}</span>
      <button class="btn btn-ghost btn-sm" @click="presetEdit.cancelDraw(); editor.select([])">{{ t('draw.cancel') }}</button>
    </div>

    <SmartPresetPanel />

    <PresetEditor
      v-if="entities.length"
      v-model:picking="picking"
      :entities="entities"
      :smart="smart"
      :readonly="presetEdit.busy"
      @patch="presetEdit.patch(ids, $event)"
      @tags="single && editor.setTags(single.id, $event)"
      @preset="onPreset"
      @smart="onSmart"
    >
      <template #meta>
        <div v-if="single" class="flex flex-wrap items-center gap-x-2 font-mono text-[11.5px] text-fg-3">
          <span>{{ shortRef(single) }}</span>
          <span v-if="single.isNew" class="font-sans text-added">{{ tt('shell.edit.new') }}</span>
          <span v-else-if="single.version">v{{ single.version }}</span>
          <span class="font-sans">· {{ kindLabel(single.kind) }}, {{ geomLabel(single.geometry) }}</span>
          <span v-if="single.nodeCount != null" class="font-sans">· {{ tn('shell.edit.nodes', single.nodeCount) }}</span>
        </div>
        <div v-else class="flex flex-wrap gap-1 pt-0.5">
          <button v-for="s in editor.selection.slice(0, 8)" :key="s.id" class="chip bg-surface-3 font-mono text-fg-2 hover:text-fg" @click="editor.select([s.id])">{{ shortRef(s) }}</button>
          <span v-if="editor.selection.length > 8" class="text-[11px] text-fg-3">+{{ editor.selection.length - 8 }}</span>
        </div>
      </template>
    </PresetEditor>

    <div v-else class="flex min-h-0 flex-1 flex-col justify-center gap-4 overflow-y-auto px-6 text-fg-3">
      <p class="text-center">{{ t('inspector.select') }}</p>
      <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11.5px]">
        <template v-for="[k, label] in shortcuts" :key="k">
          <dt><span class="kbd">{{ k }}</span></dt>
          <dd>{{ label }}</dd>
        </template>
      </dl>
    </div>

    <EditIssues v-if="editIssues.panelOpen" />
  </div>
</template>
