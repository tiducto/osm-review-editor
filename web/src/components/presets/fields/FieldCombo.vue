<script setup lang="ts">
import { computed, ref } from 'vue'
import ComboInput from './ComboInput.vue'
import MixedHint from './MixedHint.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { allowsCustomValues, comboOptions, fieldPlaceholder, readCombo, writeCombo, writeComboValue, type ComboOption } from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const combo = ref<InstanceType<typeof ComboInput> | null>(null)
const state = computed(() => readCombo(props.field, props.tags))
const options = computed(() => comboOptions(props.field).filter((o) => !(props.field.type === 'typeCombo' && o.value === 'yes')))
const placeholder = computed(() => fieldPlaceholder(props.field, props.locale, state.value.mixed))

function commit(text: string, option: ComboOption | null) {
  const patch = option ? writeComboValue(props.field, option.value) : writeCombo(props.field, props.tags, text)
  if (Object.keys(patch).length) emit('change', patch)
}

defineExpose({ focus: () => combo.value?.focus() })
</script>

<template>
  <div class="min-w-0">
    <ComboInput
      ref="combo"
      :model-value="state.display"
      :options="options"
      :placeholder="placeholder"
      :title="state.mixed?.join('\n')"
      :readonly="readonly"
      :allow-custom="allowsCustomValues(field)"
      :mono="state.raw"
      :no-match-text="tr(locale, 'field.noMatches')"
      @commit="commit"
    />
    <MixedHint :values="state.mixed" />
  </div>
</template>
