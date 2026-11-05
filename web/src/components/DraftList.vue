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
    <div class="flex shrink-0 flex-wrap items-center gap-2 border-b border-line-strong bg-surface px-3 py-1.5">
      <div class="seg max-w-full flex-wrap">
        <button
          v-for="s in statuses"
          :key="s.id"
          class="seg-btn"
          :class="drafts.statusFilter === s.id && 'active'"
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

    <ul class="scroll-thin min-h-0 flex-1 overflow-auto bg-surface text-[12px]" data-testid="draft-list">
      <li
        v-for="d in drafts.visibleDrafts"
        :key="d.id"
        class="group cursor-pointer border-b border-line px-3 py-2"
        :class="isCurrent(d) ? 'bg-accent-soft' : 'hover:bg-surface-3'"
        :data-testid="`draft-${d.id}`"
        @click="drafts.open(d.id)"
      >
        <div class="flex min-w-0 items-center gap-2">
          <span class="min-w-0 flex-1 truncate font-bold" :title="d.title">{{ d.title }}</span>
          <StatusPill :status="d.status" />
        </div>
        <div v-if="d.description" class="truncate text-[11.5px] text-fg-3" :title="d.description">{{ d.description }}</div>
        <div class="mt-1 flex min-w-0 items-center gap-2">
          <AuthorBadge :author="d.author" />
          <span class="min-w-0 truncate font-mono text-[11.5px] whitespace-nowrap tabular-nums" :title="t('drafts.col.changes')">
            <span class="text-fg">{{ d.counts.total }}</span>
            <span class="ml-2 text-fg-3" :title="t('drafts.count.pending')">{{ d.counts.pending }}○</span>
            <span class="ml-1.5 text-added" :title="t('drafts.count.approved')">{{ d.counts.approved }}✓</span>
            <span class="ml-1.5 text-removed" :title="t('drafts.count.rejected')">{{ d.counts.rejected }}✕</span>
            <span v-if="d.counts.conflict" class="ml-1.5 text-changed" :title="t('drafts.count.conflicts')">{{ d.counts.conflict }}!</span>
            <span v-if="d.counts.uploaded" class="ml-1.5 text-accent" :title="t('drafts.count.uploaded')">{{ d.counts.uploaded }}↑</span>
          </span>
          <span class="ml-auto shrink-0 whitespace-nowrap text-[11.5px] text-fg-3" :title="d.updatedAt">{{ relativeTime(d.updatedAt) }}</span>
        </div>
        <div class="mt-1.5 flex flex-wrap gap-1" @click.stop>
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
      </li>
      <li v-if="!drafts.visibleDrafts.length && !drafts.listLoading" class="px-3 py-6 text-center text-fg-3">
        {{ t('drafts.empty') }}
      </li>
    </ul>
  </div>
</template>
