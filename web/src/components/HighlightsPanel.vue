<script setup lang="ts">
import { ref } from 'vue'
import type { HighlightFile } from '@/generated/models'
import { useMapStore } from '@/stores/map'
import { useHighlightsStore } from '@/stores/highlights'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import Modal from './Modal.vue'
import MapFeaturesPanel from './MapFeaturesPanel.vue'

const emit = defineEmits<{ edit: [path: string] }>()
const mapStore = useMapStore()
const hl = useHighlightsStore()
const ruleFiles = useRuleFilesStore()

const expanded = ref<string | null>(null)
const saving = ref(false)
const saveName = ref('')
const examples = ['highway=footway !surface', 'footway=sidewalk', 'public_transport=platform !tactile_paving']

function colors(f: HighlightFile): string[] {
  const list = f.legend.length ? f.legend.map((l) => l.color) : f.rules.map((r) => r.color)
  return [...new Set(list.filter((c): c is string => !!c))].slice(0, 6)
}

function origin(f: HighlightFile) {
  return ruleFiles.info(f.path)?.origin ?? null
}

function startSave() {
  saveName.value = hl.quickText.trim()
  saving.value = true
}

async function save() {
  saving.value = false
  const path = await hl.createFromQuick(saveName.value)
  if (path) emit('edit', path)
}
</script>

