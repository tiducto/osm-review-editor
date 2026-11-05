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
import SidePanel from '@/components/SidePanel.vue'
import HighlightLegend from '@/components/HighlightLegend.vue'
import { usePanelStore } from '@/stores/panel'
import Inspector from '@/components/Inspector.vue'
import ReviewCard from '@/components/ReviewCard.vue'
import BottomDrawer from '@/components/BottomDrawer.vue'
import MergeDialog from '@/components/MergeDialog.vue'
import SettingsDialog from '@/components/SettingsDialog.vue'
import Toasts from '@/components/Toasts.vue'
import Icon from '@/components/Icon.vue'
import EditInspector from '@/components/editor/EditInspector.vue'
import EditDialogs from '@/components/editor/EditDialogs.vue'
import { useEditorStore } from '@/stores/editor'

const app = useAppStore()
const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()
const panel = usePanelStore()
const editor = useEditorStore()

const topBar = ref<InstanceType<typeof TopBar> | null>(null)
const leftOpen = ref(window.innerWidth >= 768)
const rightCollapsed = ref(false)

const rightContent = computed<'review' | 'inspector' | 'edit' | null>(() =>
  editor.active
    ? 'edit'
    : drafts.reviewing && drafts.current
      ? 'review'
      : mapStore.selected
        ? 'inspector'
        : null,
)
const rightOpen = computed(() => !!rightContent.value && !rightCollapsed.value)
const rowBox = ref<HTMLElement | null>(null)
const viewport = ref({ width: window.innerWidth, height: 0 })
const phone = computed(() => viewport.value.width < 768)
const leftWidth = computed(() => (panel.wide ? Math.min(560, viewport.value.width) : 296))

const insets = computed(() => {
  const drawer = drafts.drawerOpen ? 260 : 36
  if (phone.value) {
    const sheet = rightOpen.value ? Math.round(viewport.value.height * 0.58) : 0
    return { top: 0, left: 0, right: 0, bottom: Math.max(drawer, sheet) }
  }
  return { top: 0, left: leftOpen.value ? leftWidth.value : 0, right: rightOpen.value ? 392 : 0, bottom: drawer }
})

function syncViewport() {
  viewport.value = { width: window.innerWidth, height: rowBox.value?.clientHeight ?? 0 }
}
watch(insets, (i) => mapStore.setInsets(i), { immediate: true })
watch(
  () => drafts.reviewing,
  (r) => {
    if (r && editor.active) editor.exit()
  },
)
watch(
  () => [mapStore.selected, drafts.reviewing && drafts.current?.draft.id, editor.active],
  () => {
    if (window.innerWidth < 768) rightCollapsed.value = false
  },
)
watch(rightOpen, () => {
  if (drafts.reviewing && window.innerWidth < 768) setTimeout(() => drafts.focusCurrent(), 220)
})

function onKey(e: KeyboardEvent) {
  if (isTyping(e) || hasModifier(e) || app.settingsOpen || app.mergeDraftId) return
  if (e.key === '/') {
    e.preventDefault()
    topBar.value?.focusSearch()
  } else if (e.key === '[') {
    leftOpen.value = !leftOpen.value
  } else if (e.key === ']') {
    rightCollapsed.value = !rightCollapsed.value
  } else if (e.key === 'Escape' && !editor.active && !drafts.reviewing && mapStore.selected) {
    void mapStore.select(null)
    mapStore.highlighted = []
  }
}

let stopEvents: (() => void) | null = null

watch(
  () => (drafts.reviewing ? (drafts.current?.draft.id ?? null) : null),
  (id) => {
    const url = new URL(location.href)
    if (id) url.searchParams.set('draft', id)
    else url.searchParams.delete('draft')
    history.replaceState(history.state, '', url)
  },
)

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  window.addEventListener('resize', syncViewport)
  syncViewport()
  stopEvents = connectEvents()
  await app.init()
  await rules.load()
  void drafts.loadDrafts()
  const linked = new URLSearchParams(location.search).get('draft')
  if (linked) void drafts.open(linked, { review: true })
  if (document.referrer.includes('/auth/')) void app.loadAccount()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', syncViewport)
  stopEvents?.()
})
</script>

