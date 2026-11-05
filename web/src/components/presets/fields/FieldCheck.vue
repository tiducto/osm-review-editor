<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from '../../Icon.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { checkText, nextCheck, optionDescription, readCheck, reverseOneway } from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const btn = ref<HTMLButtonElement | null>(null)
const state = computed(() => readCheck(props.field, props.tags))
const text = computed(() => checkText(props.field, state.value, props.locale))
const tone = computed(() => {
  if (state.value.mixed) return 'text-fg-2 italic'
  if (state.value.value === 'yes') return 'text-added'
  if (state.value.value === 'no') return 'text-removed'
  if (!state.value.value) return 'text-fg-3'
  return 'text-fg'
})
const tooltip = computed(() => {
  if (state.value.mixed) return state.value.mixed.map((v) => v || '—').join('\n')
  const v = state.value.raw
  if (!v || !props.field.key) return undefined
  const desc = optionDescription(props.field, v)
  return desc ? `${desc}\n${props.field.key}=${v}` : `${props.field.key}=${v}`
})

function cycle() {
  if (props.readonly) return
  emit('change', nextCheck(props.field, props.tags))
}

function reverse() {
  if (props.readonly) return
  const patch = reverseOneway(props.field, props.tags)
  if (Object.keys(patch).length) emit('change', patch)
}

defineExpose({ focus: () => btn.value?.focus() })
</script>

<template>
  <div class="flex min-w-0 items-stretch">
    <button
      ref="btn"
      type="button"
      class="input flex min-w-0 flex-1 items-center gap-2 text-left"
      :class="[readonly ? 'cursor-default' : 'hover:bg-surface-3', field.type === 'onewayCheck' && state.canReverse && 'rounded-br-none']"
      :disabled="readonly"
      :title="tooltip"
      role="checkbox"
      :aria-checked="state.mixed || state.indeterminate ? 'mixed' : state.checked"
      @click="cycle"
    >
      <span
        class="grid h-3.5 w-3.5 shrink-0 place-items-center rounded-[3px] border"
        :class="
          state.mixed || (state.indeterminate && !state.checked)
            ? 'border-line-strong bg-surface-2 text-fg-3'
            : state.checked
              ? 'border-accent bg-accent text-accent-fg'
              : 'border-line-strong bg-surface text-removed'
        "
      >
        <Icon v-if="state.checked && !state.mixed" name="check" :size="10" :stroke="3" />
        <Icon v-else-if="state.value === 'no' && !state.mixed" name="x" :size="10" :stroke="3" />
        <Icon v-else name="minus" :size="10" :stroke="3" />
      </span>
      <span class="min-w-0 truncate" :class="tone">{{ text }}</span>
    </button>
    <button
      v-if="field.type === 'onewayCheck' && state.canReverse"
      type="button"
      class="btn h-7 shrink-0 -ml-px rounded-none rounded-br-md"
      :class="state.reversed && 'bg-accent-soft text-accent'"
      :disabled="readonly"
      :title="tr(locale, 'field.reverseTitle')"
      @click="reverse"
    >
      <Icon name="swap" :size="14" />
      {{ tr(locale, 'field.reverse') }}
    </button>
  </div>
</template>
