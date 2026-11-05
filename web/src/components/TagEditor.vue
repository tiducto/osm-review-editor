<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import Icon from './Icon.vue'

type Tags = Record<string, string>
interface Row {
  id: number
  k: string
  v: string
}

const props = withDefaults(defineProps<{ base?: Tags | null; readonly?: boolean }>(), { base: null, readonly: false })
const model = defineModel<Tags>({ required: true })

let seq = 0
const rows = ref<Row[]>([])
const textMode = ref(false)
const text = ref('')
const listEl = ref<HTMLElement | null>(null)

function build(rs: Row[]): Tags {
  const out: Tags = {}
  for (const r of rs) {
    const k = r.k.trim()
    if (k) out[k] = r.v
  }
  return out
}

function same(a: Tags, b: Tags): boolean {
  const ka = Object.keys(a)
  return ka.length === Object.keys(b).length && ka.every((k) => a[k] === b[k])
}

function fromModel() {
  rows.value = Object.entries(model.value).map(([k, v]) => ({ id: ++seq, k, v }))
  text.value = toText(model.value)
}

watch(
  model,
  (m) => {
    if (!same(m, build(rows.value))) fromModel()
  },
  { immediate: true, deep: true },
)

function commit() {
  const t = build(rows.value)
  if (!same(t, model.value)) model.value = t
}

function toText(t: Tags): string {
  return Object.entries(t)
    .map(([k, v]) => `${k}=${v}`)
    .join('\n')
}

function onText() {
  const out: Tags = {}
  for (const line of text.value.split('\n')) {
    const i = line.indexOf('=')
    if (i <= 0) continue
    out[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  }
  model.value = out
}

function toggleText() {
  if (textMode.value) onText()
  else text.value = toText(model.value)
  textMode.value = !textMode.value
}

async function addRow(k = '', v = '') {
  rows.value.push({ id: ++seq, k, v })
  if (k) commit()
  await nextTick()
  const inputs = listEl.value?.querySelectorAll<HTMLInputElement>('input[data-key]')
  inputs?.[inputs.length - 1]?.focus()
}

function removeRow(id: number) {
  rows.value = rows.value.filter((r) => r.id !== id)
  commit()
}

function restore(k: string) {
  if (props.base && k in props.base) {
    const existing = rows.value.find((r) => r.k.trim() === k)
    if (existing) existing.v = props.base[k]
    else rows.value.push({ id: ++seq, k, v: props.base[k] })
    commit()
  }
}

function state(r: Row): 'added' | 'changed' | 'same' | null {
  if (!props.base) return null
  const k = r.k.trim()
  if (!k) return null
  if (!(k in props.base)) return 'added'
  return props.base[k] === r.v ? 'same' : 'changed'
}

const removed = computed(() => {
  if (!props.base) return []
  const keys = new Set(rows.value.map((r) => r.k.trim()))
  return Object.keys(props.base).filter((k) => !keys.has(k))
})

const dupes = computed(() => {
  const seen = new Set<string>()
  const d = new Set<string>()
  for (const r of rows.value) {
    const k = r.k.trim()
    if (seen.has(k)) d.add(k)
    seen.add(k)
  }
  return d
})

function onValueKey(e: KeyboardEvent, i: number) {
  if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey && i === rows.value.length - 1) {
    e.preventDefault()
    void addRow()
  }
}
</script>

<template>
  <div>
    <div class="mb-1.5 flex items-center gap-2">
      <slot name="title"><span class="section-title">Tags</span></slot>
      <span class="text-[11px] text-fg-3">{{ Object.keys(model).length }}</span>
      <div class="ml-auto flex items-center gap-0.5">
        <button
          v-if="!readonly"
          class="btn btn-ghost btn-sm text-fg-3"
          :title="textMode ? 'Edit as a table' : 'Edit as text (key=value per line)'"
          @click="toggleText"
        >
          {{ textMode ? 'Table' : 'Text' }}
        </button>
      </div>
    </div>

    <textarea
      v-if="textMode"
      v-model="text"
      class="input h-auto min-h-40 py-1.5 font-mono text-[12px] leading-5"
      :rows="Math.max(6, rows.length + 1)"
      spellcheck="false"
      @input="onText"
    />

    <div v-else ref="listEl" class="overflow-hidden rounded-md border border-line font-mono text-[12px]">
      <div
        v-for="(r, i) in rows"
        :key="r.id"
        class="group grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)_24px] border-b border-line last:border-b-0"
        :class="{
          'bg-added-soft': state(r) === 'added',
          'bg-changed-soft': state(r) === 'changed',
          'bg-removed-soft': dupes.has(r.k.trim()),
        }"
      >
        <input
          v-model="r.k"
          data-key
          class="min-w-0 border-r border-line bg-transparent px-2 py-1 text-fg-2 outline-none focus:bg-surface-2"
          :class="state(r) === 'added' && 'text-added'"
          :readonly="readonly"
          spellcheck="false"
          @change="commit"
        />
        <div class="relative min-w-0">
          <input
            v-model="r.v"
            class="w-full min-w-0 bg-transparent px-2 py-1 text-fg outline-none focus:bg-surface-2"
            :readonly="readonly"
            spellcheck="false"
            @input="commit"
            @keydown="onValueKey($event, i)"
          />
          <div v-if="state(r) === 'changed' && base" class="-mt-1 truncate px-2 pb-1 text-[10.5px] text-fg-3 line-through" :title="base[r.k.trim()]">
            {{ base[r.k.trim()] }}
          </div>
        </div>
        <button
          v-if="!readonly"
          class="grid place-items-center text-fg-3 opacity-0 group-hover:opacity-100 hover:text-removed focus:opacity-100"
          title="Remove tag"
          tabindex="-1"
          @click="removeRow(r.id)"
        >
          <Icon name="x" :size="12" />
        </button>
      </div>
      <div
        v-for="k in removed"
        :key="`rm-${k}`"
        class="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)_24px] border-b border-line bg-removed-soft text-removed last:border-b-0"
      >
        <span class="truncate border-r border-line px-2 py-1 line-through">{{ k }}</span>
        <span class="truncate px-2 py-1 line-through">{{ base?.[k] }}</span>
        <button class="grid place-items-center text-fg-3 hover:text-fg" title="Restore" @click="restore(k)"><Icon name="undo" :size="12" /></button>
      </div>
      <div v-if="!rows.length && !removed.length" class="px-2 py-2 font-sans text-fg-3">No tags</div>
    </div>

    <button v-if="!readonly && !textMode" class="btn btn-ghost btn-sm mt-1 text-fg-3" @click="addRow()">
      <Icon name="plus" :size="12" /> Add tag
    </button>
  </div>
</template>
