<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from '../../Icon.vue'
import ComboInput from './ComboInput.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { addMulti, allowsCustomValues, readMulti, removeMulti, toggleMulti, type ComboOption, type MultiItem } from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const combo = ref<InstanceType<typeof ComboInput> | null>(null)
const state = computed(() => readMulti(props.field, props.tags))
const canAdd = computed(() => !props.readonly && (allowsCustomValues(props.field) || state.value.available.length > 0))

function send(patch: TagPatch) {
  if (Object.keys(patch).length) emit('change', patch)
}

function add(text: string, option: ComboOption | null) {
  send(addMulti(props.field, props.tags, option ? option.value : text))
}

function remove(item: MultiItem) {
  if (props.readonly) return
  send(removeMulti(props.field, props.tags, item))
}

function toggle(item: MultiItem) {
  if (props.readonly || props.field.type !== 'multiCombo') return
  send(toggleMulti(props.field, props.tags, item))
}

function chipTitle(item: MultiItem): string {
  if (item.mixed) return tr(props.locale, 'field.multiple')
  if (props.field.type === 'multiCombo') return `${item.key}=${props.tags[item.key] ?? ''}\n${tr(props.locale, 'field.invert')}`
  if (props.field.type === 'manyCombo') return `${item.key}=${props.tags[item.key] ?? ''}`
  return item.value
}

defineExpose({ focus: () => combo.value?.focus() })
</script>

<template>
  <div class="min-w-0">
    <div v-if="state.items.length" class="mb-1 flex flex-wrap gap-1">
      <span
        v-for="item in state.items"
        :key="item.key + ':' + item.index"
        class="chip max-w-full border bg-surface-3 pr-0.5 text-fg"
        :class="[item.mixed ? 'border-dashed border-line-strong text-fg-2 italic' : 'border-transparent', item.state === 'no' && 'text-fg-3 line-through']"
        :title="chipTitle(item)"
      >
        <button
          type="button"
          class="min-w-0 truncate"
          :class="field.type === 'multiCombo' && !readonly ? 'cursor-pointer' : 'cursor-default'"
          :disabled="readonly"
          @click="toggle(item)"
        >
          {{ item.title }}
        </button>
        <button
          v-if="!readonly"
          type="button"
          class="grid h-4 w-4 place-items-center rounded-full text-fg-3 hover:bg-surface hover:text-removed"
          :title="tr(locale, 'field.remove')"
          @click="remove(item)"
        >
          <Icon name="x" :size="10" />
        </button>
      </span>
    </div>
    <ComboInput
      v-if="canAdd"
      ref="combo"
      model-value=""
      :options="state.available"
      :placeholder="state.items.length ? tr(locale, 'field.addValue') : field.placeholder || tr(locale, 'field.addValue')"
      :allow-custom="allowsCustomValues(field)"
      :no-match-text="tr(locale, 'field.noMatches')"
      clear-on-commit
      @commit="add"
    />
  </div>
</template>