<template>
  <div>
    <section class="space-y-2 border-b border-line px-3 py-3">
      <div class="section-title">Imagery</div>
      <div class="relative">
        <Icon name="image" :size="14" class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-fg-3" />
        <select
          class="input appearance-none pr-7 pl-7"
          :value="mapStore.imageryId ?? ''"
          @change="mapStore.setImagery(($event.target as HTMLSelectElement).value || null)"
        >
          <option value="">None</option>
          <option v-for="s in mapStore.allImagery" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
        <Icon name="chevron-down" :size="13" class="pointer-events-none absolute top-1/2 right-2 -translate-y-1/2 text-fg-3" />
      </div>
    </section>

    <MapFeaturesPanel />

    <section class="space-y-2 border-b border-line px-3 py-3">
      <div class="flex items-center">
        <span class="section-title">Filter</span>
        <button v-if="hl.quickFile" class="ml-auto text-[11.5px] text-accent hover:underline" data-testid="save-filter" @click="startSave">
          New highlight from filter
        </button>
      </div>
      <div class="relative">
        <Icon name="filter" :size="13" class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-fg-3" />
        <input
          v-model="hl.quickText"
          class="input pl-7 font-mono text-[12px]"
          :class="hl.quickError && 'border-error'"
          placeholder="highway=footway !surface"
          spellcheck="false"
          data-testid="quick-filter"
        />
      </div>
      <p v-if="hl.quickError" class="text-[11.5px] text-error">{{ hl.quickError }}</p>
      <p v-else-if="hl.quickFile" class="text-[11.5px] text-fg-3">Matching objects are drawn in red; the rest is dimmed.</p>
      <div v-else class="flex flex-wrap gap-1">
        <button
          v-for="ex in examples"
          :key="ex"
          class="rounded bg-surface-2 px-1.5 py-0.5 font-mono text-[10.5px] text-fg-3 hover:bg-surface-3 hover:text-fg-2"
          @click="hl.quickText = ex"
        >
          {{ ex }}
        </button>
      </div>
    </section>

    <section class="py-2">
      <div class="flex items-center gap-2 px-3 pb-1.5">
        <span class="section-title">Highlights</span>
        <span class="truncate text-[11.5px] text-fg-3">· {{ hl.scope?.label ?? 'Map view' }}</span>
        <button v-if="hl.active.length" class="ml-auto text-[11.5px] text-fg-3 hover:text-accent" @click="hl.clear()">Clear</button>
      </div>
      <div v-if="!hl.loaded && !hl.loadError" class="flex items-center gap-2 px-3 py-2 text-fg-3"><Spinner :size="12" /> Loading highlights…</div>
      <p v-else-if="hl.loadError" class="px-3 py-2 text-[12px] text-error">{{ hl.loadError }}</p>
      <p v-else-if="!hl.files.length" class="px-3 py-2 text-[12px] text-fg-3">No highlight files. Create one in the Rules tab.</p>
      <ul>
        <li
          v-for="f in hl.files"
          :key="f.path"
          class="border-l-2 transition-colors"
          :class="expanded === f.path ? 'border-accent bg-surface-2' : 'border-transparent hover:bg-surface-2'"
          :data-testid="`highlight-${f.path}`"
        >
          <div class="flex items-center gap-2 py-1.5 pr-2 pl-2.5">
            <button
              class="grid size-4 shrink-0 place-items-center rounded border-2 transition-colors disabled:opacity-40"
              :class="hl.isActive(f.path) ? 'border-accent bg-accent' : 'border-line-strong'"
              :disabled="!f.valid"
              :title="hl.isActive(f.path) ? 'Hide' : 'Show on map'"
              :aria-pressed="hl.isActive(f.path)"
              @click="hl.toggle(f.path)"
            >
              <Icon v-if="hl.isActive(f.path)" name="check" :size="10" :stroke="3.5" class="text-white" />
            </button>
            <button class="min-w-0 flex-1 text-left" @click="expanded = expanded === f.path ? null : f.path">
              <span class="block truncate" :class="hl.isActive(f.path) ? 'text-fg' : 'text-fg-2'" :title="f.title">{{ f.title }}</span>
              <span class="mt-0.5 flex gap-0.5">
                <span v-for="c in colors(f)" :key="c" class="h-1 w-4 rounded-full" :style="{ background: c }" />
              </span>
            </button>
            <span v-if="!f.valid" class="text-error" title="The file has errors"><Icon name="alert" :size="13" /></span>
            <span v-else-if="!f.allCompiled" class="chip shrink-0 bg-surface-3 text-[10px] text-fg-3" title="Some rules are evaluated on the server">server</span>
            <span v-if="origin(f)?.builtIn" class="chip shrink-0 bg-surface-3 text-[10px] text-fg-3">built-in</span>
          </div>
          <div v-if="expanded === f.path" class="space-y-2 pr-3 pb-3 pl-9">
            <p class="text-[12px] text-fg-2">{{ f.description || 'No description.' }}</p>
            <ul v-if="f.legend.length" class="space-y-0.5 text-[12px]">
              <li v-for="l in f.legend" :key="l.rule" class="flex items-center gap-2">
                <span class="h-1.5 w-3.5 rounded-full" :style="{ background: l.color ?? '#999' }" />{{ l.label }}
              </li>
            </ul>
            <div class="flex flex-wrap items-center gap-1.5 text-[11px]">
              <span class="chip bg-surface-3 font-mono text-fg-3">{{ f.path }}</span>
              <span v-if="f.dimOthers" class="chip bg-surface-3 text-fg-3">dims others</span>
              <span v-if="f.stats" class="chip bg-surface-3 text-fg-3">stats: {{ f.stats }}</span>
              <span class="chip bg-surface-3 text-fg-3">{{ f.rules.filter((r) => r.compiled).length }}/{{ f.rules.length }} rules on tiles</span>
            </div>
            <p v-for="(d, i) in f.diagnostics.filter((x) => x.severity === 'error')" :key="i" class="text-[11.5px] text-error">
              {{ d.line }}:{{ d.column }} {{ d.message }}
            </p>
            <div class="flex gap-1.5">
              <button class="btn btn-sm" @click="hl.only(f.path)">Only this</button>
              <button class="btn btn-sm" :data-testid="`edit-${f.path}`" @click="emit('edit', f.path)"><Icon name="edit" :size="12" /> Edit</button>
            </div>
          </div>
        </li>
      </ul>
    </section>

    <Modal v-if="saving" title="New highlight from filter" width="420px" @close="saving = false">
      <form class="space-y-2 px-5 py-4" @submit.prevent="save">
        <label class="block text-[12px] text-fg-2">Title</label>
        <input v-model="saveName" class="input" autofocus data-testid="filter-name" />
        <p class="font-mono text-[11px] text-fg-3">{{ hl.quickText }}</p>
      </form>
      <template #footer>
        <button class="btn ml-auto" @click="saving = false">Cancel</button>
        <button class="btn btn-primary" :disabled="!saveName.trim()" data-testid="create-from-filter" @click="save">Create</button>
      </template>
    </Modal>
  </div>
</template>
