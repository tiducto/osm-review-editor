<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { useMapStore } from '@/stores/map'
import { useRulesStore } from '@/stores/rules'
import { useDraftsStore } from '@/stores/drafts'
import { connectEvents } from '@/stores/events'
import { hasModifier, isTyping } from '@/lib/keyboard'
import MapView from '@/map/MapView.vue'
import TopBar from '@/components/TopBar.vue'
import RulesPanel from '@/components/RulesPanel.vue'
import Inspector from '@/components/Inspector.vue'
import ReviewCard from '@/components/ReviewCard.vue'
import BottomDrawer from '@/components/BottomDrawer.vue'
import MergeDialog from '@/components/MergeDialog.vue'
import SettingsDialog from '@/components/SettingsDialog.vue'
import Toasts from '@/components/Toasts.vue'
import Icon from '@/components/Icon.vue'

const app = useAppStore()
const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()

const topBar = ref<InstanceType<typeof TopBar> | null>(null)
const leftOpen = ref(true)
const rightCollapsed = ref(false)

const rightContent = computed<'review' | 'inspector' | null>(() =>
  drafts.reviewing && drafts.current ? 'review' : mapStore.selected ? 'inspector' : null,
)
const rightOpen = computed(() => !!rightContent.value && !rightCollapsed.value)

function onKey(e: KeyboardEvent) {
  if (isTyping(e) || hasModifier(e) || app.settingsOpen || app.mergeDraftId) return
  if (e.key === '/') {
    e.preventDefault()
    topBar.value?.focusSearch()
  } else if (e.key === '[') {
    leftOpen.value = !leftOpen.value
  } else if (e.key === ']') {
    rightCollapsed.value = !rightCollapsed.value
  } else if (e.key === 'Escape' && !drafts.reviewing && mapStore.selected) {
    void mapStore.select(null)
    mapStore.highlighted = []
  }
}

let stopEvents: (() => void) | null = null

watch(
  () => drafts.current?.draft.id ?? null,
  (id) => {
    const url = new URL(location.href)
    if (id) url.searchParams.set('draft', id)
    else url.searchParams.delete('draft')
    history.replaceState(history.state, '', url)
  },
)

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  stopEvents = connectEvents()
  await app.init()
  await rules.load()
  void rules.refresh()
  void drafts.loadDrafts()
  const linked = new URLSearchParams(location.search).get('draft')
  if (linked) void drafts.open(linked, { review: true })
  if (document.referrer.includes('/auth/')) void app.loadAccount()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  stopEvents?.()
})
</script>

<template>
  <div class="flex h-full flex-col">
    <TopBar ref="topBar" />

    <div v-if="app.serverError" class="flex shrink-0 items-center gap-2 border-b border-error/30 bg-removed-soft px-4 py-1.5 text-[12.5px] text-removed">
      <Icon name="alert" :size="14" /> Cannot reach the server: {{ app.serverError }}
      <button class="btn btn-sm ml-auto" @click="app.init()">Retry</button>
    </div>
    <div
      v-else-if="app.mirror && !app.mirror.available"
      class="flex shrink-0 items-center gap-2 border-b border-changed/30 bg-changed-soft px-4 py-1.5 text-[12.5px] text-changed"
    >
      <Icon name="database" :size="14" /> No local mirror is open yet{{ app.mirror.message ? `: ${app.mirror.message}` : '' }}. Rules and objects
      appear once it is ready.
    </div>

    <div class="flex min-h-0 flex-1">
      <aside
        class="shrink-0 overflow-hidden border-r border-line bg-surface transition-[width] duration-200"
        :class="leftOpen ? 'w-[296px]' : 'w-0 border-r-0'"
      >
        <div class="h-full w-[296px]"><RulesPanel /></div>
      </aside>

      <main class="flex min-w-0 flex-1 flex-col">
        <div class="relative min-h-0 flex-1">
          <MapView />
          <button
            class="absolute top-1/2 left-0 z-10 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-r-md border border-l-0 border-line bg-surface text-fg-3 shadow-panel hover:text-fg"
            :title="leftOpen ? 'Hide layers ([)' : 'Show layers ([)'"
            @click="leftOpen = !leftOpen"
          >
            <Icon :name="leftOpen ? 'chevron-left' : 'chevron-right'" :size="12" />
          </button>
          <button
            v-if="rightContent"
            class="absolute top-1/2 right-0 z-10 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-l-md border border-r-0 border-line bg-surface text-fg-3 shadow-panel hover:text-fg"
            :title="rightOpen ? 'Hide panel (])' : 'Show panel (])'"
            @click="rightCollapsed = !rightCollapsed"
          >
            <Icon :name="rightOpen ? 'chevron-right' : 'chevron-left'" :size="12" />
          </button>
        </div>
        <BottomDrawer />
      </main>

      <aside
        class="shrink-0 overflow-hidden border-l border-line bg-surface transition-[width] duration-200"
        :class="rightOpen ? 'w-[392px]' : 'w-0 border-l-0'"
      >
        <div class="h-full w-[392px]">
          <ReviewCard v-if="rightContent === 'review'" />
          <Inspector v-else-if="rightContent === 'inspector'" />
        </div>
      </aside>
    </div>

    <SettingsDialog v-if="app.settingsOpen" />
    <MergeDialog v-if="app.mergeDraftId" :draft-id="app.mergeDraftId" />
    <Toasts />
  </div>
</template>
