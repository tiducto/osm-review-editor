<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import Icon from '../../Icon.vue'
import ComboInput from './ComboInput.vue'
import MixedHint from './MixedHint.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import {
  isLanguageCode,
  isValidQid,
  LANGUAGES,
  readWikidata,
  readWikipedia,
  wikidataLink,
  wikipediaLink,
  writeWikidata,
  writeWikipedia,
  type ComboOption,
} from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

const el = ref<HTMLInputElement | null>(null)
const isWikidata = computed(() => props.field.type === 'wikidata')
const wd = computed(() => readWikidata(props.field, props.tags))
const wp = computed(() => readWikipedia(props.field, props.tags, langOverride.value ?? props.locale))
const langOverride = ref<string | null>(null)
const link = computed(() => (isWikidata.value ? wikidataLink(wd.value.value) : wikipediaLink(wp.value)))
const mixed = computed(() => (isWikidata.value ? wd.value.mixed : wp.value.mixed))
const invalid = computed(() => isWikidata.value && !isValidQid(wd.value.value))
const langOptions = computed<ComboOption[]>(() => LANGUAGES.map((l) => ({ value: l.code, title: l.code, description: l.native, terms: [l.native] })))

function send(patch: TagPatch) {
  if (Object.keys(patch).length) emit('change', patch)
}

function current(): string {
  return isWikidata.value ? wd.value.value : wp.value.title
}

function commit(e: Event) {
  if (props.readonly) return
  const input = e.target as HTMLInputElement
  if (isWikidata.value) send(writeWikidata(props.field, props.tags, input.value))
  else send(writeWikipedia(props.field, props.tags, wp.value.lang, input.value))
  void nextTick(() => {
    input.value = current()
  })
}

function commitLang(text: string, option: ComboOption | null) {
  const code = option?.value ?? text.trim().toLowerCase()
  if (!isLanguageCode(code)) return
  langOverride.value = code
  if (wp.value.title) send(writeWikipedia(props.field, props.tags, code, wp.value.title))
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
}

defineExpose({ focus: () => el.value?.focus() })
</script>

<template>
  <div class="min-w-0">
    <div class="flex min-w-0">
      <div v-if="!isWikidata" class="w-16 shrink-0 [&_.input]:rounded-br-none">
        <ComboInput :model-value="wp.lang" :options="langOptions" :readonly="readonly" mono :title="tr(locale, 'field.language')" @commit="commitLang" />
      </div>
      <div class="relative flex min-w-0 flex-1">
        <input
          ref="el"
          class="input min-w-0"
          :class="[isWikidata && 'font-mono text-[12px]', invalid && 'border-removed', !isWikidata && '-ml-px rounded-bl-none', link && 'rounded-br-none']"
          :value="current()"
          :placeholder="mixed ? tr(locale, 'field.multiple') : isWikidata ? tr(locale, 'field.wikidataPlaceholder') : field.placeholder || tr(locale, 'field.wikipediaTitle')"
          :title="mixed?.join('\n') ?? (invalid ? tr(locale, 'field.invalid') : undefined)"
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
      </div>
    </div>
    <MixedHint :values="mixed" />
  </div>
</template>
