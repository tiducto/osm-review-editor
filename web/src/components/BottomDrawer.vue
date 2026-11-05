<script setup lang="ts">
import { computed } from 'vue'
import { useDraftsStore, type DrawerTab } from '@/stores/drafts'
import DraftList from './DraftList.vue'
import ChangeQueue from './ChangeQueue.vue'
import Icon from './Icon.vue'

const drafts = useDraftsStore()

const tabs = computed<{ id: DrawerTab; label: string; count: number | null }[]>(() => [
  { id: 'drafts', label: 'Drafts', count: drafts.drafts.length },
  { id: 'changes', label: drafts.current ? `Changes · ${drafts.current.draft.title}` : 'Changes', count: drafts.current ? drafts.changes.length : null },
])

function selectTab(id: DrawerTab) {
  drafts.drawerTab = id
  drafts.drawerOpen = true
}
</script>

<template>
  <section class="flex shrink-0 flex-col border-t border-line bg-surface transition-[height] duration-200" :class="drafts.drawerOpen ? 'h-[260px]' : 'h-9'">
    <div class="flex h-9 shrink-0 items-center gap-1 border-b border-line px-2" :class="!drafts.drawerOpen && 'border-b-0'">
      <button
        v-for="t in tabs"
        :key="t.id"
        class="flex max-w-[50%] items-center gap-1.5 rounded-md px-2.5 py-1 text-[12.5px] font-medium transition-colors"
        :class="drafts.drawerTab === t.id && drafts.drawerOpen ? 'bg-surface-3 text-fg' : 'text-fg-3 hover:text-fg-2'"
        @click="selectTab(t.id)"
      >
        <span class="truncate">{{ t.label }}</span>
        <span v-if="t.count !== null" class="rounded-full bg-surface-3 px-1.5 font-mono text-[10.5px] text-fg-2">{{ t.count }}</span>
      </button>
      <button
        class="btn btn-ghost btn-icon btn-sm ml-auto w-6"
        :title="drafts.drawerOpen ? 'Collapse' : 'Expand'"
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
