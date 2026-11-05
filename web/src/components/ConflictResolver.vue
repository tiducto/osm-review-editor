<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Change } from '@/generated/models'
import { useDraftsStore } from '@/stores/drafts'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import { useI18n, type MessageKey } from '@/i18n'

const { t, tn } = useI18n()

type Side = 'base' | 'live' | 'proposed'

const props = defineProps<{ change: Change }>()
const drafts = useDraftsStore()

const live = computed(() => props.change.liveTags)

interface Row {
  key: string
  base?: string
  live?: string
  proposed?: string
  conflict: boolean
}

const rows = computed<Row[]>(() => {
  const l = live.value ?? {}
  const keys = new Set([...Object.keys(props.change.baseTags), ...Object.keys(l), ...Object.keys(props.change.newTags)])
  return [...keys]
    .sort()
    .map((key) => {
      const base = props.change.baseTags[key]
      const lv = l[key]
      const proposed = props.change.newTags[key]
      return { key, base, live: lv, proposed, conflict: lv !== base && proposed !== base && lv !== proposed }
    })
    .filter((r) => !(r.base === r.live && r.live === r.proposed))
})
const unchangedCount = computed(() => {
  const l = live.value ?? {}
  return Object.keys(props.change.baseTags).filter((k) => props.change.baseTags[k] === l[k] && l[k] === props.change.newTags[k]).length
})

const picks = ref<Record<string, Side>>({})

function defaults() {
  const out: Record<string, Side> = {}
  for (const r of rows.value) {
    if (r.proposed === r.base) out[r.key] = 'live'
    else if (r.live === r.base) out[r.key] = 'proposed'
    else out[r.key] = r.conflict ? 'live' : 'proposed'
  }
  picks.value = out
}
watch(() => props.change.id, defaults, { immediate: true })
watch(rows, () => {
  for (const r of rows.value) if (!picks.value[r.key]) defaults()
})

function value(r: Row, s: Side): string | undefined {
  return s === 'base' ? r.base : s === 'live' ? r.live : r.proposed
}

const result = computed(() => {
  const out: Record<string, string> = {}
  const l = live.value ?? {}
  for (const [k, v] of Object.entries(l)) if (!rows.value.some((r) => r.key === k)) out[k] = v
  for (const r of rows.value) {
    const v = value(r, picks.value[r.key] ?? 'live')
    if (v !== undefined) out[r.key] = v
  }
  return out
})

const sides: { id: Side; label: MessageKey }[] = [
  { id: 'base', label: 'conflict.base' },
  { id: 'live', label: 'conflict.live' },
  { id: 'proposed', label: 'conflict.proposed' },
]
</script>

<template>
  <div class="space-y-2.5">
    <div class="flex items-start gap-2 rounded-md border border-line-strong border-l-4 border-l-changed bg-changed-soft p-2.5 text-[12px] text-changed">
      <Icon name="conflict" :size="14" class="mt-0.5" />
      <div>
        <div class="font-bold">{{ t('conflict.title') }}</div>
        <div class="text-fg-2">{{ t('conflict.versions', { base: change.baseVersion ?? '?', live: change.liveVersion ?? '?' }) }}</div>
      </div>
    </div>

    <div v-if="!live" class="flex items-center gap-2 text-[12px] text-fg-3">
      {{ t('conflict.liveUnknown') }}
      <button class="btn btn-sm" :disabled="drafts.busy" @click="drafts.refreshLive()"><Icon name="refresh" :size="12" /> {{ t('conflict.fetchLive') }}</button>
    </div>

    <div v-else class="overflow-hidden rounded-md border border-line-strong bg-surface text-[12px]">
      <div class="grid grid-cols-[minmax(0,1.2fr)_repeat(3,minmax(0,1fr))] border-b border-line-strong bg-surface-2 text-[11.5px] font-bold text-fg-2">
        <div class="px-2 py-1">{{ t('conflict.key') }}</div>
        <div v-for="s in sides" :key="s.id" class="px-2 py-1">{{ t(s.label) }}</div>
      </div>
      <div
        v-for="r in rows"
        :key="r.key"
        class="grid grid-cols-[minmax(0,1.2fr)_repeat(3,minmax(0,1fr))] border-b border-line-strong font-mono last:border-b-0"
      >
        <div class="flex min-w-0 items-center gap-1 bg-surface-2 px-2 py-1 font-bold text-fg">
          <span v-if="r.conflict" class="size-1.5 shrink-0 rounded-full bg-changed" :title="t('conflict.bothChanged')" />
          <span class="truncate" :title="r.key">{{ r.key }}</span>
        </div>
        <button
          v-for="s in sides"
          :key="s.id"
          class="min-w-0 border-l border-line-strong px-2 py-1 text-left break-words transition-colors"
          :class="picks[r.key] === s.id ? 'bg-accent-soft text-fg shadow-[inset_0_0_0_1px_var(--accent)]' : 'text-fg-3 hover:bg-surface-3'"
          :title="value(r, s.id) === undefined ? t('conflict.absentTitle') : value(r, s.id)"
          @click="picks[r.key] = s.id"
        >
          <span v-if="value(r, s.id) === undefined" class="italic opacity-70">{{ t('conflict.absent') }}</span>
          <template v-else>{{ value(r, s.id) }}</template>
        </button>
      </div>
      <div v-if="!rows.length" class="px-2 py-2 font-sans text-fg-3">{{ t('conflict.noDiff') }}</div>
      <div v-if="unchangedCount" class="border-t border-line-strong bg-surface-2 px-2 py-1 text-[11px] text-fg-3">
        {{ tn('conflict.identical', unchangedCount) }}
      </div>
    </div>

    <div v-if="live" class="flex items-center gap-2">
      <button class="btn btn-primary" :disabled="drafts.busy" @click="drafts.resolve(result)">
        <Spinner v-if="drafts.busy" :size="12" /> {{ t('conflict.resolve') }}
      </button>
      <button class="btn btn-ghost" :disabled="drafts.busy" :title="t('conflict.refreshTitle')" @click="drafts.refreshLive()">
        <Icon name="refresh" :size="13" /> {{ t('common.refresh') }}
      </button>
    </div>
  </div>
</template>
