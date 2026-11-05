<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { addressRows, readAddress, writeAddress } from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const inputs = ref<HTMLInputElement[] | null>(null)
const rows = computed(() => addressRows(props.field, props.tags, props.locale))
const values = computed(() => readAddress(props.field, props.tags))

function value(key: string): string {
  return values.value[key]?.value ?? ''
}

function mixed(key: string): string[] | null {
  return values.value[key]?.mixed ?? null
}

function commit(key: string, e: Event) {
  if (props.readonly) return
  const el = e.target as HTMLInputElement
  const patch = writeAddress(props.tags, key, el.value)
  if (Object.keys(patch).length) emit('change', patch)
  void nextTick(() => {
    el.value = value(key)
  })
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
}

defineExpose({ focus: () => inputs.value?.[0]?.focus() })
</script>

<template>
  <div class="flex flex-col gap-1">
    <div v-for="(row, i) in rows" :key="i" class="flex gap-1">
      <input
        v-for="sub in row"
        :key="sub.key"
        ref="inputs"
        class="input min-w-0"
        :style="{ flex: `${sub.width} 1 0` }"
        :value="value(sub.key)"
        :placeholder="mixed(sub.key) ? tr(locale, 'field.multiple') : sub.placeholder"
        :title="mixed(sub.key)?.join('\n') ?? `${sub.label} (${sub.key})`"
        :readonly="readonly"
        spellcheck="false"
        autocomplete="off"
        @change="commit(sub.key, $event)"
        @keydown="onKey"
      />
    </div>
  </div>
</template>
