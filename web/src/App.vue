<script setup lang="ts">
import { useI18n, type MessageKey } from '@/i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { useMapStore } from '@/stores/map'
import { useRulesStore } from '@/stores/rules'
import { useDraftsStore } from '@/stores/drafts'
import { useHighlightsStore } from '@/stores/highlights'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import { useScansStore } from '@/stores/scans'
import { usePanelStore, type PaneName } from '@/stores/panel'
import { useLayoutStore, type LeftContent } from '@/stores/layout'
import { connectEvents } from '@/stores/events'
import { hasModifier, isTyping } from '@/lib/keyboard'
import MapView from '@/map/MapView.vue'
import HighlightMapLayers from '@/map/HighlightMapLayers.vue'
import ScanMapLayers from '@/map/ScanMapLayers.vue'
import UploadOverlayLayers from '@/map/UploadOverlayLayers.vue'
import TopBar from '@/components/TopBar.vue'
import HighlightLegend from '@/components/HighlightLegend.vue'
import Inspector from '@/components/Inspector.vue'
import ReviewCard from '@/components/ReviewCard.vue'
import MergeDialog from '@/components/MergeDialog.vue'
import Toasts from '@/components/Toasts.vue'
import RemoteConfirmDialog from '@/components/RemoteConfirmDialog.vue'
import Icon from '@/components/Icon.vue'
import EditInspector from '@/components/editor/EditInspector.vue'
import EditDialogs from '@/components/editor/EditDialogs.vue'
import PaneHost from '@/components/panes/PaneHost.vue'
import MapControls from '@/components/panes/MapControls.vue'
import { useEditorStore } from '@/stores/editor'
import { usePresetsStore } from '@/stores/presets'

const app = useAppStore()
const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()
const hl = useHighlightsStore()
const ruleFiles = useRuleFilesStore()
const scans = useScansStore()
const panel = usePanelStore()
const layout = useLayoutStore()
const editor = useEditorStore()
const { t } = useI18n()

const topBar = ref<InstanceType<typeof TopBar> | null>(null)
const sheet = ref<HTMLElement | null>(null)
const resizing = ref(false)

const LEFT_LABEL: Record<LeftContent, MessageKey> = {
  review: 'shell.left.review',
  edit: 'shell.left.edit',
  inspector: 'shell.left.inspector',
}

const PANE_KEYS: Record<string, PaneName> = { b: 'background', f: 'map-data', i: 'validation', l: 'rules', p: 'presets', d: 'drafts' }

watch(
  () => drafts.reviewing,
  (r) => {
    if (r && editor.active) editor.exit()
  },
)
watch(
  () => [mapStore.selected, drafts.reviewing && drafts.current?.draft.id, editor.active],
  () => (layout.sidebarCollapsed = false),
)
watch(
  () => (drafts.reviewing ? (drafts.current?.draft.id ?? null) : null),
  (id) => {
    if (id && layout.phone) setTimeout(() => drafts.focusCurrent(), 250)
  },
)
watch(
  () => ({ top: 0, left: layout.insets.left, right: layout.insets.right, bottom: layout.insets.bottom }),
  (i) => mapStore.setInsets(i),
  { immediate: true },
)
watch(
  () => panel.pane,
  (p) => {
    scans.active = p === 'validation'
  },
  { immediate: true },
)

let restored = false
watch(
  () => mapStore.ready,
  (r) => {
    if (!r || restored) return
    restored = true
    void hl.restoreFromUrl()
  },
  { immediate: true },
)

function dialogOpen(): boolean {
  return !!document.querySelector('[role="dialog"]')
}

function onEscapeCapture(e: KeyboardEvent) {
  if (e.key !== 'Escape' || !panel.pane || isTyping(e) || hasModifier(e) || app.mergeDraftId || mapStore.drawing || dialogOpen()) return
  const active = document.activeElement
  const inPane = active instanceof HTMLElement && !!active.closest('[data-pane]')
  if (!inPane && (editor.active || drafts.reviewing)) return
  e.preventDefault()
  e.stopImmediatePropagation()
  if (inPane) (active as HTMLElement).blur()
  panel.close()
}

