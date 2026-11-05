<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useEditorStore } from '@/stores/editor'
import type { EntitySnapshot, Tags } from '@/editor/types'
import { iconMarkup } from '@/map/osm/icons'
import TagEditor from '../TagEditor.vue'
import Icon from '../Icon.vue'

const editor = useEditorStore()

const single = computed<EntitySnapshot | null>(() => (editor.selection.length === 1 ? editor.selection[0] : null))
const iconSvg = ref<string | null>(null)

watch(
  () => single.value?.preset.icon ?? null,
  async (icon) => {
    iconSvg.value = null
    if (!icon) return
    const svg = await iconMarkup(icon)
    if (single.value?.preset.icon === icon) iconSvg.value = svg
  },
  { immediate: true },
)

const tags = computed<Tags>({
  get: () => single.value?.tags ?? {},
  set: (t) => {
    if (single.value) editor.setTags(single.value.id, t)
  },
})

const shortcuts: [string, string][] = [
  ['1 2 3', 'Draw a point, line, area'],
  ['X D C V', 'Split, disconnect, combine, reverse'],
  ['Q O S', 'Square, circularize, straighten'],
  ['M R A', 'Move, rotate, continue line'],
  ['Del', 'Delete'],
  ['⌘Z ⇧⌘Z', 'Undo, redo'],
  ['Alt', 'Drag or draw without snapping'],
]

const kindLabel: Record<string, string> ={ node: 'Node', way: 'Way', relation: 'Relation' }

function title(s: EntitySnapshot): string {
  return s.displayName ?? s.preset.name
}

function shortRef(s: EntitySnapshot): string {
  return `${s.id[0]}${s.osmId}`
}
</script>

<template>
  <div class="flex h-full flex-col" data-testid="edit-inspector">
    <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line px-3">
      <h2 class="text-[13px] font-semibold">Edit</h2>
      <span class="chip bg-accent-soft text-accent">iD {{ editor.version }}</span>
      <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" title="Deselect (Esc)" @click="editor.select([])"><Icon name="x" :size="14" /></button>
    </div>

    <div v-if="single" class="scroll-thin min-h-0 flex-1 overflow-y-auto">
      <div class="space-y-1 border-b border-line px-4 py-3">
        <div class="flex items-center gap-2.5">
          <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-3 p-1.5 text-fg-2" data-testid="edit-preset-icon">
            <span v-if="iconSvg" class="block size-6" v-html="iconSvg" />
            <Icon v-else name="dot" :size="16" />
          </span>
          <div class="min-w-0">
            <div class="truncate text-[12px] text-fg-2" data-testid="edit-preset-name">{{ single.preset.name }}</div>
            <h3 class="truncate text-[15px] leading-snug font-semibold">{{ title(single) }}</h3>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-x-2 font-mono text-[11.5px] text-fg-3">
          <span>{{ shortRef(single) }}</span>
          <span v-if="single.isNew" class="font-sans text-added">new</span>
          <span v-else-if="single.version">v{{ single.version }}</span>
          <span class="font-sans">· {{ kindLabel[single.kind] }}, {{ single.geometry }}</span>
          <span v-if="single.nodeCount != null" class="font-sans">· {{ single.nodeCount }} nodes</span>
          <span v-if="single.memberCount != null" class="font-sans">· {{ single.memberCount }} members</span>
        </div>
      </div>

      <section v-if="single.preset.fields.length" class="border-b border-line px-4 py-2.5" data-testid="edit-preset-fields">
        <slot name="fields" :entity="single" :fields="single.preset.fields">
          <div class="text-[11.5px] text-fg-3">
            Preset fields: <span class="font-mono">{{ single.preset.fields.join(', ') }}</span>
          </div>
        </slot>
      </section>

      <section class="px-4 py-3">
        <TagEditor v-model="tags" :base="single.baseTags" />
      </section>
    </div>

    <div v-else-if="editor.selection.length" class="scroll-thin min-h-0 flex-1 overflow-y-auto px-4 py-3">
      <div class="section-title mb-2">{{ editor.selection.length }} selected</div>
      <ul class="space-y-1">
        <li v-for="s in editor.selection" :key="s.id">
          <button class="flex w-full items-center gap-2 rounded-md px-2 py-1 text-left hover:bg-surface-3" @click="editor.select([s.id])">
            <span class="min-w-0 flex-1 truncate">{{ title(s) }}</span>
            <span class="font-mono text-[11px] text-fg-3">{{ shortRef(s) }}</span>
          </button>
        </li>
      </ul>
    </div>

    <div v-else class="flex flex-1 flex-col justify-center gap-4 px-6 text-fg-3">
      <p class="text-center">Select a feature to edit its tags. Right-click for operations.</p>
      <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11.5px]">
        <template v-for="[k, label] in shortcuts" :key="k">
          <dt><span class="kbd">{{ k }}</span></dt>
          <dd>{{ label }}</dd>
        </template>
      </dl>
    </div>
  </div>
</template>
