<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import Icon from '../../Icon.vue'
import MixedHint from './MixedHint.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import {
  canStepNumber,
  colourHex,
  fieldPlaceholder,
  identifierLink,
  isValidColour,
  isValidDate,
  isValidIdentifier,
  readInput,
  readNumber,
  stepNumber,
  urlLink,
  writeColourPicker,
  writeInput,
  writeNumber,
} from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const el = ref<HTMLInputElement | HTMLTextAreaElement | null>(null)

const isNumber = computed(() => props.field.type === 'number' || props.field.type === 'integer')
const state = computed(() => (isNumber.value ? readNumber(props.field, props.tags, props.locale) : readInput(props.field, props.tags)))
const placeholder = computed(() => fieldPlaceholder(props.field, props.locale, state.value.mixed))
const htmlType = computed(() => {
  switch (props.field.type) {
    case 'tel':
      return 'tel'
    case 'email':
      return 'email'
    default:
      return 'text'
  }
})
const inputMode = computed(() => {
  if (isNumber.value) return props.field.type === 'integer' ? 'numeric' : 'decimal'
  if (props.field.type === 'tel') return 'tel'
  if (props.field.type === 'email') return 'email'
  if (props.field.type === 'url') return 'url'
  return 'text'
})

const link = computed(() => {
  const v = state.value.value
  if (props.field.type === 'url') return urlLink(props.field, v)
  if (props.field.type === 'identifier') return identifierLink(props.field, v)
  return null
})

const invalidText = computed(() => {
  const v = state.value.value
  if (!v) return ''
  if (props.field.type === 'date' && !isValidDate(v)) return tr(props.locale, 'field.invalidDate')
  if (props.field.type === 'colour' && !isValidColour(v)) return tr(props.locale, 'field.invalidColour')
  if (props.field.type === 'identifier' && !isValidIdentifier(props.field, v)) return tr(props.locale, 'field.invalid')
  return ''
})

const swatch = computed(() => (props.field.type === 'colour' ? colourHex(state.value.value) : null))
const canStep = computed(() => isNumber.value && !props.readonly && canStepNumber(props.field, props.tags))

function resync() {
  void nextTick(() => {
    if (el.value) el.value.value = state.value.value
  })
}

function commit(e: Event) {
  if (props.readonly) return
  const raw = (e.target as HTMLInputElement).value
  const patch = isNumber.value ? writeNumber(props.field, props.tags, raw) : writeInput(props.field, props.tags, raw)
  if (Object.keys(patch).length) emit('change', patch)
  resync()
}

function step(dir: 1 | -1) {
  const patch = stepNumber(props.field, props.tags, dir)
  if (Object.keys(patch).length) emit('change', patch)
}

function pickColour(e: Event) {
  emit('change', writeColourPicker(props.field, props.tags, (e.target as HTMLInputElement).value))
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && props.field.type !== 'textarea') (e.target as HTMLInputElement).blur()
}

function focus() {
  el.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="min-w-0">
    <textarea
      v-if="field.type === 'textarea'"
      ref="el"
      class="input h-auto min-h-14 py-1 leading-snug"
      rows="3"
      :value="state.value"
      :placeholder="placeholder"
      :title="state.mixed?.join('\n')"
      :readonly="readonly"
      @change="commit"
    />
    <div v-else class="flex min-w-0 items-stretch">
      <div class="relative flex min-w-0 flex-1">
        <input
          ref="el"
          class="input min-w-0"
          :class="[invalidText && 'border-removed', link ? 'rounded-br-none' : (swatch !== null || field.type === 'colour') && 'pr-7', isNumber && 'rounded-br-none']"
          :type="htmlType"
          :inputmode="inputMode"
          :value="state.value"
          :placeholder="placeholder"
          :title="state.mixed?.join('\n') ?? (invalidText || undefined)"
          :readonly="readonly"
          spellcheck="false"
          autocomplete="off"
          @change="commit"
          @keydown="onKey"
        />
        <a
          v-if="link"
          :href="link"
          target="_blank"
          rel="noopener noreferrer"
          class="-ml-px grid w-8 shrink-0 place-items-center rounded-br-md border border-line-strong bg-surface text-fg opacity-70 hover:bg-surface-3 hover:opacity-100"
          :title="tr(locale, 'field.openLink')"
        >
          <Icon name="external" :size="14" />
        </a>
        <label
          v-else-if="field.type === 'colour'"
          class="absolute inset-y-0 right-1 my-auto grid h-5 w-5 cursor-pointer place-items-center overflow-hidden rounded-sm border border-line-strong"
          :style="{ background: swatch ?? 'transparent' }"
          :title="tr(locale, 'field.pickColour')"
        >
          <input type="color" class="absolute inset-0 h-full w-full cursor-pointer opacity-0" :value="swatch ?? '#000000'" :disabled="readonly" @change="pickColour" />
        </label>
      </div>
      <template v-if="isNumber">
        <button
          type="button"
          class="-ml-px grid w-8 shrink-0 place-items-center border border-line-strong bg-surface text-fg hover:bg-surface-3 disabled:opacity-40"
          :disabled="!canStep"
          :title="tr(locale, 'field.decrement')"
          @click="step(-1)"
        >
          <Icon name="minus" :size="14" />
        </button>
        <button
          type="button"
          class="-ml-px grid w-8 shrink-0 place-items-center rounded-br-md border border-line-strong bg-surface text-fg hover:bg-surface-3 disabled:opacity-40"
          :disabled="!canStep"
          :title="tr(locale, 'field.increment')"
          @click="step(1)"
        >
          <Icon name="plus" :size="14" />
        </button>
      </template>
    </div>
    <p v-if="invalidText" class="mt-0.5 text-[11px] text-removed">{{ invalidText }}</p>
    <MixedHint :values="state.mixed" />
  </div>
</template>