function onKey(e: KeyboardEvent) {
  if (e.defaultPrevented || isTyping(e) || hasModifier(e) || app.mergeDraftId || dialogOpen()) return
  if (e.key === '/') {
    e.preventDefault()
    topBar.value?.focusSearch()
  } else if (e.key === '[') {
    layout.toggleSidebar()
  } else if (e.key === ']') {
    panel.toggle()
  } else if (e.key === 'Escape' && !editor.active && !drafts.reviewing && mapStore.selected) {
    void mapStore.select(null)
    mapStore.highlighted = []
  } else if (!e.shiftKey && e.key.length === 1) {
    const key = e.key.toLowerCase()
    const pane = PANE_KEYS[key]
    if (!pane || (key === 'f' && drafts.reviewing) || (key === 'd' && editor.active)) return
    e.preventDefault()
    panel.toggle(pane)
  }
}

function startResize(e: PointerEvent) {
  const el = e.currentTarget as HTMLElement
  el.setPointerCapture(e.pointerId)
  resizing.value = true
  const startX = e.clientX
  const startW = layout.sidebarWidth
  const move = (ev: PointerEvent) => layout.setSidebarWidth(startW + ev.clientX - startX)
  const up = (ev: PointerEvent) => {
    layout.setSidebarWidth(startW + ev.clientX - startX, true)
    resizing.value = false
    el.removeEventListener('pointermove', move)
    el.removeEventListener('pointerup', up)
    el.removeEventListener('pointercancel', up)
  }
  el.addEventListener('pointermove', move)
  el.addEventListener('pointerup', up)
  el.addEventListener('pointercancel', up)
}

const sheetTabs = computed(() => {
  const out: { id: 'left' | 'pane'; label: string }[] = []
  if (layout.leftContent) out.push({ id: 'left', label: t(LEFT_LABEL[layout.leftContent]) })
  if (panel.pane) out.push({ id: 'pane', label: t(`shell.pane.${panel.pane}`) })
  return out
})

