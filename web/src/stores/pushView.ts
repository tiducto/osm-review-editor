import type { OsmRef, UiEventPushView } from '@/generated/models'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useDraftsStore } from './drafts'
import { useHighlightsStore } from './highlights'
import { useScansStore } from './scans'
import { usePanelStore, type PaneName } from './panel'
import { currentEditSession, useEditorStore } from './editor'
import { t, tn } from '@/i18n'

interface ViewSnapshot {
  center: [number, number] | null
  zoom: number | null
  bearing: number
  pitch: number
  highlights: string[]
  areaName: string | null
  scanId: string | null
  draftId: string | null
  reviewing: boolean
  selected: OsmRef | null
  editSelection: string[]
  pane: PaneName | null
}

function editId(ref: OsmRef): string {
  return ref.type[0] + String(ref.id)
}

export function describePushView(e: UiEventPushView): string {
  const parts: string[] = []
  if (e.areaName) parts.push(e.areaName)
  else if (e.bbox || e.center) parts.push(t('push.theView'))
  if (e.highlights.length) parts.push(tn('push.highlights', e.highlights.length))
  if (e.scanId) parts.push(t('push.scanReport'))
  if (e.draftId) parts.push(t('push.draft'))
  if (e.select.length) parts.push(e.select.map((r) => editId(r)).join(' '))
  return parts.join(', ')
}

export function usePushView() {
  const app = useAppStore()
  const mapStore = useMapStore()
  const drafts = useDraftsStore()
  const highlights = useHighlightsStore()
  const scans = useScansStore()
  const panel = usePanelStore()
  const editor = useEditorStore()

  function snapshot(): ViewSnapshot {
    const map = mapStore.instance()
    const c = map?.getCenter()
    return {
      center: c ? [c.lng, c.lat] : null,
      zoom: map?.getZoom() ?? null,
      bearing: map?.getBearing() ?? 0,
      pitch: map?.getPitch() ?? 0,
      highlights: [...highlights.active],
      areaName: mapStore.area?.name ?? null,
      scanId: scans.report?.scan.id ?? null,
      draftId: drafts.current?.draft.id ?? null,
      reviewing: drafts.reviewing,
      selected: mapStore.selected,
      editSelection: [...editor.selected],
      pane: panel.pane,
    }
  }

  async function restore(s: ViewSnapshot) {
    const map = mapStore.instance()
    highlights.active = [...s.highlights]
    if (s.pane) panel.open(s.pane)
    else panel.close()
    if ((mapStore.area?.name ?? null) !== s.areaName) await mapStore.setArea(s.areaName, false)
    if ((scans.report?.scan.id ?? null) !== s.scanId) {
      if (s.scanId) await scans.openScan(s.scanId)
      else scans.closeReport()
    }
    if ((drafts.current?.draft.id ?? null) !== s.draftId || drafts.reviewing !== s.reviewing) {
      if (s.draftId) await drafts.open(s.draftId, { review: s.reviewing })
      else drafts.close()
    }
    if (editor.active) editor.select(s.editSelection)
    else void mapStore.select(s.selected)
    if (map && s.center && s.zoom != null) map.jumpTo({ center: s.center, zoom: s.zoom, bearing: s.bearing, pitch: s.pitch })
  }

  async function apply(e: UiEventPushView) {
    const before = snapshot()
    const map = mapStore.instance()
    if (e.highlights.length || e.replaceHighlights) {
      if (e.replaceHighlights) highlights.active = [...new Set(e.highlights)]
      else for (const p of e.highlights) highlights.toggle(p, true)
      if (!highlights.loaded) void highlights.load()
      panel.open('map-data')
    }
    if (e.areaName && !editor.active) await mapStore.setArea(e.areaName, false)
    if (e.scanId) {
      panel.open('validation')
      await scans.openScan(e.scanId)
    }
    if (e.draftId) {
      if (editor.active && editor.editCount > 0) app.toast(t('push.unsavedEdits'), 'error', 9000)
      else {
        if (editor.active) editor.exit(true)
        await drafts.open(e.draftId, { review: true })
      }
    } else if (drafts.reviewing && (e.bbox || e.center || e.select.length)) drafts.stopReview()
    const framed = !!(e.bbox || e.center)
    if (e.bbox) mapStore.fitBBox(e.bbox, 19)
    else if (e.center && map) mapStore.flyTo(e.center, e.zoom ?? Math.max(map.getZoom(), 17))
    if (e.select.length) {
      const session = editor.active ? currentEditSession() : null
      const ids = session ? e.select.map(editId).filter((id) => session.adapter.graph().hasEntity(id)) : []
      if (session && ids.length) editor.select(ids)
      else {
        mapStore.highlighted = e.select.length > 1 ? e.select : []
        void mapStore.select(e.select[0], { fly: !framed && !e.draftId })
      }
    }
    const what = describePushView(e)
    const head = what ? t('push.changedWhat', { what }) : t('push.changed')
    const text = e.message ? `${head} — ${e.message}` : head
    app.toast(text, 'info', 12000, { action: { label: t('common.undo'), run: () => void restore(before) }, testId: 'push-view-toast' })
    window.focus()
  }

  return { apply, snapshot, restore }
}
