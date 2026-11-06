<script setup lang="ts">
import { computed, nextTick, ref, watch, type Component } from 'vue'
import Icon from '../Icon.vue'
import ComboInput from './fields/ComboInput.vue'
import FieldInput from './fields/FieldInput.vue'
import FieldCombo from './fields/FieldCombo.vue'
import FieldMultiCombo from './fields/FieldMultiCombo.vue'
import FieldCheck from './fields/FieldCheck.vue'
import FieldRadio from './fields/FieldRadio.vue'
import FieldAccess from './fields/FieldAccess.vue'
import FieldAddress from './fields/FieldAddress.vue'
import FieldLocalized from './fields/FieldLocalized.vue'
import FieldWiki from './fields/FieldWiki.vue'
import FieldDirectional from './fields/FieldDirectional.vue'
import FieldRoad from './fields/FieldRoad.vue'
import type { FieldDef, FieldTags, Geometry, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import { fieldFamily, fieldKeys, isFieldPresent, removeFieldPatch, visibleFields, type ComboOption, type FieldFamily } from '@/presets/fields'

const props = withDefaults(
  defineProps<{
    fields: FieldDef[]
    moreFields: FieldDef[]
    tags: FieldTags
    geometries: Geometry[]
    locale: Locale
    readonly?: boolean
    multiSelection?: boolean
    fieldById?: (id: string) => FieldDef | null | undefined
  }>(),
  { readonly: false, multiSelection: undefined, fieldById: undefined },
)

const emit = defineEmits<{ change: [patch: TagPatch] }>()

const COMPONENTS: Record<Exclude<FieldFamily, 'fallback'>, Component> = {
  input: FieldInput,
  combo: FieldCombo,
  multi: FieldMultiCombo,
  check: FieldCheck,
  radio: FieldRadio,
  access: FieldAccess,
  address: FieldAddress,
  localized: FieldLocalized,
  wikidata: FieldWiki,
  wikipedia: FieldWiki,
  directional: FieldDirectional,
  roadspeed: FieldRoad,
  roadheight: FieldRoad,
}

const userShown = ref<string[]>([])
const rowEls = ref<Record<string, { focus?: () => void } | null>>({})

watch(
  () => props.fields,
  () => {
    userShown.value = []
  },
)

const isMulti = computed(() => props.multiSelection ?? Object.values(props.tags).some((v) => Array.isArray(v)))

const visible = computed(() =>
  visibleFields({
    fields: props.fields,
    moreFields: props.moreFields,
    tags: props.tags,
    geometries: props.geometries,
    shownIds: userShown.value,
    locale: props.locale,
    multiSelection: isMulti.value,
  }),
)

const addOptions = computed<ComboOption[]>(() =>
  visible.value.addable.map((f) => ({
    value: f.id,
    title: f.label,
    terms: [...f.terms, ...fieldKeys(f)],
    raw: false,
  })),
)

const addPlaceholder = computed(() => {
  const titles = visible.value.addable.map((f) => f.label)
  if (!titles.length) return tr(props.locale, 'field.addPlaceholder')
  return titles.slice(0, 3).join(', ') + (titles.length > 3 ? '…' : '')
})

function component(field: FieldDef): Component | null {
  const fam = fieldFamily(field)
  return fam === 'fallback' ? null : COMPONENTS[fam]
}

function present(field: FieldDef): boolean {
  return isFieldPresent(field, props.tags)
}

function change(patch: TagPatch) {
  if (props.readonly || !Object.keys(patch).length) return
  emit('change', patch)
}

function remove(field: FieldDef) {
  if (!userShown.value.includes(field.id) && !props.fields.some((f) => f.id === field.id)) userShown.value = [...userShown.value, field.id]
  change(removeFieldPatch(field, props.tags))
}

async function addField(_text: string, option: ComboOption | null) {
  if (!option) return
  if (!userShown.value.includes(option.value)) userShown.value = [...userShown.value, option.value]
  await nextTick()
  rowEls.value[option.value]?.focus?.()
}

function setRowEl(id: string, el: unknown) {
  rowEls.value[id] = el as { focus?: () => void } | null
}

function fallbackValues(field: FieldDef): string {
  return fieldKeys(field, props.tags)
    .filter((k) => props.tags[k] !== undefined)
    .map((k) => {
      const v = props.tags[k]
      return `${k}=${Array.isArray(v) ? v.join(' | ') : v}`
    })
    .join(', ')
}

function labelTitle(field: FieldDef): string {
  const keys = fieldKeys(field, props.tags)
  return keys.length ? keys.join(', ') : field.id
}
</script>

<template>
  <div class="flex flex-col gap-2.5">
    <div v-for="field in visible.shown" :key="field.id" class="min-w-0" :data-field="field.id">
      <div class="mb-0.5 flex h-5 items-center gap-1">
        <span class="min-w-0 truncate text-[11.5px] font-medium text-fg-2" :title="labelTitle(field)">{{ field.label }}</span>
        <button
          v-if="!readonly && present(field)"
          type="button"
          class="ml-auto grid h-5 w-5 shrink-0 place-items-center rounded text-fg-3 hover:bg-removed-soft hover:text-removed"
          :title="tr(locale, 'field.remove')"
          @click="remove(field)"
        >
          <Icon name="trash" :size="12" />
        </button>
      </div>
      <component
        :is="component(field)!"
        v-if="component(field)"
        :ref="(el: unknown) => setRowEl(field.id, el)"
        :field="field"
        :tags="tags"
        :locale="locale"
        :readonly="readonly"
        v-bind="fieldFamily(field) === 'radio' ? { fieldById } : {}"
        @change="change"
      />
      <div v-else class="rounded-md border border-dashed border-line bg-surface-2 px-2 py-1 text-[11.5px] text-fg-3">
        <div>{{ tr(locale, 'field.editInTags') }}</div>
        <div v-if="fallbackValues(field)" class="truncate font-mono text-[11px] text-fg-2" :title="fallbackValues(field)">{{ fallbackValues(field) }}</div>
      </div>
    </div>

    <div v-if="!readonly && visible.addable.length" class="min-w-0">
      <div class="mb-0.5 text-[11.5px] font-medium text-fg-3">{{ tr(locale, 'field.add') }}</div>
      <ComboInput
        model-value=""
        :options="addOptions"
        :placeholder="addPlaceholder"
        :allow-custom="false"
        :no-match-text="tr(locale, 'field.noMatches')"
        clear-on-commit
        @commit="addField"
      />
    </div>
  </div>
</template>