let sheetObserver: ResizeObserver | null = null
watch(sheet, (el, old) => {
  if (old) sheetObserver?.unobserve(old)
  if (el) sheetObserver?.observe(el)
  else layout.sheetHeight = 0
})

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
  window.addEventListener('keydown', onEscapeCapture, true)
  window.addEventListener('keydown', onKey)
  sheetObserver = new ResizeObserver(() => (layout.sheetHeight = sheet.value?.offsetHeight ?? 0))
  if (sheet.value) sheetObserver.observe(sheet.value)
  stopEvents = connectEvents()
  void hl.load()
  void ruleFiles.load()
  await app.init()
  void usePresetsStore().ensure().catch(() => {})
  await rules.load()
  void drafts.loadDrafts()
  const linked = new URLSearchParams(location.search).get('draft')
  if (linked) void drafts.open(linked, { review: true })
  if (document.referrer.includes('/auth/')) void app.loadAccount()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onEscapeCapture, true)
  window.removeEventListener('keydown', onKey)
  sheetObserver?.disconnect()
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

    <main class="relative isolate min-h-0 flex-1 overflow-hidden" data-testid="map-area">
      <MapView />
      <HighlightMapLayers />
      <ScanMapLayers />
      <UploadOverlayLayers />
      <HighlightLegend />

      <template v-if="!layout.phone">
        <aside
          v-if="layout.leftContent"
          v-show="layout.sidebarOpen"
          class="absolute inset-y-0 left-0 z-20 border-r border-line-strong bg-surface shadow-panel"
          :style="{ width: `${layout.sidebarWidth}px` }"
          data-testid="sidebar-left"
        >
          <div class="h-full overflow-hidden">
            <EditInspector v-if="layout.leftContent === 'edit'" />
            <ReviewCard v-else-if="layout.leftContent === 'review'" />
            <Inspector v-else />
          </div>
          <div
            class="sidebar-resizer"
            :class="resizing && 'dragging'"
            role="separator"
            aria-orientation="vertical"
            :aria-valuenow="layout.sidebarWidth"
            :title="t('shell.sidebar.resize')"
            data-testid="sidebar-resizer"
            @pointerdown.prevent="startResize"
          />
        </aside>
        <button
          v-if="layout.leftContent"
          class="absolute top-1/2 z-20 grid h-12 w-4 -translate-y-1/2 place-items-center rounded-r-md border border-l-0 border-line-strong bg-surface text-fg-3 shadow-panel hover:text-fg"
          :style="{ left: `${layout.insets.left}px` }"
          :title="layout.sidebarOpen ? t('shell.sidebar.hide') : t('shell.sidebar.show')"
          :aria-expanded="layout.sidebarOpen"
          data-testid="sidebar-toggle"
          @click="layout.toggleSidebar()"
        >
          <Icon :name="layout.sidebarOpen ? 'chevron-left' : 'chevron-right'" :size="12" />
        </button>

        <aside
          v-if="panel.pane"
          class="absolute inset-y-0 z-20 border-l border-line-strong bg-surface shadow-panel"
          :style="{ right: `${layout.stripWidth}px`, width: `${layout.paneWidth}px` }"
          :data-testid="`pane-${panel.pane}`"
          data-pane
        >
          <PaneHost :name="panel.pane" />
        </aside>
      </template>

      <section
        v-else-if="layout.sheetShown"
        ref="sheet"
        class="absolute inset-x-0 bottom-0 z-20 flex flex-col rounded-t-lg border-t border-line-strong bg-surface shadow-pop"
        :class="layout.sheetCollapsed ? '' : 'h-[50%]'"
        data-testid="bottom-sheet"
      >
        <button
          class="mx-auto mt-1.5 block h-1.5 w-10 shrink-0 rounded-full bg-line-strong"
          :title="layout.sheetCollapsed ? t('shell.sheet.expand') : t('shell.sheet.collapse')"
          data-testid="sheet-handle"
          @click="layout.sheetCollapsed = !layout.sheetCollapsed"
        />
        <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line-strong px-2" :class="layout.sheetCollapsed && 'border-b-0'">
          <div class="seg min-w-0 flex-1" role="tablist">
            <button
              v-for="tab in sheetTabs"
              :key="tab.id"
              role="tab"
              class="seg-btn h-7 min-w-0 flex-1"
              :aria-selected="layout.sheetContent === tab.id"
              :data-testid="`sheet-tab-${tab.id}`"
              @click="layout.showSheet(tab.id)"
            >
              <span class="truncate">{{ tab.label }}</span>
            </button>
          </div>
          <button
            class="btn btn-ghost btn-icon"
            :title="layout.sheetCollapsed ? t('shell.sheet.expand') : t('shell.sheet.collapse')"
            data-testid="sheet-toggle"
            @click="layout.sheetCollapsed = !layout.sheetCollapsed"
          >
            <Icon :name="layout.sheetCollapsed ? 'chevron-up' : 'chevron-down'" :size="16" />
          </button>
          <button v-if="layout.sheetContent === 'pane'" class="btn btn-ghost btn-icon" :title="t('shell.pane.close')" data-testid="pane-close" @click="panel.close()">
            <Icon name="x" :size="16" />
          </button>
        </div>
        <div v-show="!layout.sheetCollapsed" class="min-h-0 flex-1">
          <div v-if="layout.leftContent" v-show="layout.sheetContent === 'left'" class="h-full overflow-hidden" data-testid="sidebar-left">
            <EditInspector v-if="layout.leftContent === 'edit'" />
            <ReviewCard v-else-if="layout.leftContent === 'review'" />
            <Inspector v-else />
          </div>
          <div v-if="panel.pane" v-show="layout.sheetContent === 'pane'" class="h-full" :data-testid="`pane-${panel.pane}`" data-pane>
            <PaneHost :name="panel.pane" :header="false" />
          </div>
        </div>
      </section>

      <MapControls class="absolute right-0 z-30" :class="layout.phone ? 'top-2' : 'top-1/2 -translate-y-1/2'" />
    </main>

    <MergeDialog v-if="app.mergeDraftId" :draft-id="app.mergeDraftId" />
    <EditDialogs />
    <RemoteConfirmDialog />
    <Toasts />
  </div>
</template>
