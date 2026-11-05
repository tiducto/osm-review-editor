<script setup lang="ts">
import { computed, ref } from 'vue'
import ComboInput from './ComboInput.vue'
import FieldInput from './FieldInput.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import {
  comboOptions,
  LAYER_FIELD,
  readCombo,
  readRadio,
  showsLayer,
  structureTypeField,
  writeRadio,
  writeStructureType,
  type ComboOption,
  type RadioOption,
} from '@/presets/fields'

const props = withDefaults(
  defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean; fieldById?: (id: string) => FieldDef | null | undefined }>(),
  { readonly: false, fieldById: undefined },
)
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const first = ref<HTMLButtonElement[] | null>(null)
const state = computed(() => readRadio(props.field, props.tags))
const isStructure = computed(() => props.field.type === 'structureRadio')
const typeField = computed(() => (isStructure.value && state.value.selected ? structureTypeField(state.value.selected, props.fieldById) : null))
const typeState = computed(() => (typeField.value ? readCombo(typeField.value, props.tags) : null))
const typeOptions = computed(() => (typeField.value ? comboOptions(typeField.value).filter((o) => o.value !== 'yes') : []))
const layerField = computed<FieldDef>(() => {
  const f = props.fieldById?.('layer')
  return f ? { ...f, type: 'integer', key: 'layer' } : { ...LAYER_FIELD, label: tr(props.locale, 'field.layer') }
})

function isOn(o: RadioOption): boolean {
  return state.value.selected === o.value || (!props.field.key && state.value.active.length > 1 && state.value.active.includes(o.value))
}

function isMixed(o: RadioOption): boolean {
  return state.value.mixed.includes(o.value)
}

function select(o: RadioOption) {
  if (props.readonly) return
  const next = state.value.selected === o.value ? null : o.value
  emit('change', writeRadio(props.field, props.tags, next))
}

function optionTooltip(o: RadioOption): string | undefined {
  if (isMixed(o)) return tr(props.locale, 'field.multiple')
  if (!props.field.key) return o.description
  const tag = `${props.field.key}=${o.value}`
  return o.description ? `${o.description}\n${tag}` : tag
}

function commitType(text: string, option: ComboOption | null) {
  const key = state.value.selected
  if (!key || !typeField.value) return
  emit('change', writeStructureType(props.tags, key, typeField.value, option ? option.value : text))
}

defineExpose({ focus: () => first.value?.[0]?.focus() })
</script>

<template>
  <div class="min-w-0">
    <div class="flex flex-col overflow-hidden rounded-b-md border border-line-strong bg-surface" role="radiogroup">
      <button
        v-for="o in state.options"
        :key="o.value"
        ref="first"
        type="button"
        role="radio"
        :aria-checked="isOn(o)"
        class="flex h-7 min-w-0 items-center gap-2 border-b border-line px-2.5 text-left text-[12px] transition-colors last:border-b-0"
        :class="[
          isOn(o) ? 'bg-accent-soft font-bold text-fg' : 'bg-surface text-fg',
          isMixed(o) && !isOn(o) && 'text-fg-2 italic',
          !readonly && !isOn(o) && 'hover:bg-surface-3',
          o.raw && 'font-mono text-[12px]',
        ]"
        :disabled="readonly"
        :title="optionTooltip(o)"
        @click="select(o)"
      >
        <span
          class="grid size-3.5 shrink-0 place-items-center rounded-full border"
          :class="isOn(o) ? 'border-accent bg-surface' : isMixed(o) ? 'border-dashed border-fg-3 bg-surface' : 'border-line-strong bg-surface'"
        >
          <span v-if="isOn(o)" class="size-2 rounded-full bg-accent" />
        </span>
        <span class="truncate">{{ o.title }}</span>
      </button>
    </div>
    <div v-if="isStructure && (typeField || showsLayer(field, tags))" class="mt-1.5 grid grid-cols-[minmax(0,3fr)_minmax(0,2fr)] gap-1.5 rounded-md border border-line-strong bg-surface-2 p-2 [&_.input]:rounded-md!">
      <label v-if="typeField && typeState" class="flex min-w-0 flex-col gap-0.5" :class="!showsLayer(field, tags) && 'col-span-2'">
        <span class="text-[11px] font-bold text-fg">{{ tr(locale, 'field.type') }}</span>
        <ComboInput
          :model-value="typeState.display"
          :options="typeOptions"
          :placeholder="typeField.placeholder ?? ''"
          :readonly="readonly"
          :mono="typeState.raw"
          :no-match-text="tr(locale, 'field.noMatches')"
          @commit="commitType"
        />
      </label>
      <label v-if="showsLayer(field, tags)" class="flex min-w-0 flex-col gap-0.5" :class="!typeField && 'col-span-2'">
        <span class="text-[11px] font-bold text-fg">{{ layerField.label || tr(locale, 'field.layer') }}</span>
        <FieldInput :field="layerField" :tags="tags" :locale="locale" :readonly="readonly" @change="emit('change', $event)" />
      </label>
    </div>
  </div>
</template>
