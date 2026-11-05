<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SearchHit } from '@/generated/models'
import { api, errorMessage, isAbort } from '@/api/client'
import { useMapStore } from '@/stores/map'
import { useRulesStore } from '@/stores/rules'
import { useDraftsStore } from '@/stores/drafts'
import { refString } from '@/lib/osm'
import { plural } from '@/lib/format'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import { useI18n } from '@/i18n'

const { t, tn } = useI18n()
const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()

const q = ref('')
const hits = ref<SearchHit[]>([])
const active = ref(0)
const open = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
const input = ref<HTMLInputElement | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null
let ctrl: AbortController | null = null

watch(q, (v) => {
  if (timer) clearTimeout(timer)
  error.value = null
  if (!v.trim()) {
    hits.value = []
    loading.value = false
    return
  }
  loading.value = true
  timer = setTimeout(() => void run(v), 250)
})

async function run(v: string) {
  ctrl?.abort()
  const c = new AbortController()
  ctrl = c
  try {
    hits.value = await api.search(v.trim(), mapStore.bbox, c.signal)
    active.value = 0
    open.value = true
  } catch (e) {
    if (!isAbort(e)) {
      hits.value = []
      error.value = errorMessage(e)
    }
  } finally {
    if (ctrl === c) loading.value = false
  }
}

function pick(h: SearchHit) {
  open.value = false
  input.value?.blur()
  if (h.kind !== 'area' && drafts.reviewing) drafts.stopReview()
  switch (h.kind) {
    case 'area':
      void mapStore.setArea(h.label)
      break
    case 'object':
      mapStore.highlighted = []
      if (h.ref) void mapStore.select(h.ref, { fly: !h.bbox && !h.center })
      if (h.bbox) mapStore.fitBBox(h.bbox, 18)
      else if (h.center) mapStore.flyTo(h.center, 18)
      break
    case 'query':
      mapStore.highlighted = h.refs
      if (h.bbox) mapStore.fitBBox(h.bbox, 18)
      if (h.refs.length === 1) void mapStore.select(h.refs[0])
      break
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    open.value = true
    active.value = Math.min(active.value + 1, hits.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(active.value - 1, 0)
  } else if (e.key === 'Enter') {
    const h = hits.value[active.value]
    if (h) pick(h)
  } else if (e.key === 'Escape') {
    if (q.value) q.value = ''
    else input.value?.blur()
    open.value = false
  }
}

const kindIcon = { area: 'pin', object: 'target', query: 'filter' } as const
const showPanel = computed(() => open.value && !!q.value.trim() && (hits.value.length > 0 || !!error.value || !loading.value))

function clearHighlight() {
  mapStore.highlighted = []
}

defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <div class="relative w-full max-w-[440px] min-w-[110px] flex-1 sm:min-w-[180px]">
    <div class="relative">
      <Icon name="search" :size="16" class="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-fg-2" />
      <input
        id="search-input"
        ref="input"
        v-model="q"
        class="input h-8 border-transparent pr-16 pl-8"
        :placeholder="t('search.placeholder')"
        autocomplete="off"
        spellcheck="false"
        @focus="open = true"
        @blur="open = false"
        @keydown="onKey"
      />
      <div class="absolute top-1/2 right-2 flex -translate-y-1/2 items-center gap-1.5">
        <Spinner v-if="loading" :size="12" class="text-fg-3" />
        <span v-else-if="!q" class="kbd">/</span>
        <button v-else class="text-fg-3 hover:text-fg" :title="t('common.clear')" @mousedown.prevent="q = ''"><Icon name="x" :size="14" /></button>
      </div>
    </div>

    <Transition name="pop">
      <div
        v-if="showPanel"
        class="absolute top-full right-0 left-0 z-40 mt-1.5 overflow-hidden rounded-md border border-line-strong bg-surface text-fg shadow-pop max-sm:fixed max-sm:inset-x-2 max-sm:top-12"
        @mousedown.prevent
      >
        <div v-if="error" class="flex items-center gap-2 px-3 py-2.5 text-[12.5px] text-error"><Icon name="alert" :size="14" />{{ error }}</div>
        <div v-else-if="!hits.length" class="px-3 py-2.5 text-[12.5px] text-fg-3">{{ t('search.noResults') }}</div>
        <ul v-else class="scroll-thin max-h-[360px] overflow-y-auto py-1">
          <li v-for="(h, i) in hits" :key="i">
            <button
              class="flex w-full items-center gap-2.5 px-3 py-1.5 text-left"
              :class="i === active ? 'bg-surface-3' : 'hover:bg-surface-3'"
              @mouseenter="active = i"
              @click="pick(h)"
            >
              <Icon :name="kindIcon[h.kind]" :size="14" class="text-fg-3" />
              <span class="min-w-0 flex-1">
                <span class="block truncate font-bold">{{ h.label }}</span>
                <span v-if="h.detail || h.ref" class="block truncate text-[11.5px] text-fg-3">
                  <span v-if="h.ref" class="font-mono">{{ refString(h.ref) }}</span>
                  <span v-if="h.ref && h.detail"> · </span>{{ h.detail }}
                </span>
              </span>
              <span v-if="h.kind === 'query'" class="chip bg-surface-3 text-fg-2">{{ plural(h.refs.length, 'common.matches') }}</span>
              <span v-else-if="h.kind === 'area'" class="chip bg-surface-3 text-fg-2">{{ t('search.area') }}</span>
            </button>
          </li>
        </ul>
        <div class="border-t border-line-strong bg-surface-2 px-3 py-1.5 text-[11px] text-fg-2">
          <span class="kbd">↑</span> <span class="kbd">↓</span> {{ t('search.toMove') }} · <span class="kbd">↵</span> {{ t('search.toGo') }}
          <template v-if="rules.scope?.area"> · {{ t('search.queriesIn', { area: rules.scope.area }) }}</template>
        </div>
      </div>
    </Transition>

    <button
      v-if="mapStore.highlighted.length"
      class="chip absolute top-1/2 -right-2 translate-x-full -translate-y-1/2 bg-accent text-white"
      :title="t('search.clearHighlight')"
      @click="clearHighlight"
    >
      {{ tn('search.highlighted', mapStore.highlighted.length) }} <Icon name="x" :size="11" />
    </button>
  </div>
</template>
