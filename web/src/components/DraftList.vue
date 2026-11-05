<script setup lang="ts">
import type { Draft, DraftStatus } from '@/generated/models'
import { useDraftsStore } from '@/stores/drafts'
import { useAppStore } from '@/stores/app'
import type { AuthorKind } from '@/lib/authors'
import { relativeTime } from '@/lib/format'
import AuthorBadge from './AuthorBadge.vue'
import StatusPill from './StatusPill.vue'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import { useI18n, type MessageKey } from '@/i18n'

const { t } = useI18n()
const drafts = useDraftsStore()
const app = useAppStore()

const statuses: { id: DraftStatus | ''; label: MessageKey }[] = [
  { id: 'open', label: 'drafts.status.open' },
  { id: 'partially_merged', label: 'drafts.status.partiallyMerged' },
  { id: 'merged', label: 'drafts.status.merged' },
  { id: 'closed', label: 'drafts.status.closed' },
  { id: '', label: 'common.all' },
]
const authors: { id: AuthorKind | ''; label: MessageKey }[] = [
  { id: '', label: 'drafts.author.anyone' },
  { id: 'user', label: 'drafts.author.me' },
  { id: 'claude', label: 'drafts.author.claude' },
  { id: 'rule', label: 'drafts.author.rules' },
  { id: 'remote', label: 'drafts.author.remote' },
]

function isCurrent(d: Draft) {
  return drafts.current?.draft.id === d.id
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex shrink-0 items-center gap-2 px-3 py-1.5">
      <div class="flex rounded-md bg-surface-3 p-0.5">
        <button
          v-for="s in statuses"
          :key="s.id"
          class="rounded px-2 py-0.5 text-[11.5px] font-medium transition-colors"
          :class="drafts.statusFilter === s.id ? 'bg-surface text-fg shadow-sm' : 'text-fg-3 hover:text-fg-2'"
          @click="drafts.statusFilter = s.id"
        >
          {{ t(s.label) }}
        </button>
      </div>
      <select v-model="drafts.authorFilter" class="input h-6 w-auto py-0 text-[11.5px]">
        <option v-for="a in authors" :key="a.id" :value="a.id">{{ t(a.label) }}</option>
      </select>
      <Spinner v-if="drafts.listLoading" :size="12" class="text-fg-3" />
      <button class="btn btn-ghost btn-sm ml-auto text-fg-3" :title="t('common.reload')" @click="drafts.loadDrafts()"><Icon name="refresh" :size="12" /></button>
    </div>

    <div class="scroll-thin min-h-0 flex-1 overflow-auto">
      <table class="w-full border-collapse text-[12.5px]">
        <thead class="sticky top-0 z-[1] bg-surface text-left text-[11px] text-fg-3">
          <tr class="border-b border-line">
            <th class="w-[38%] px-3 py-1 font-medium">{{ t('drafts.col.draft') }}</th>
            <th class="px-2 py-1 font-medium">{{ t('drafts.col.author') }}</th>
            <th class="px-2 py-1 font-medium">{{ t('drafts.col.status') }}</th>
            <th class="px-2 py-1 font-medium">{{ t('drafts.col.changes') }}</th>
            <th class="px-2 py-1 font-medium">{{ t('drafts.col.updated') }}</th>
            <th class="w-1 px-3 py-1" />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="d in drafts.visibleDrafts"
            :key="d.id"
            class="group cursor-pointer border-b border-line"
            :class="isCurrent(d) ? 'bg-accent-soft' : 'hover:bg-surface-2'"
            @click="drafts.open(d.id)"
          >
            <td class="max-w-0 px-3 py-1.5">
              <div class="truncate font-medium" :title="d.title">{{ d.title }}</div>
              <div v-if="d.description" class="truncate text-[11.5px] text-fg-3" :title="d.description">{{ d.description }}</div>
            </td>
            <td class="px-2 py-1.5"><AuthorBadge :author="d.author" /></td>
            <td class="px-2 py-1.5"><StatusPill :status="d.status" /></td>
            <td class="px-2 py-1.5 font-mono text-[11.5px] whitespace-nowrap tabular-nums">
              <span class="text-fg">{{ d.counts.total }}</span>
              <span class="ml-2 text-fg-3" :title="t('drafts.count.pending')">{{ d.counts.pending }}○</span>
              <span class="ml-1.5 text-added" :title="t('drafts.count.approved')">{{ d.counts.approved }}✓</span>
              <span class="ml-1.5 text-removed" :title="t('drafts.count.rejected')">{{ d.counts.rejected }}✕</span>
              <span v-if="d.counts.conflict" class="ml-1.5 text-changed" :title="t('drafts.count.conflicts')">{{ d.counts.conflict }}!</span>
              <span v-if="d.counts.uploaded" class="ml-1.5 text-accent" :title="t('drafts.count.uploaded')">{{ d.counts.uploaded }}↑</span>
            </td>
            <td class="px-2 py-1.5 whitespace-nowrap text-fg-3" :title="d.updatedAt">{{ relativeTime(d.updatedAt) }}</td>
            <td class="px-3 py-1.5 whitespace-nowrap" @click.stop>
              <div class="flex justify-end gap-1 opacity-60 group-hover:opacity-100">
                <button class="btn btn-sm" :disabled="!d.counts.total" @click="drafts.open(d.id, { review: true })">{{ t('drafts.review') }}</button>
                <button
                  class="btn btn-sm"
                  :disabled="!d.counts.approved || d.status === 'merged'"
                  :title="t('drafts.mergeTitle')"
                  @click="app.mergeDraftId = d.id"
                >
                  {{ t('drafts.merge') }}
                </button>
                <button v-if="d.status === 'open'" class="btn btn-ghost btn-sm" :title="t('drafts.closeTitle')" @click="drafts.setStatus(d.id, 'closed')">
                  {{ t('drafts.close') }}
                </button>
                <button v-else-if="d.status === 'closed'" class="btn btn-ghost btn-sm" @click="drafts.setStatus(d.id, 'open')">{{ t('drafts.reopen') }}</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!drafts.visibleDrafts.length && !drafts.listLoading" class="px-3 py-6 text-center text-fg-3">
        {{ t('drafts.empty') }}
      </div>
    </div>
  </div>
</template>
