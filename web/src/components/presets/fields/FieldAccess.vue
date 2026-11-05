<script setup lang="ts">
import { computed, ref } from 'vue'
import ComboInput from './ComboInput.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { accessOptions, readAccess, writeAccess, type ComboOption } from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const combos = ref<InstanceType<typeof ComboInput>[] | null>(null)
const rows = computed(() => readAccess(props.field, props.tags, props.locale))

function commit(key: string, text: string, option: ComboOption | null) {
  const patch = writeAccess(props.field, props.tags, key, option ? option.value : text)
  if (Object.keys(patch).length) emit('change', patch)
}

defineExpose({ focus: () => combos.value?.[0]?.focus() })
</script>

<template>
  <div class="overflow-visible rounded-md border border-line">
    <div
      v-for="row in rows"
      :key="row.key"
      class="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] items-center gap-2 border-b border-line px-1.5 py-1 last:border-b-0"
    >
      <span class="truncate text-[12px] text-fg-2" :title="row.key">{{ row.label }}</span>
      <div class="min-w-0">
        <ComboInput
          ref="combos"
          :model-value="row.display"
          :options="accessOptions(field, row.key)"
          :placeholder="row.placeholder"
          :title="row.mixed?.join('\n')"
          :readonly="readonly"
          :no-match-text="tr(locale, 'field.noMatches')"
          @commit="(text, option) => commit(row.key, text, option)"
        />
      </div>
    </div>
  </div>
</template>
