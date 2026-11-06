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
import UploadOverlayLayers from '@/map/UploadOverlayLayers.vue'
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
import RemoteConfirmDialog from '@/components/RemoteConfirmDialog.vue'
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
const leftCollapsed = ref(false)

const leftContent = computed<'review' | 'inspector' | 'edit' | null>(() =>
  editor.active
    ? 'edit'
    : drafts.reviewing && drafts.current
      ? 'review'
      : mapStore.selected
        ? 'inspector'
        : null,
)
const leftOpen = computed(() => !!leftContent.value && !leftCollapsed.value)
const rowBox = ref<HTMLElement | null>(null)
const viewport = ref({ width: window.innerWidth, height: 0 })
const phone = computed(() => viewport.value.width < 768)
const rightWidth = computed(() => (panel.wide ? Math.min(560, viewport.value.width) : 296))

const insets = computed(() => {
  const drawer = drafts.drawerOpen ? 260 : 36
  if (phone.value) {
    const sheet = leftOpen.value ? Math.round(viewport.value.height * 0.58) : 0
    return { top: 0, left: 0, right: 0, bottom: Math.max(drawer, sheet) }
  }
  return { top: 0, left: leftOpen.value ? 392 : 0, right: panel.open ? rightWidth.value : 0, bottom: drawer }
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
    if (window.innerWidth < 768) leftCollapsed.value = false
  },
)
watch(leftOpen, () => {
  if (drafts.reviewing && window.innerWidth < 768) setTimeout(() => drafts.focusCurrent(), 220)
})

function onKey(e: KeyboardEvent) {
  if (isTyping(e) || hasModifier(e) || app.settingsOpen || app.mergeDraftId) return
  if (e.key === '/') {
    e.preventDefault()
    topBar.value?.focusSearch()
  } else if (e.key === '[') {
    leftCollapsed.value = !leftCollapsed.value
  } else if (e.key === ']') {
    panel.open = !panel.open
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
  void usePresetsStore().ensure().catch(() => {})
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
      <Icon name="alert" :size="14" /> {{ t('shell.serverError', { error: app.serverError }) }}
      <button class="btn btn-sm ml-auto" @click="app.init()">{{ t('common.retry') }}</button>
    </div>
    <div
      v-else-if="app.mirror && !app.mirror.available"
      class="flex shrink-0 items-center gap-2 border-b border-changed/30 bg-changed-soft px-4 py-1.5 text-[12.5px] text-changed"
    >
      <Icon name="database" :size="14" /> {{ t('shell.noMirror', { detail: app.mirror.message ? `: ${app.mirror.message}` : '' }) }}
    </div>

    <div ref="rowBox" class="relative min-h-0 flex-1 overflow-hidden" data-testid="map-area">
      <div class="absolute inset-0">
        <MapView />
        <UploadOverlayLayers />
        <div
          class="pointer-events-none absolute transition-[left,right,bottom] duration-200"
          :style="{ left: `${insets.left}px`, right: `${insets.right}px`, top: `${insets.top}px`, bottom: `${insets.bottom}px` }"
        >
          <HighlightLegend />
        </div>
      </div>

      <aside
        class="absolute z-20 border-line bg-surface md:inset-y-0 md:left-0 md:overflow-hidden md:border-r md:transition-[width] md:duration-200 max-md:inset-x-0 max-md:bottom-0 max-md:border-t max-md:shadow-pop max-md:transition-[height]"
        :class="[leftOpen ? 'md:w-[392px] max-md:h-[58%]' : 'md:w-0 md:border-r-0 max-md:h-0 max-md:border-t-0', !leftContent && 'max-md:hidden']"
        data-testid="left-panel"
      >
        <button
          v-if="leftContent"
          class="absolute -top-8 right-3 flex h-8 items-center gap-1 rounded-t-lg border border-b-0 border-line bg-surface px-3 text-[12px] font-medium text-fg-2 shadow-panel md:hidden"
          data-testid="sheet-toggle"
          @click="leftCollapsed = !leftCollapsed"
        >
          <Icon :name="leftOpen ? 'chevron-down' : 'chevron-up'" :size="13" />
          {{ leftOpen ? t('shell.showMap') : leftContent === 'review' ? t('shell.showReview') : leftContent === 'edit' ? t('shell.showEditor') : t('shell.showInspector') }}
        </button>
        <div class="h-full overflow-hidden md:w-[392px]">
          <ReviewCard v-if="leftContent === 'review'" />
          <Inspector v-else-if="leftContent === 'inspector'" />
          <EditInspector v-else-if="leftContent === 'edit'" />
        </div>
      </aside>
      <button
        v-if="leftContent"
        class="absolute top-1/2 z-30 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-r-md border border-l-0 border-line bg-surface text-fg-3 shadow-panel transition-[left] duration-200 hover:text-fg max-md:hidden"
        :style="{ left: `${insets.left}px` }"
        :title="leftOpen ? t('shell.hidePanel') : t('shell.showPanel')"
        data-testid="left-toggle"
        @click="leftCollapsed = !leftCollapsed"
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
        class="absolute top-1/2 z-30 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-l-md border border-r-0 border-line bg-surface text-fg-3 shadow-panel transition-[right] duration-200 hover:text-fg"
        :class="panel.open && panel.wide && 'max-md:hidden'"
        :style="{ right: panel.open ? `${rightWidth}px` : '0px' }"
        :title="panel.open ? t('shell.hideLayers') : t('shell.showLayers')"
        data-testid="right-toggle"
        @click="panel.open = !panel.open"
      >
        <Icon :name="panel.open ? 'chevron-right' : 'chevron-left'" :size="12" />
      </button>
      <aside
        class="absolute inset-y-0 right-0 z-20 overflow-hidden border-l border-line bg-surface transition-[width] duration-200 max-md:shadow-panel"
        :style="{ width: panel.open ? `${rightWidth}px` : '0px' }"
        :class="!panel.open && 'border-l-0'"
        data-testid="right-panel"
      >
        <div class="ml-auto h-full max-w-[100vw]" :style="{ width: `${rightWidth}px` }"><SidePanel /></div>
      </aside>
    </div>

    <SettingsDialog v-if="app.settingsOpen" />
    <MergeDialog v-if="app.mergeDraftId" :draft-id="app.mergeDraftId" />
    <EditDialogs />
    <RemoteConfirmDialog />
    <Toasts />
  </div>
</template>
