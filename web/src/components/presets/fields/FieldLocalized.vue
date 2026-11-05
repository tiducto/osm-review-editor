<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import Icon from '../../Icon.vue'
import ComboInput from './ComboInput.vue'
import MixedHint from './MixedHint.vue'
import type { FieldDef, FieldTags, Locale, TagPatch } from '@/presets/types'
import { tr } from '@/presets/i18n'
import {
  fieldPlaceholder,
  isLanguageCode,
  LANGUAGES,
  languageName,
  readLocalized,
  renameLocalizedVariant,
  writeLocalizedName,
  writeLocalizedVariant,
  type ComboOption,
} from '@/presets/fields'

const props = withDefaults(defineProps<{ field: FieldDef; tags: FieldTags; locale: Locale; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ change: [patch: TagPatch] }>()

interface Pending {
  id: number
  lang: string
}

let seq = 0
const mainEl = ref<HTMLInputElement | null>(null)
const pending = ref<Pending[]>([])
const state = computed(() => readLocalized(props.field, props.tags))
const used = computed(() => new Set([...state.value.variants.map((v) => v.lang), ...pending.value.map((p) => p.lang)]))
const visiblePending = computed(() => pending.value.filter((p) => !state.value.variants.some((v) => v.lang === p.lang)))

function langOptions(current: string): ComboOption[] {
  return LANGUAGES.filter((l) => l.code === current || !used.value.has(l.code)).map((l) => ({
    value: l.code,
    title: languageName(l.code, props.locale),
    terms: [l.native, l.code],
  }))
}

function langCode(text: string, option: ComboOption | null): string | null {
  if (option) return option.value
  const t = text.trim()
  const byName = LANGUAGES.find((l) => languageName(l.code, props.locale).toLowerCase() === t.toLowerCase() || l.native.toLowerCase() === t.toLowerCase())
  if (byName) return byName.code
  return isLanguageCode(t) ? t : null
}

function send(patch: TagPatch) {
  if (Object.keys(patch).length) emit('change', patch)
}

function resync(el: HTMLInputElement, value: () => string) {
  void nextTick(() => {
    el.value = value()
  })
}

function commitMain(e: Event) {
  if (props.readonly) return
  const el = e.target as HTMLInputElement
  send(writeLocalizedName(props.field, props.tags, el.value))
  resync(el, () => state.value.name.value)
}

function commitVariant(lang: string, e: Event) {
  if (props.readonly) return
  const el = e.target as HTMLInputElement
  send(writeLocalizedVariant(props.field, props.tags, lang, el.value))
  resync(el, () => state.value.variants.find((v) => v.lang === lang)?.value ?? '')
}

function renameVariant(from: string, text: string, option: ComboOption | null) {
  const code = langCode(text, option)
  if (!code || code === from || used.value.has(code)) return
  send(renameLocalizedVariant(props.field, props.tags, from, code))
}

function removeVariant(lang: string) {
  send(writeLocalizedVariant(props.field, props.tags, lang, ''))
}

async function addLanguage() {
  const preferred = [props.locale, 'cs', 'en', 'de', 'pl', 'sk'].find((c) => !used.value.has(c)) ?? ''
  pending.value.push({ id: ++seq, lang: preferred })
  await nextTick()
  const els = document.querySelectorAll<HTMLInputElement>(`[data-pending-value="${seq}"]`)
  els[0]?.focus()
}

function setPendingLang(p: Pending, text: string, option: ComboOption | null) {
  const code = langCode(text, option)
  if (code && !state.value.variants.some((v) => v.lang === code)) p.lang = code
}

function commitPending(p: Pending, e: Event) {
  const el = e.target as HTMLInputElement
  if (!p.lang || !el.value.trim()) return
  send(writeLocalizedVariant(props.field, props.tags, p.lang, el.value))
  pending.value = pending.value.filter((x) => x.id !== p.id)
}

function dropPending(p: Pending) {
  pending.value = pending.value.filter((x) => x.id !== p.id)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
}

defineExpose({ focus: () => mainEl.value?.focus() })
</script>

<template>
  <div class="min-w-0">
    <input
      ref="mainEl"
      class="input"
      :value="state.name.value"
      :placeholder="fieldPlaceholder(field, locale, state.name.mixed)"
      :title="state.name.mixed?.join('\n')"
      :readonly="readonly"
      spellcheck="false"
      autocomplete="off"
      @change="commitMain"
      @keydown="onKey"
    />
    <MixedHint :values="state.name.mixed" />
    <div v-if="state.variants.length || visiblePending.length" class="mt-1.5 flex flex-col gap-1 border-l-2 border-line-strong pl-1.5 [&_.input]:rounded-md!">
      <div v-for="v in state.variants" :key="v.key" class="group grid grid-cols-[6.5rem_minmax(0,1fr)_20px] items-center gap-1">
        <ComboInput
          :model-value="languageName(v.lang, locale)"
          :options="langOptions(v.lang)"
          :title="`${field.key ?? 'name'}:${v.lang}`"
          :readonly="readonly"
          :placeholder="tr(locale, 'field.language')"
          input-class="text-[12px] text-fg-2"
          @commit="(text, option) => renameVariant(v.lang, text, option)"
        />
        <input
          class="input"
          :value="v.value"
          :placeholder="v.mixed ? tr(locale, 'field.multiple') : ''"
          :title="v.mixed?.join('\n')"
          :readonly="readonly"
          :lang="v.lang"
          spellcheck="false"
          autocomplete="off"
          @change="commitVariant(v.lang, $event)"
          @keydown="onKey"
        />
        <button
          v-if="!readonly"
          type="button"
          class="grid h-5 w-5 place-items-center rounded-sm text-fg opacity-50 hover:bg-surface-3 hover:text-removed hover:opacity-100"
          :title="tr(locale, 'field.remove')"
          @click="removeVariant(v.lang)"
        >
          <Icon name="x" :size="11" />
        </button>
      </div>
      <div v-for="p in visiblePending" :key="`p-${p.id}`" class="grid grid-cols-[6.5rem_minmax(0,1fr)_20px] items-center gap-1">
        <ComboInput
          :model-value="p.lang ? languageName(p.lang, locale) : ''"
          :options="langOptions(p.lang)"
          :placeholder="tr(locale, 'field.languageCode')"
          input-class="text-[12px] text-fg-2"
          @commit="(text, option) => setPendingLang(p, text, option)"
        />
        <input
          class="input"
          :data-pending-value="p.id"
          :lang="p.lang || undefined"
          spellcheck="false"
          autocomplete="off"
          @change="commitPending(p, $event)"
          @keydown="onKey"
        />
        <button type="button" class="grid h-5 w-5 place-items-center rounded-sm text-fg opacity-50 hover:bg-surface-3 hover:text-removed hover:opacity-100" :title="tr(locale, 'field.remove')" @click="dropPending(p)">
          <Icon name="x" :size="11" />
        </button>
      </div>
    </div>
    <button v-if="!readonly" type="button" class="btn btn-ghost btn-sm mt-1 -ml-1.5 text-accent" @click="addLanguage">
      <Icon name="plus" :size="12" />
      {{ tr(locale, 'field.addLanguage') }}
    </button>
  </div>
</template>
