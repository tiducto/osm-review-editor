<script setup lang="ts">
import type { Preset, PresetField } from '@/lib/presets'
import { humanize } from '@/lib/osm'

const props = withDefaults(defineProps<{ preset: Preset; readonly?: boolean }>(), { readonly: false })
const model = defineModel<Record<string, string>>({ required: true })

function set(key: string, value: string) {
  const next = { ...model.value }
  if (value === '') delete next[key]
  else next[key] = value
  model.value = next
}

function checkState(f: PresetField): 'yes' | 'no' | '' {
  const v = model.value[f.key]
  return v === 'yes' || v === 'no' ? v : ''
}

function cycleCheck(f: PresetField) {
  if (props.readonly) return
  const s = checkState(f)
  set(f.key, s === '' ? 'yes' : s === 'yes' ? 'no' : '')
}
</script>

<template>
  <div class="grid grid-cols-2 gap-x-3 gap-y-2.5">
    <label v-for="f in preset.fields" :key="f.key" class="flex min-w-0 flex-col gap-1" :class="f.type === 'text' && 'col-span-2'">
      <span class="text-[11.5px] font-medium text-fg-2">{{ f.label }}</span>
      <template v-if="f.type === 'check'">
        <button
          type="button"
          class="input flex items-center justify-between text-left"
          :class="{ 'text-added': checkState(f) === 'yes', 'text-removed': checkState(f) === 'no', 'text-fg-3': !checkState(f) }"
          :disabled="readonly"
          @click="cycleCheck(f)"
        >
          {{ checkState(f) === 'yes' ? 'Yes' : checkState(f) === 'no' ? 'No' : model[f.key] ? model[f.key] : 'Unknown' }}
          <span class="text-[10.5px] text-fg-3">click to cycle</span>
        </button>
      </template>
      <template v-else-if="f.type === 'combo'">
        <input
          class="input"
          :list="`preset-${preset.id}-${f.key}`"
          :value="model[f.key] ?? ''"
          :readonly="readonly"
          placeholder="—"
          @change="set(f.key, ($event.target as HTMLInputElement).value.trim())"
        />
        <datalist :id="`preset-${preset.id}-${f.key}`">
          <option v-for="o in f.options" :key="o" :value="o">{{ humanize(o) }}</option>
        </datalist>
      </template>
      <input
        v-else
        class="input"
        :type="f.type === 'number' ? 'text' : 'text'"
        :inputmode="f.type === 'number' ? 'decimal' : 'text'"
        :value="model[f.key] ?? ''"
        :placeholder="f.placeholder ?? '—'"
        :readonly="readonly"
        @change="set(f.key, ($event.target as HTMLInputElement).value.trim())"
      />
    </label>
  </div>
</template>
