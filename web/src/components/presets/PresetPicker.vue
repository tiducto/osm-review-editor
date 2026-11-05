<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { SmartPreset } from '@/generated/models'
import { usePresetsStore } from '@/stores/presets'
import { fold } from '@/presets/catalog'
import type { CategoryDef, Geometry, PresetDef } from '@/presets/types'
import PresetIcon from './PresetIcon.vue'
import { useI18n } from '@/i18n'
import Icon from '../Icon.vue'

const props = withDefaults(defineProps<{ geometries: Geometry[]; currentId?: string | null; smart?: SmartPreset[]; closable?: boolean }>(), {
  currentId: null,
  smart: () => [],
  closable: true,
})
const emit = defineEmits<{ choose: [id: string]; smart: [preset: SmartPreset]; close: [] }>()

const presets = usePresetsStore()
const { t } = useI18n()
const query = ref('')
const category = ref<CategoryDef | null>(null)
const input = ref<HTMLInputElement | null>(null)
const active = ref(0)

onMounted(async () => {
  await nextTick()
  input.value?.focus()
})

const fits = (p: PresetDef) => props.geometries.every((g) => p.geometry.includes(g))
const primary = computed<Geometry>(() => props.geometries[0] ?? 'point')

interface Group {
  key: string
  title: string
  items: ({ kind: 'preset'; p: PresetDef } | { kind: 'category'; c: CategoryDef } | { kind: 'smart'; s: SmartPreset })[]
}

const groups = computed<Group[]>(() => {
  const cat = presets.catalog
  if (!cat) return []
  const q = query.value.trim()
  const out: Group[] = []
  if (category.value) {
    out.push({
      key: 'cat',
      title: category.value.name,
      items: category.value.members.map((id) => cat.preset(id)).filter((p): p is PresetDef => !!p && fits(p)).map((p) => ({ kind: 'preset' as const, p })),
    })
    return out
  }
  const smartItems = props.smart
    .filter((s) => !q || fold(s.name).includes(fold(q)) || fold(s.id).includes(fold(q)))
    .map((s) => ({ kind: 'smart' as const, s }))
  if (q) {
    const found = cat.search(q, props.geometries, 60)
    const user = found.filter((p) => p.origin === 'user')
    const rest = found.filter((p) => p.origin !== 'user')
    if (user.length) out.push({ key: 'user', title: t('preset.user'), items: user.map((p) => ({ kind: 'preset' as const, p })) })
    if (smartItems.length) out.push({ key: 'smart', title: t('preset.smart'), items: smartItems })
    out.push({ key: 'results', title: t('preset.results'), items: rest.map((p) => ({ kind: 'preset' as const, p })) })
    return out
  }
  const user = cat.userIds.map((id) => cat.preset(id)).filter((p): p is PresetDef => !!p && fits(p))
  const recent = presets.recent.map((id) => cat.preset(id)).filter((p): p is PresetDef => !!p && fits(p) && p.origin !== 'user').slice(0, 6)
  if (user.length) out.push({ key: 'user', title: t('preset.user'), items: user.map((p) => ({ kind: 'preset' as const, p })) })
  if (smartItems.length) out.push({ key: 'smart', title: t('preset.smart'), items: smartItems })
  if (recent.length) out.push({ key: 'recent', title: t('preset.recent'), items: recent.map((p) => ({ kind: 'preset' as const, p })) })
  const defaults = cat.defaults(primary.value).filter((x) => ('members' in x ? true : fits(x)))
  out.push({
    key: 'defaults',
    title: t('preset.categories'),
    items: defaults.map((x) => ('members' in x ? { kind: 'category' as const, c: x } : { kind: 'preset' as const, p: x })),
  })
  return out
})

const flat = computed(() => groups.value.flatMap((g) => g.items))

function pick(i: Group['items'][number]) {
  if (i.kind === 'category') {
    category.value = i.c
    active.value = 0
    return
  }
  if (i.kind === 'smart') emit('smart', i.s)
  else emit('choose', i.p.id)
}

function onInput() {
  category.value = null
  active.value = 0
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = Math.min(flat.value.length - 1, active.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(0, active.value - 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const item = flat.value[active.value]
    if (item) pick(item)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    e.stopPropagation()
    if (category.value) category.value = null
    else if (props.closable) emit('close')
  }
}

function indexOf(i: Group['items'][number]) {
  return flat.value.indexOf(i)
}

function subtitle(p: PresetDef): string {
  const tags = Object.entries(p.tags)
    .map(([k, v]) => `${k}=${v}`)
    .join(' ')
  return p.origin === 'user' ? `${p.file ?? ''}` : tags
}
</script>

<template>
  <div class="flex min-h-0 flex-col" data-testid="preset-picker">
    <div class="flex items-center gap-1.5 border-b border-line px-3 py-2">
      <button v-if="category" class="btn btn-ghost btn-icon btn-sm w-6" :title="t('preset.back')" @click="category = null"><Icon name="chevron-left" :size="14" /></button>
      <input
        ref="input"
        v-model="query"
        class="input"
        :placeholder="t('preset.search')"
        data-testid="preset-search"
        @input="onInput"
        @keydown="onKey"
      />
      <button v-if="closable" class="btn btn-ghost btn-icon btn-sm w-6" :title="t('common.close')" :aria-label="t('common.close')" @click="emit('close')"><Icon name="x" :size="14" /></button>
    </div>
    <div class="scroll-thin min-h-0 flex-1 overflow-y-auto pb-2">
      <div v-if="!presets.catalog" class="px-4 py-6 text-center text-fg-3">{{ t('inspector.loading') }}</div>
      <template v-for="g in groups" :key="g.key">
        <div v-if="g.items.length" class="px-3 pt-2.5 pb-1 text-[10.5px] font-semibold tracking-wide text-fg-3 uppercase">{{ g.title }}</div>
        <button
          v-for="item in g.items"
          :key="item.kind === 'preset' ? item.p.id : item.kind === 'category' ? item.c.id : `smart:${item.s.id}`"
          class="flex w-full items-center gap-2.5 px-3 py-1.5 text-left hover:bg-surface-3"
          :class="[indexOf(item) === active && 'bg-surface-3', item.kind === 'preset' && item.p.id === currentId && 'font-semibold']"
          :data-preset="item.kind === 'preset' ? item.p.id : item.kind === 'smart' ? `smart:${item.s.id}` : item.c.id"
          @click="pick(item)"
          @mouseenter="active = indexOf(item)"
        >
          <PresetIcon v-if="item.kind === 'preset'" :icon="item.p.icon" :size="28" />
          <PresetIcon v-else-if="item.kind === 'category'" :icon="item.c.icon" :size="28" />
          <PresetIcon v-else :icon="null" :size="28" smart />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-[12.5px]">
              {{ item.kind === 'preset' ? item.p.name : item.kind === 'category' ? item.c.name : item.s.name }}
            </span>
            <span class="block truncate font-mono text-[10.5px] text-fg-3">
              {{ item.kind === 'preset' ? subtitle(item.p) : item.kind === 'category' ? `${item.c.members.length}` : item.s.description ?? item.s.id }}
            </span>
          </span>
          <Icon v-if="item.kind === 'category'" name="chevron-right" :size="13" class="text-fg-3" />
          <span v-if="item.kind === 'preset' && item.p.origin === 'user'" class="chip bg-accent-soft text-accent">{{ t('preset.user') }}</span>
        </button>
      </template>
      <div v-if="presets.catalog && !flat.length" class="px-4 py-6 text-center text-fg-3">{{ t('preset.noResults') }}</div>
    </div>
  </div>
</template>
