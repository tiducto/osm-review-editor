<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import type { Change, ChangeStatus } from '@/generated/models'
import { useDraftsStore } from '@/stores/drafts'
import { useRulesStore } from '@/stores/rules'
import { changeKind } from '@/lib/changes'
import { changeKindColor, changeKindLabel } from '@/lib/colors'
import { featureTitle, refString } from '@/lib/osm'
import StatusPill from './StatusPill.vue'

const drafts = useDraftsStore()
const rules = useRulesStore()
const filter = ref<ChangeStatus | ''>('')
const body = ref<HTMLElement | null>(null)

const statuses: { id: ChangeStatus | ''; label: string }[] = [
  { id: '', label: 'All' },
  { id: 'pending', label: 'Pending' },
  { id: 'approved', label: 'Approved' },
  { id: 'rejected', label: 'Rejected' },
  { id: 'conflict', label: 'Conflicts' },
  { id: 'uploaded', label: 'Uploaded' },
]

const rows = computed(() =>
  drafts.changes.map((c, i) => ({ c, i })).filter(({ c }) => !filter.value || c.status === filter.value),
)

function count(s: ChangeStatus | '') {
  return s ? drafts.changes.filter((c) => c.status === s).length : drafts.changes.length
}

interface Token {
  sign: '+' | '~' | '−'
  text: string
}
function tokens(c: Change): Token[] {
  const out: Token[] = []
  for (const [k, v] of Object.entries(c.diff.added)) out.push({ sign: '+', text: `${k}=${v}` })
  for (const [k, v] of Object.entries(c.diff.changed)) out.push({ sign: '~', text: `${k}=${v[1] ?? ''}` })
  for (const k of Object.keys(c.diff.removed)) out.push({ sign: '−', text: k })
  return out
}

function open(i: number) {
  drafts.goTo(i)
  if (!drafts.reviewing) drafts.startReview()
}

watch(
  () => drafts.index,
  async () => {
    await nextTick()
    body.value?.querySelector('[data-current="true"]')?.scrollIntoView({ block: 'nearest' })
  },
)
</script>

<template>
  <div class="flex h-full flex-col">
    <div v-if="!drafts.current" class="grid flex-1 place-items-center text-fg-3">Open a draft to see its changes.</div>
    <template v-else>
      <div class="flex shrink-0 items-center gap-2 px-3 py-1.5">
        <div class="flex rounded-md bg-surface-3 p-0.5">
          <button
            v-for="s in statuses"
            :key="s.id"
            class="rounded px-2 py-0.5 text-[11.5px] font-medium transition-colors"
            :class="filter === s.id ? 'bg-surface text-fg shadow-sm' : 'text-fg-3 hover:text-fg-2'"
            @click="filter = s.id"
          >
            {{ s.label }} <span class="font-mono text-[10.5px] opacity-70">{{ count(s.id) }}</span>
          </button>
        </div>
        <button v-if="!drafts.reviewing" class="btn btn-sm ml-auto" @click="drafts.startReview()">Start review</button>
        <button v-else class="btn btn-ghost btn-sm ml-auto" :disabled="drafts.busy" title="Refetch live versions, detect conflicts" @click="drafts.refreshLive()">
          Refresh live
        </button>
      </div>
      <div ref="body" class="scroll-thin min-h-0 flex-1 overflow-auto">
        <table class="w-full table-fixed border-collapse text-[12.5px]">
          <colgroup>
            <col class="w-10" />
            <col class="w-[26%]" />
            <col class="w-[20%]" />
            <col />
            <col class="w-24" />
            <col class="w-24" />
          </colgroup>
          <thead class="sticky top-0 z-[1] bg-surface text-left text-[11px] text-fg-3">
            <tr class="border-b border-line">
              <th class="px-3 py-1 font-medium">#</th>
              <th class="px-2 py-1 font-medium">Object</th>
              <th class="px-2 py-1 font-medium">Rule</th>
              <th class="px-2 py-1 font-medium">Change</th>
              <th class="px-2 py-1 font-medium">Kind</th>
              <th class="px-2 py-1 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="{ c, i } in rows"
              :key="c.id"
              :data-current="drafts.reviewing && i === drafts.index"
              class="cursor-pointer border-b border-line"
              :class="drafts.reviewing && i === drafts.index ? 'bg-accent-soft' : 'hover:bg-surface-2'"
              @click="open(i)"
            >
              <td class="px-3 py-1 font-mono text-[11px] text-fg-3 tabular-nums">{{ i + 1 }}</td>
              <td class="truncate px-2 py-1">
                <span class="font-medium">{{ featureTitle(c.ref, c.newTags) }}</span>
                <span class="ml-1.5 font-mono text-[11px] text-fg-3">{{ refString(c.ref) }}</span>
              </td>
              <td class="truncate px-2 py-1 text-fg-2">
                <template v-if="c.ruleId">
                  <span class="mr-1.5 inline-block size-1.5 rounded-full align-middle" :style="{ background: rules.colorOf.get(c.ruleId) ?? '#888' }" />{{
                    rules.ruleById.get(c.ruleId)?.title ?? c.ruleId
                  }}
                </template>
                <span v-else class="text-fg-3">—</span>
              </td>
              <td class="truncate px-2 py-1 font-mono text-[11.5px]">
                <span
                  v-for="(t, k) in tokens(c)"
                  :key="k"
                  class="mr-2"
                  :class="{ 'text-added': t.sign === '+', 'text-changed': t.sign === '~', 'text-removed': t.sign === '−' }"
                  >{{ t.sign }}{{ t.text }}</span
                >
                <span v-if="!tokens(c).length" class="text-fg-3">geometry only</span>
              </td>
              <td class="px-2 py-1">
                <span class="flex items-center gap-1.5 text-[11.5px] text-fg-2">
                  <span class="size-2 rounded-full" :style="{ background: changeKindColor[changeKind(c)] }" />{{ changeKindLabel[changeKind(c)] }}
                </span>
              </td>
              <td class="px-2 py-1"><StatusPill :status="c.status" /></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!rows.length" class="px-3 py-6 text-center text-fg-3">No changes match.</div>
      </div>
    </template>
  </div>
</template>
