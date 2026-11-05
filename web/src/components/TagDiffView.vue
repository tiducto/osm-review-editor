<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TagDiff } from '@/generated/models'
import { useI18n } from '@/i18n'
import Icon from './Icon.vue'

const { t, tn } = useI18n()

const props = defineProps<{ diff: TagDiff; base: Record<string, string> }>()
const showUnchanged = ref(false)

interface Line {
  key: string
  kind: 'added' | 'removed' | 'changed'
  old?: string
  value?: string
}

const lines = computed<Line[]>(() => {
  const out: Line[] = []
  for (const [k, v] of Object.entries(props.diff.changed)) out.push({ key: k, kind: 'changed', old: v[0], value: v[1] })
  for (const [k, v] of Object.entries(props.diff.added)) out.push({ key: k, kind: 'added', value: v })
  for (const [k, v] of Object.entries(props.diff.removed)) out.push({ key: k, kind: 'removed', old: v })
  return out.sort((a, b) => a.key.localeCompare(b.key))
})

const sign = { added: '+', removed: '−', changed: '~' }
</script>

<template>
  <div class="overflow-hidden rounded-md border border-line-strong bg-surface font-mono text-[12px]">
    <div v-if="!lines.length" class="px-2.5 py-2 font-sans text-fg-3">{{ t('diff.none') }}</div>
    <div
      v-for="l in lines"
      :key="l.key"
      class="grid grid-cols-[18px_minmax(0,2fr)_minmax(0,3fr)] items-stretch border-b border-l-4 border-b-line-strong last:border-b-0"
      :class="{ 'border-l-added bg-added-soft': l.kind === 'added', 'border-l-removed bg-removed-soft': l.kind === 'removed', 'border-l-changed bg-changed-soft': l.kind === 'changed' }"
    >
      <span :class="{ 'text-added': l.kind === 'added', 'text-removed': l.kind === 'removed', 'text-changed': l.kind === 'changed' }" class="py-1 text-center font-bold">
        {{ sign[l.kind] }}
      </span>
      <span class="truncate border-r border-line-strong py-1 pr-2 font-bold" :class="l.kind === 'removed' ? 'text-removed line-through' : 'text-fg'" :title="l.key">{{ l.key }}</span>
      <span class="min-w-0 bg-surface/60 px-2 py-1 break-words">
        <template v-if="l.kind === 'changed'">
          <span class="text-removed line-through decoration-1">{{ l.old }}</span>
          <span class="px-1 text-fg-3">→</span>
          <span class="text-added">{{ l.value }}</span>
        </template>
        <span v-else-if="l.kind === 'added'" class="text-added">{{ l.value }}</span>
        <span v-else class="text-removed line-through decoration-1">{{ l.old }}</span>
      </span>
    </div>
    <button
      v-if="diff.unchanged.length"
      class="flex w-full items-center gap-1.5 border-t border-line-strong bg-surface-2 px-2.5 py-1 text-left font-sans text-[11.5px] font-bold text-fg-2 hover:bg-surface-3 hover:text-fg"
      :class="!lines.length && 'border-t-0'"
      @click="showUnchanged = !showUnchanged"
    >
      <Icon :name="showUnchanged ? 'chevron-down' : 'chevron-right'" :size="12" />
      {{ tn('diff.unchanged', diff.unchanged.length) }}
    </button>
    <template v-if="showUnchanged">
      <div
        v-for="k in diff.unchanged"
        :key="`u-${k}`"
        class="grid grid-cols-[18px_minmax(0,2fr)_minmax(0,3fr)] border-t border-l-4 border-t-line-strong border-l-transparent text-fg-2"
      >
        <span class="bg-surface-2" />
        <span class="truncate border-r border-line-strong bg-surface-2 py-1 pr-2 font-bold" :title="k">{{ k }}</span>
        <span class="min-w-0 px-2 py-1 break-words">{{ base[k] }}</span>
      </div>
    </template>
  </div>
</template>
