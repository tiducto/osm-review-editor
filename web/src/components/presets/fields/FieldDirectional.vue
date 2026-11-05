<script setup lang="ts">
import { computed, ref } from 'vue'
import ComboInput from './ComboInput.vue'
import MixedHint from './MixedHint.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { allowsCustomValues, comboOptions, comboTagValue, directionalKeys, directionalSubfield, readCombo, readDirectional, writeDirectional, type ComboOption } from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const combos = ref<InstanceType<typeof ComboInput>[] | null>(null)
const keys = computed(() => directionalKeys(props.field).keys)
const values = computed(() => readDirectional(props.field, props.tags))
const options = computed(() => comboOptions(props.field))

function sideLabel(key: string, i: number): string {
  return props.field.types?.[key] ?? tr(props.locale, i === 0 ? 'field.left' : 'field.right')
}

function state(key: string) {
  const sub = directionalSubfield(props.field, key)
  return readCombo(sub, { [key]: values.value[key] })
}

function commit(key: string, text: string, option: ComboOption | null) {
  if (props.readonly) return
  const sub = directionalSubfield(props.field, key)
  const st = state(key)
  if (!option && !text.trim() && st.mixed) return
  const value = option ? option.value : text.trim() ? comboTagValue(sub, text) : undefined
  const patch = writeDirectional(props.field, props.tags, key, value)
  if (Object.keys(patch).length) emit('change', patch)
}

defineExpose({ focus: () => combos.value?.[0]?.focus() })
</script>

<template>
  <div class="flex flex-col gap-1">
    <div v-for="(key, i) in keys" :key="key" class="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] items-start gap-2">
      <span class="truncate pt-1.5 text-[12px] text-fg-2" :title="key">{{ sideLabel(key, i) }}</span>
      <div class="min-w-0">
        <ComboInput
          ref="combos"
          :model-value="state(key).display"
          :options="options"
          :placeholder="state(key).mixed ? tr(locale, 'field.multiple') : (field.placeholder ?? '')"
          :title="state(key).mixed?.join('\n')"
          :readonly="readonly"
          :allow-custom="allowsCustomValues(field)"
          :mono="state(key).raw"
          :no-match-text="tr(locale, 'field.noMatches')"
          @commit="(text, option) => commit(key, text, option)"
        />
        <MixedHint :values="state(key).mixed" />
      </div>
    </div>
  </div>
</template>
