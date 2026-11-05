<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SmartPreset } from '@/generated/models'
import { usePresetsStore } from '@/stores/presets'
import type { FieldDef, FieldTags, Geometry, PresetDef, TagPatch, Tags } from '@/presets/types'
import TagEditor from '../TagEditor.vue'
import Icon from '../Icon.vue'
import PresetIcon from './PresetIcon.vue'
import PresetPicker from './PresetPicker.vue'
import PresetFieldList from './PresetFieldList.vue'

export interface EditableEntity {
  id: string
  tags: Tags
  geometry: Geometry
  baseTags?: Tags | null
  title?: string | null
}

const props = withDefaults(defineProps<{ entities: EditableEntity[]; smart?: SmartPreset[]; readonly?: boolean; picking?: boolean }>(), {
  smart: () => [],
  readonly: false,
  picking: false,
})
const emit = defineEmits<{ patch: [patch: TagPatch]; tags: [tags: Tags]; preset: [id: string]; smart: [preset: SmartPreset]; 'update:picking': [v: boolean] }>()

const presets = usePresetsStore()
const t = presets.t
void presets.ensure().catch(() => {})

const pickerOpen = ref(props.picking)
watch(
  () => props.picking,
  (v) => (pickerOpen.value = v),
)
watch(pickerOpen, (v) => emit('update:picking', v))
watch(
  () => props.entities.map((e) => e.id).join(','),
  () => (pickerOpen.value = props.picking),
)

const matched = computed<PresetDef[]>(() => {
  const cat = presets.catalog
  if (!cat) return []
  return props.entities.map((e) => cat.match(e.tags, e.geometry))
})

const preset = computed<PresetDef | null>(() => {
  const m = matched.value
  if (!m.length) return null
  return m.every((p) => p.id === m[0].id) ? m[0] : null
})

const geometries = computed<Geometry[]>(() => [...new Set(props.entities.map((e) => e.geometry))])

const fieldSets = computed<{ fields: FieldDef[]; more: FieldDef[] }>(() => {
  const cat = presets.catalog
  if (!cat || !matched.value.length) return { fields: [], more: [] }
  const lists = matched.value.map((p) => cat.fieldsOf(p))
  const shared = lists[0].filter((f) => lists.every((l) => l.some((x) => x.id === f.id)))
  const moreById = new Map<string, FieldDef>()
  for (const p of matched.value) for (const f of cat.moreFieldsOf(p)) moreById.set(f.id, f)
  for (const l of lists) for (const f of l) if (!shared.some((x) => x.id === f.id)) moreById.set(f.id, f)
  for (const f of cat.universalFields()) moreById.set(f.id, f)
  for (const f of shared) moreById.delete(f.id)
  const more = [...moreById.values()].sort((a, b) => a.label.localeCompare(b.label, presets.locale))
  return { fields: shared, more }
})

const fieldTags = computed<FieldTags>(() => {
  const out: FieldTags = {}
  const ents = props.entities
  const keys = new Set(ents.flatMap((e) => Object.keys(e.tags)))
  for (const k of keys) {
    const values = ents.map((e) => e.tags[k])
    const distinct = [...new Set(values.filter((v): v is string => v !== undefined))]
    out[k] = distinct.length === 1 && values.every((v) => v === distinct[0]) ? distinct[0] : distinct.length ? distinct : undefined
    if (distinct.length === 1 && values.some((v) => v === undefined)) out[k] = [...distinct, '']
  }
  return out
})

const single = computed(() => (props.entities.length === 1 ? props.entities[0] : null))

const rawTags = computed<Tags>({
  get: () => single.value?.tags ?? {},
  set: (v) => emit('tags', v),
})

const presetName = computed(() => {
  if (preset.value) return preset.value.name
  return t('preset.mixed')
})

const title = computed(() => {
  const s = single.value
  if (s) return s.title ?? s.tags.name ?? ''
  return t('preset.multi', { n: props.entities.length })
})

