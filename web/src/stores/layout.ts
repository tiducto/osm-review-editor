import { defineStore } from 'pinia'
import { computed, reactive, ref, watch, watchEffect } from 'vue'
import { useEditorStore } from './editor'
import { useDraftsStore } from './drafts'
import { useMapStore } from './map'
import { usePanelStore } from './panel'

export type LeftContent = 'edit' | 'review' | 'inspector'
export type SheetView = 'left' | 'pane'

const WIDTH_KEY = 'ore.sidebarWidth'
const PHONE_QUERY = '(max-width: 767.98px)'
export const SIDEBAR_MIN = 300
export const SIDEBAR_DEFAULT = 400
export const STRIP_WIDTH = 40
export const STRIP_WIDTH_PHONE = 36
export const PANE_WIDTH = 380
export const PANE_WIDE = 720

function readWidth(): number {
  try {
    const v = Number(localStorage.getItem(WIDTH_KEY))
    if (Number.isFinite(v) && v >= SIDEBAR_MIN) return v
  } catch {}
  return SIDEBAR_DEFAULT
}

function phoneQuery(): MediaQueryList | null {
  return typeof window.matchMedia === 'function' ? window.matchMedia(PHONE_QUERY) : null
}

export const useLayoutStore = defineStore('layout', () => {
  const editor = useEditorStore()
  const drafts = useDraftsStore()
  const mapStore = useMapStore()
  const panel = usePanelStore()

  const mq = phoneQuery()
  const phone = ref(mq?.matches ?? window.innerWidth < 768)
  mq?.addEventListener('change', (e) => (phone.value = e.matches))
  const viewportWidth = ref(window.innerWidth)
  window.addEventListener('resize', () => (viewportWidth.value = window.innerWidth))

  const storedWidth = ref(readWidth())
  const sidebarMax = computed(() => Math.max(SIDEBAR_MIN, Math.floor(viewportWidth.value / 2)))
  const sidebarWidth = computed(() => Math.min(Math.max(storedWidth.value, SIDEBAR_MIN), sidebarMax.value))
  const sidebarCollapsed = ref(false)

  const leftContent = computed<LeftContent | null>(() =>
    editor.active ? 'edit' : drafts.reviewing && drafts.current ? 'review' : mapStore.selected ? 'inspector' : null,
  )
  const sidebarOpen = computed(() => !!leftContent.value && !sidebarCollapsed.value)

  const sheetView = ref<SheetView>('left')
  const sheetCollapsed = ref(false)
  const sheetHeight = ref(0)
  const sheetShown = computed(() => phone.value && (!!leftContent.value || !!panel.pane))
  const sheetContent = computed<SheetView | null>(() => {
    if (!leftContent.value && !panel.pane) return null
    if (sheetView.value === 'pane' && panel.pane) return 'pane'
    if (sheetView.value === 'left' && leftContent.value) return 'left'
    return leftContent.value ? 'left' : 'pane'
  })

  const paneWidth = computed(() =>
    Math.min(panel.wide ? PANE_WIDE : PANE_WIDTH, Math.max(0, viewportWidth.value - STRIP_WIDTH)),
  )
  const stripWidth = computed(() => (phone.value ? STRIP_WIDTH_PHONE : STRIP_WIDTH))

  const insets = reactive({
    left: computed(() => (!phone.value && sidebarOpen.value ? sidebarWidth.value : 0)),
    right: computed(() => stripWidth.value + (!phone.value && panel.pane ? paneWidth.value : 0)),
    bottom: computed(() => (sheetShown.value ? sheetHeight.value : 0)),
  })

  function setSidebarWidth(px: number, persist = false) {
    storedWidth.value = Math.min(Math.max(Math.round(px), SIDEBAR_MIN), sidebarMax.value)
    if (!persist) return
    try {
      localStorage.setItem(WIDTH_KEY, String(storedWidth.value))
    } catch {}
  }

  function toggleSidebar() {
    if (phone.value) {
      if (!leftContent.value) return
      if (sheetContent.value === 'left' && !sheetCollapsed.value) sheetCollapsed.value = true
      else showSheet('left')
      return
    }
    if (leftContent.value) sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function showSheet(view: SheetView) {
    sheetView.value = view
    sheetCollapsed.value = false
  }

  watch(
    () => [mapStore.selected, drafts.reviewing && drafts.current?.draft.id, editor.active],
    () => {
      if (phone.value) showSheet('left')
    },
  )
  watch(
    () => panel.pane,
    (p) => {
      if (p) showSheet('pane')
      else sheetView.value = 'left'
    },
  )

  watchEffect(() => {
    const s = document.documentElement.style
    s.setProperty('--inset-left', `${insets.left}px`)
    s.setProperty('--inset-right', `${insets.right}px`)
    s.setProperty('--inset-bottom', `${insets.bottom}px`)
  })

  return {
    phone, viewportWidth, sidebarWidth, sidebarMax, sidebarCollapsed, sidebarOpen, leftContent,
    sheetView, sheetCollapsed, sheetHeight, sheetShown, sheetContent, showSheet,
    paneWidth, stripWidth, insets, setSidebarWidth, toggleSidebar,
  }
})

export function useLayoutInsets(): { left: number; right: number; bottom: number } {
  return useLayoutStore().insets
}
