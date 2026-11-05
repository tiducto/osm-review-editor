<script setup lang="ts">
import { computed } from 'vue'
import { useDraftsStore, type DrawerTab } from '@/stores/drafts'
import DraftList from './DraftList.vue'
import ChangeQueue from './ChangeQueue.vue'
import Icon from './Icon.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const drafts = useDraftsStore()

const tabs = computed<{ id: DrawerTab; label: string; count: number | null }[]>(() => [
  { id: 'drafts', label: t('drawer.drafts'), count: drafts.drafts.length },
  { id: 'changes', label: drafts.current ? t('drawer.changesOf', { title: drafts.current.draft.title }) : t('drawer.changes'), count: drafts.current ? drafts.changes.length : null },
])

function selectTab(id: DrawerTab) {
  drafts.drawerTab = id
  drafts.drawerOpen = true
}
</script>

<template>
  <section class="flex shrink-0 flex-col border-t border-line-strong bg-surface transition-[height] duration-200" :class="drafts.drawerOpen ? 'h-[260px]' : 'h-9'">
    <div class="flex h-9 shrink-0 items-end gap-0 border-b border-line-strong bg-surface-2 px-2" :class="!drafts.drawerOpen && 'border-b-0'">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        class="-mb-px flex h-8 max-w-[50%] items-center gap-1.5 rounded-t-md border border-b-0 px-3 text-[12px] font-bold transition-colors"
        :class="drafts.drawerTab === tab.id && drafts.drawerOpen ? 'border-line-strong bg-surface text-fg' : 'border-transparent text-fg-2 hover:bg-surface-3 hover:text-fg'"
        @click="selectTab(tab.id)"
      >
        <span class="truncate">{{ tab.label }}</span>
        <span v-if="tab.count !== null" class="chip h-4 bg-surface-3 px-1 font-mono text-[10.5px] text-fg-2">{{ tab.count }}</span>
      </button>
      <button
        class="btn btn-ghost btn-icon btn-sm mb-1.5 ml-auto w-6"
        :title="drafts.drawerOpen ? t('drawer.collapse') : t('drawer.expand')"
        @click="drafts.drawerOpen = !drafts.drawerOpen"
      >
        <Icon :name="drafts.drawerOpen ? 'chevron-down' : 'chevron-up'" :size="14" />
      </button>
    </div>
    <div v-if="drafts.drawerOpen" class="min-h-0 flex-1">
      <DraftList v-if="drafts.drawerTab === 'drafts'" />
      <ChangeQueue v-else />
    </div>
  </section>
</template>