<template>
  <div class="flex h-full flex-col overflow-hidden">
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

    <div ref="rowBox" class="relative min-h-0 flex-1 overflow-hidden" data-testid="map-area">
      <div class="absolute inset-0">
        <MapView />
        <div
          class="pointer-events-none absolute transition-[left,right,bottom] duration-200"
          :style="{ left: `${insets.left}px`, right: `${insets.right}px`, top: `${insets.top}px`, bottom: `${insets.bottom}px` }"
        >
          <HighlightLegend />
        </div>
      </div>

      <aside
        class="absolute inset-y-0 left-0 z-20 overflow-hidden border-r border-line bg-surface transition-[width] duration-200 max-md:shadow-panel"
        :style="{ width: leftOpen ? `${leftWidth}px` : '0px' }"
        :class="!leftOpen && 'border-r-0'"
        data-testid="left-panel"
      >
        <div class="h-full max-w-[100vw]" :style="{ width: `${leftWidth}px` }"><SidePanel /></div>
      </aside>
      <button
        class="absolute top-1/2 z-30 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-r-md border border-l-0 border-line bg-surface text-fg-3 shadow-panel transition-[left] duration-200 hover:text-fg"
        :class="leftOpen && panel.wide && 'max-md:hidden'"
        :style="{ left: leftOpen ? `${leftWidth}px` : '0px' }"
        :title="leftOpen ? 'Hide layers ([)' : 'Show layers ([)'"
        data-testid="left-toggle"
        @click="leftOpen = !leftOpen"
      >
        <Icon :name="leftOpen ? 'chevron-left' : 'chevron-right'" :size="12" />
      </button>

      <div
        class="absolute bottom-0 z-10 transition-[left,right] duration-200"
        :style="{ left: `${insets.left}px`, right: `${insets.right}px` }"
        data-testid="bottom-panel"
      >
        <BottomDrawer />
      </div>

      <button
        v-if="rightContent"
        class="absolute top-1/2 z-30 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-l-md border border-r-0 border-line bg-surface text-fg-3 shadow-panel transition-[right] duration-200 hover:text-fg max-md:hidden"
        :style="{ right: `${insets.right}px` }"
        :title="rightOpen ? 'Hide panel (])' : 'Show panel (])'"
        data-testid="right-toggle"
        @click="rightCollapsed = !rightCollapsed"
      >
        <Icon :name="rightOpen ? 'chevron-right' : 'chevron-left'" :size="12" />
      </button>
      <aside
        class="absolute z-20 border-line bg-surface md:inset-y-0 md:right-0 md:overflow-hidden md:border-l md:transition-[width] md:duration-200 max-md:inset-x-0 max-md:bottom-0 max-md:border-t max-md:shadow-pop max-md:transition-[height]"
        :class="[rightOpen ? 'md:w-[392px] max-md:h-[58%]' : 'md:w-0 md:border-l-0 max-md:h-0 max-md:border-t-0', !rightContent && 'max-md:hidden']"
        data-testid="right-panel"
      >
        <button
          v-if="rightContent"
          class="absolute -top-8 right-3 flex h-8 items-center gap-1 rounded-t-lg border border-b-0 border-line bg-surface px-3 text-[12px] font-medium text-fg-2 shadow-panel md:hidden"
          data-testid="sheet-toggle"
          @click="rightCollapsed = !rightCollapsed"
        >
          <Icon :name="rightOpen ? 'chevron-down' : 'chevron-up'" :size="13" />
          {{ rightOpen ? 'Show map' : rightContent === 'review' ? 'Show review' : rightContent === 'edit' ? 'Show editor' : 'Show inspector' }}
        </button>
        <div class="h-full overflow-hidden md:w-[392px]">
          <ReviewCard v-if="rightContent === 'review'" />
          <Inspector v-else-if="rightContent === 'inspector'" />
          <EditInspector v-else-if="rightContent === 'edit'" />
        </div>
      </aside>
    </div>

    <SettingsDialog v-if="app.settingsOpen" />
    <MergeDialog v-if="app.mergeDraftId" :draft-id="app.mergeDraftId" />
    <EditDialogs />
    <Toasts />
  </div>
</template>