function choose(id: string) {
  pickerOpen.value = false
  emit('preset', id)
}

function runSmart(p: SmartPreset) {
  pickerOpen.value = false
  emit('smart', p)
}
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col" data-testid="preset-editor">
    <PresetPicker
      v-if="pickerOpen && !readonly"
      class="min-h-0 flex-1"
      :geometries="geometries"
      :current-id="preset?.id ?? null"
      :smart="smart"
      @choose="choose"
      @smart="runSmart"
      @close="pickerOpen = false"
    />
    <div v-else class="scroll-thin min-h-0 flex-1 overflow-y-auto">
      <div class="flex items-center gap-2.5 border-b border-line px-4 py-3">
        <PresetIcon :icon="preset?.icon" :size="36" data-testid="edit-preset-icon" />
        <div class="min-w-0 flex-1">
          <button
            class="group flex max-w-full items-center gap-1 truncate text-fg-2 hover:text-accent disabled:hover:text-fg-2"
            :class="title ? 'text-[12px]' : 'text-[15px] font-semibold text-fg'"
            :disabled="readonly"
            :title="t('preset.change')"
            data-testid="edit-preset-name"
            @click="pickerOpen = true"
          >
            <span class="truncate">{{ presetName }}</span>
            <Icon v-if="!readonly" name="chevron-down" :size="12" class="shrink-0 opacity-60 group-hover:opacity-100" />
          </button>
          <h3 v-if="title" class="truncate text-[15px] leading-snug font-semibold">{{ title }}</h3>
          <slot name="meta" />
        </div>
        <div class="flex shrink-0 flex-col items-end gap-1">
          <div class="flex overflow-hidden rounded-md border border-line text-[10.5px]" :title="t('inspector.locale')">
            <button
              v-for="l in ['cs', 'en'] as const"
              :key="l"
              class="px-1.5 py-0.5 uppercase"
              :class="presets.locale === l ? 'bg-accent text-white' : 'text-fg-3 hover:bg-surface-3'"
              :data-testid="`preset-locale-${l}`"
              @click="presets.locale = l"
            >
              {{ l }}
            </button>
          </div>
          <button v-if="!readonly" class="btn btn-sm" data-testid="change-preset" @click="pickerOpen = true">{{ t('preset.change') }}</button>
        </div>
      </div>

      <slot name="before-fields" />

      <section v-if="smart.length && !readonly" class="flex flex-wrap items-center gap-1.5 border-b border-line px-4 py-2" data-testid="smart-presets">
        <span class="text-[11px] font-medium text-fg-3">{{ t('smart.available') }}</span>
        <button v-for="s in smart" :key="s.id" class="btn btn-sm text-claude" :title="s.description ?? s.id" :data-smart="s.id" @click="emit('smart', s)">
          <Icon name="sparkle" :size="12" /> {{ s.name }}
        </button>
      </section>

      <section v-if="presets.catalog" class="border-b border-line px-4 py-3" data-testid="edit-preset-fields">
        <PresetFieldList
          :fields="fieldSets.fields"
          :more-fields="fieldSets.more"
          :tags="fieldTags"
          :geometries="geometries"
          :locale="presets.locale"
          :readonly="readonly"
          :multi-selection="entities.length > 1"
          :field-by-id="(id: string) => presets.catalog?.field(id) ?? null"
          @change="emit('patch', $event)"
        />
      </section>
      <div v-else class="border-b border-line px-4 py-3 text-[12px] text-fg-3">{{ t('inspector.loading') }}</div>

      <section v-if="single" class="px-4 py-3">
        <TagEditor v-model="rawTags" :base="single.baseTags ?? null" :readonly="readonly">
          <template #title><span class="section-title">{{ t('preset.tags') }}</span></template>
        </TagEditor>
      </section>
      <slot name="after" />
    </div>
  </div>
</template>
