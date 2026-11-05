<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import Icon from '../../Icon.vue'
import type { ComboOption } from '@/presets/fields'

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: ComboOption[]
    placeholder?: string
    title?: string
    readonly?: boolean
    allowCustom?: boolean
    clearOnCommit?: boolean
    mono?: boolean
    invalid?: boolean
    noMatchText?: string
    inputClass?: string
  }>(),
  { placeholder: '', title: undefined, readonly: false, allowCustom: true, clearOnCommit: false, mono: false, invalid: false, noMatchText: '', inputClass: '' },
)

const emit = defineEmits<{ commit: [text: string, option: ComboOption | null] }>()

const text = ref(props.modelValue)
const open = ref(false)
const filtering = ref(false)
const active = ref(-1)
const inputEl = ref<HTMLInputElement | null>(null)
const listEl = ref<HTMLElement | null>(null)
let lastCommitted = props.modelValue

watch(
  () => props.modelValue,
  (v) => {
    text.value = v
    lastCommitted = v
  },
)

function fold(s: string): string {
  return s
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
}

const filtered = computed(() => {
  if (!filtering.value || !text.value.trim()) return props.options
  const q = fold(text.value.trim())
  const starts: ComboOption[] = []
  const contains: ComboOption[] = []
  for (const o of props.options) {
    const t = fold(o.title)
    const v = fold(o.value)
    if (t.startsWith(q) || v.startsWith(q)) starts.push(o)
    else if (t.includes(q) || v.includes(q) || o.terms?.some((term) => fold(term).includes(q))) contains.push(o)
  }
  return [...starts, ...contains]
})

watch(filtered, (list) => {
  active.value = filtering.value && list.length && text.value.trim() ? 0 : -1
})

function exactMatch(s: string): ComboOption | null {
  const q = s.trim().toLowerCase()
  if (!q) return null
  return props.options.find((o) => o.title.toLowerCase() === q || o.value.toLowerCase() === q) ?? null
}

function commit(option: ComboOption | null) {
  const value = option ? option.title : text.value
  const match = option ?? exactMatch(value)
  if (!props.allowCustom && !match && value.trim()) {
    text.value = props.modelValue
    return
  }
  open.value = false
  filtering.value = false
  if (option) text.value = option.title
  lastCommitted = text.value
  emit('commit', text.value, match)
  if (props.clearOnCommit) {
    text.value = ''
    lastCommitted = ''
  } else {
    void nextTick(() => {
      text.value = props.modelValue
      lastCommitted = props.modelValue
    })
  }
}

function onFocus() {
  if (props.readonly) return
  open.value = true
  filtering.value = false
  active.value = -1
}

function onInput() {
  open.value = true
  filtering.value = true
}

function onBlur() {
  open.value = false
  if (props.readonly) return
  if (text.value !== lastCommitted) commit(null)
  filtering.value = false
}

function scrollActive() {
  void nextTick(() => {
    const el = listEl.value?.querySelector<HTMLElement>('[data-active="true"]')
    el?.scrollIntoView({ block: 'nearest' })
  })
}

function onKey(e: KeyboardEvent) {
  if (props.readonly) return
  const n = filtered.value.length
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!open.value) open.value = true
    if (n) active.value = (active.value + 1) % n
    scrollActive()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (!open.value) open.value = true
    if (n) active.value = active.value <= 0 ? n - 1 : active.value - 1
    scrollActive()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const opt = open.value && active.value >= 0 ? filtered.value[active.value] : null
    commit(opt ?? null)
  } else if (e.key === 'Escape') {
    if (open.value) {
      e.preventDefault()
      e.stopPropagation()
      open.value = false
    }
    text.value = lastCommitted
    filtering.value = false
  } else if (e.key === 'Tab') {
    open.value = false
  }
}

function toggle() {
  if (props.readonly) return
  if (open.value) {
    open.value = false
    return
  }
  inputEl.value?.focus()
  open.value = true
  filtering.value = false
}

function pick(o: ComboOption) {
  commit(o)
}

function focus() {
  inputEl.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="relative min-w-0">
    <input
      ref="inputEl"
      v-model="text"
      class="input pr-6"
      :class="[mono && 'font-mono text-[12px]', invalid && 'border-removed', inputClass]"
      :placeholder="placeholder"
      :title="title"
      :readonly="readonly"
      spellcheck="false"
      autocomplete="off"
      role="combobox"
      :aria-expanded="open"
      @focus="onFocus"
      @blur="onBlur"
      @input="onInput"
      @keydown="onKey"
    />
    <button
      v-if="!readonly && options.length"
      type="button"
      tabindex="-1"
      class="absolute inset-y-0 right-0 grid w-7 place-items-center text-fg-3 hover:text-fg"
      @mousedown.prevent="toggle"
    >
      <Icon name="chevron-down" :size="12" />
    </button>
    <div
      v-if="open && !readonly && (filtered.length || (noMatchText && filtering && text.trim()))"
      ref="listEl"
      class="scroll-thin absolute top-full right-0 left-0 z-40 -mt-px max-h-60 overflow-y-auto rounded-b-md border border-line-strong bg-surface shadow-pop"
      role="listbox"
    >
      <button
        v-for="(o, i) in filtered"
        :key="o.value"
        type="button"
        tabindex="-1"
        role="option"
        :data-active="i === active"
        :aria-selected="i === active"
        class="flex w-full items-baseline gap-2 px-2.5 py-1 text-left text-[12px]"
        :class="i === active ? 'bg-accent-soft text-fg' : 'text-fg hover:bg-surface-3'"
        :title="o.description ?? o.value"
        @mousedown.prevent="pick(o)"
        @mouseenter="active = i"
      >
        <span class="min-w-0 truncate" :class="o.raw && 'font-mono text-[12px]'">{{ o.title }}</span>
        <span v-if="!o.raw && o.title !== o.value" class="ml-auto shrink-0 truncate font-mono text-[11px] text-fg-3">{{ o.value }}</span>
      </button>
      <div v-if="!filtered.length" class="px-2.5 py-1 text-[12px] text-fg-3">{{ noMatchText }}</div>
    </div>
  </div>
</template>
