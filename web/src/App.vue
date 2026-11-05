<script setup lang="ts">
import { useI18n } from '@/i18n'
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
import { usePresetsStore } from '@/stores/presets'

const app = useAppStore()
const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()
const panel = usePanelStore()
const editor = useEditorStore()
const { t } = useI18n()

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
const mapBox = ref<HTMLElement | null>(null)
const rightPanel = ref<HTMLElement | null>(null)

function syncInset() {
  const map = mapBox.value?.getBoundingClientRect()
  const box = rightPanel.value?.parentElement?.getBoundingClientRect()
  if (!map || !box || window.innerWidth >= 768 || !rightOpen.value) {
    mapStore.setBottomInset(0)
    return
  }
  mapStore.setBottomInset(Math.max(0, map.bottom - (box.bottom - box.height * 0.58)))
}
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
  syncInset()
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
  window.addEventListener('resize', syncInset)
  syncInset()
  stopEvents = connectEvents()
  await app.init()
  void usePresetsStore().ensure().catch(() => {})
  await rules.load()
  void drafts.loadDrafts()
  const linked = new URLSearchParams(location.search).get('draft')
  if (linked) void drafts.open(linked, { review: true })
  if (document.referrer.includes('/auth/')) void app.loadAccount()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', syncInset)
  stopEvents?.()
})
</script>

<template>
  <div class="flex h-full flex-col overflow-hidden">
    <TopBar ref="topBar" />

    <div v-if="app.serverError" class="flex shrink-0 items-center gap-2 border-b border-error/30 bg-removed-soft px-4 py-1.5 text-[12.5px] text-removed">
      <Icon name="alert" :size="14" /> {{ t('shell.serverError', { error: app.serverError }) }}
      <button class="btn btn-sm ml-auto" @click="app.init()">{{ t('common.retry') }}</button>
    </div>
    <div
      v-else-if="app.mirror && !app.mirror.available"
      class="flex shrink-0 items-center gap-2 border-b border-changed/30 bg-changed-soft px-4 py-1.5 text-[12.5px] text-changed"
    >
      <Icon name="database" :size="14" /> {{ t('shell.noMirror', { detail: app.mirror.message ? `: ${app.mirror.message}` : '' }) }}
    </div>

    <div class="relative flex min-h-0 flex-1">
      <aside
        class="shrink-0 overflow-hidden border-r border-line bg-surface transition-[width] duration-200 max-md:absolute max-md:inset-y-0 max-md:left-0 max-md:z-20 max-md:shadow-panel"
        :class="leftOpen ? (panel.wide ? 'w-[min(560px,100vw)]' : 'w-[296px]') : 'w-0 border-r-0'"
      >
        <div class="h-full max-w-[100vw]" :class="panel.wide ? 'w-[min(560px,100vw)]' : 'w-[296px]'"><SidePanel /></div>
      </aside>

      <main class="flex min-w-0 flex-1 flex-col">
        <div ref="mapBox" class="relative min-h-0 flex-1">
          <MapView />
          <HighlightLegend />
          <button
            class="absolute top-1/2 left-0 z-10 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-r-md border border-l-0 border-line bg-surface text-fg-3 shadow-panel hover:text-fg"
            :class="leftOpen && (panel.wide ? 'max-md:hidden' : 'max-md:left-[296px] max-md:z-30')"
            :title="leftOpen ? t('shell.hideLayers') : t('shell.showLayers')"
            @click="leftOpen = !leftOpen"
          >
            <Icon :name="leftOpen ? 'chevron-left' : 'chevron-right'" :size="12" />
          </button>
          <button
            v-if="rightContent"
            class="absolute top-1/2 right-0 z-10 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-l-md border border-r-0 border-line bg-surface text-fg-3 shadow-panel hover:text-fg max-md:hidden"
            :title="rightOpen ? t('shell.hidePanel') : t('shell.showPanel')"
            @click="rightCollapsed = !rightCollapsed"
          >
            <Icon :name="rightOpen ? 'chevron-right' : 'chevron-left'" :size="12" />
          </button>
        </div>
        <BottomDrawer />
      </main>

      <aside
        class="shrink-0 border-line bg-surface md:overflow-hidden md:border-l md:transition-[width] md:duration-200 max-md:absolute max-md:inset-x-0 max-md:bottom-0 max-md:z-20 max-md:border-t max-md:shadow-pop max-md:transition-[height]"
        :class="[rightOpen ? 'md:w-[392px] max-md:h-[58%]' : 'md:w-0 md:border-l-0 max-md:h-0 max-md:border-t-0', !rightContent && 'max-md:hidden']"
        ref="rightPanel"
        data-testid="right-panel"
      >
        <button
          v-if="rightContent"
          class="absolute -top-8 right-3 flex h-8 items-center gap-1 rounded-t-lg border border-b-0 border-line bg-surface px-3 text-[12px] font-medium text-fg-2 shadow-panel md:hidden"
          data-testid="sheet-toggle"
          @click="rightCollapsed = !rightCollapsed"
        >
          <Icon :name="rightOpen ? 'chevron-down' : 'chevron-up'" :size="13" />
          {{ rightOpen ? t('shell.showMap') : rightContent === 'review' ? t('shell.showReview') : rightContent === 'edit' ? t('shell.showEditor') : t('shell.showInspector') }}
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
