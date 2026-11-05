<script setup lang="ts">
import { computed } from 'vue'
import { useDraftsStore, type DrawerTab } from '@/stores/drafts'
import DraftList from '../DraftList.vue'
import ChangeQueue from '../ChangeQueue.vue'
import { useI18n } from '@/i18n'

const { t } = useI18n()
const drafts = useDraftsStore()

const tabs = computed<{ id: DrawerTab; label: string; count: number | null }[]>(() => [
  { id: 'drafts', label: t('drawer.drafts'), count: drafts.drafts.length },
  { id: 'changes', label: t('drawer.changes'), count: drafts.current ? drafts.changes.length : null },
])
</script>

<template>
  <div class="flex h-full flex-col bg-surface-2">
    <div class="flex shrink-0 items-center gap-2 border-b border-line-strong bg-surface px-3 py-2">
      <div class="seg w-full" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          role="tab"
          class="seg-btn h-7 min-w-0 flex-1"
          :aria-selected="drafts.drawerTab === tab.id"
          :disabled="tab.id === 'changes' && !drafts.current"
          :data-testid="`drafts-tab-${tab.id}`"
          @click="drafts.drawerTab = tab.id"
        >
          <span class="truncate">{{ tab.label }}</span>
          <span v-if="tab.count !== null" class="font-mono text-[10.5px] font-normal opacity-80">{{ tab.count }}</span>
        </button>
      </div>
    </div>
    <div v-if="drafts.drawerTab === 'changes' && drafts.current" class="shrink-0 truncate border-b border-line bg-surface px-3 py-1.5 text-[12px] font-bold" :title="drafts.current.draft.title">
      {{ drafts.current.draft.title }}
    </div>
    <div class="min-h-0 flex-1">
      <ChangeQueue v-if="drafts.drawerTab === 'changes'" />
      <DraftList v-else />
    </div>
  </div>
</template>
