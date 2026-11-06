import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import type { DraftDetail, OpenChangeElsewhere, OsmChangeImportResult, ProposalResult } from '@/generated/models'
import { api, ApiError, errorMessage } from '@/api/client'
import type { EditSession } from '@/editor/session'
import type { EditMode } from '@/editor/controller'
import type { EntityGeometry, EntitySnapshot, OperationInfo, Tags } from '@/editor/types'
import { asGeometry, geometryBBox } from '@/lib/geo'
import { isTyping } from '@/lib/keyboard'
import { refString } from '@/lib/osm'
import { formatNumber, locale, t, tn } from '@/i18n'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useDraftsStore } from './drafts'
import { useEditIssuesStore } from './editIssues'

export const EDIT_MIN_ZOOM = 16

let session: EditSession | null = null

export function currentEditSession(): EditSession | null {
  return session
}

export interface SaveRequest {
  title: string
  changesetComment: string
}

export interface NeedsPreset {
  entityId: string
  geometry: EntityGeometry
}

export interface TakeOverPrompt {
  openChanges: OpenChangeElsewhere[]
  resolve: (ok: boolean) => void
}

export const useEditorStore = defineStore('editor', () => {
  const app = useAppStore()
  const mapStore = useMapStore()
  const drafts = useDraftsStore()
  const issues = useEditIssuesStore()

  const active = ref(false)
  const starting = ref(false)
  const mode = ref<EditMode>('browse')
  const selected = ref<string[]>([])
  const revision = ref(0)
  const editCount = ref(0)
  const undoLabel = ref<string | null>(null)
  const redoLabel = ref<string | null>(null)
  const loadingTiles = ref(0)
  const draftId = ref<string | null>(null)
  const draftTitle = ref<string | null>(null)
  const replaceSafe = ref(true)
  const saving = ref(false)
  const saveDialog = ref(false)
  const exitConfirm = ref(false)
  const takeOver = shallowRef<TakeOverPrompt | null>(null)
  const menu = ref<{ x: number; y: number } | null>(null)
  const hover = ref<{ x: number; y: number; id: string } | null>(null)
  const notice = ref<string | null>(null)
  const version = ref<string | null>(null)
  const needsPreset = ref<NeedsPreset | null>(null)
  const presetListeners = new Set<(req: NeedsPreset) => void>()

  function onNeedsPreset(fn: (req: NeedsPreset) => void): () => void {
    presetListeners.add(fn)
    return () => presetListeners.delete(fn)
  }

  function isUntagged(tags: Tags): boolean {
    return Object.keys(tags).every((k) => k === 'area')
  }

  function requestPreset(id: string) {
    const snap = session?.adapter.snapshot(id)
    if (!snap || !snap.isNew || !isUntagged(snap.tags)) return
    const req: NeedsPreset = { entityId: id, geometry: snap.geometry }
    needsPreset.value = req
    for (const fn of presetListeners) fn(req)
  }

  function clearNeedsPreset() {
    needsPreset.value = null
  }

  function checkNeedsPreset() {
    const req = needsPreset.value
    if (!req || !session) return
    const snap = session.adapter.snapshot(req.entityId)
    if (!snap || !isUntagged(snap.tags) || !selected.value.includes(req.entityId)) needsPreset.value = null
  }

  const zoomOk = computed(() => mapStore.zoom >= EDIT_MIN_ZOOM)

  const selection = computed<EntitySnapshot[]>(() => {
    void revision.value
    if (!session) return []
    return selected.value.map((id) => session!.adapter.snapshot(id)).filter((s): s is EntitySnapshot => !!s)
  })

  const operations = computed<OperationInfo[]>(() => {
    void revision.value
    if (!session || !selected.value.length) return []
    return session.adapter.operations(selected.value)
  })

  function sync() {
    if (!session) return
    const a = session.adapter
    revision.value++
    editCount.value = a.editCount()
    undoLabel.value = a.undoLabel()
    redoLabel.value = a.redoLabel()
    selected.value = [...a.selected]
    checkNeedsPreset()
    issues.schedule()
  }

  watch(locale, (l) => {
    const s = session
    if (!s) return
    void import('@/editor/session')
      .then((m) => m.setIdLocale(l))
      .then((changed) => {
        if (changed && session === s) sync()
      })
      .catch(() => {})
  })

  async function enter(): Promise<boolean> {
    if (active.value || starting.value) return active.value
    const map = mapStore.instance()
    if (!map) return false
    if (map.getZoom() < EDIT_MIN_ZOOM) {
      app.toast(t('edit.zoomIn'))
      return false
    }
    starting.value = true
    try {
      drafts.stopReview()
      void mapStore.select(null)
      const mod = await import('@/editor/session')
      session = await mod.createEditSession(map, {
        selectionChanged: (ids) => {
          selected.value = [...ids]
          menu.value = null
          revision.value++
          checkNeedsPreset()
        },
        featureCreated: (id) => requestPreset(id),
        issueClicked: (id) => issues.focusById(id),
        modeChanged: (m) => (mode.value = m),
        historyChanged: () => sync(),
        openMenu: (x, y) => (menu.value = { x, y }),
        hover: (h) => (hover.value = h),
        notice: (t) => app.toast(t),
        busy: (n) => (loadingTiles.value = n),
        loaded: () => revision.value++,
        error: (t) => app.toast(t, 'error'),
      })
      session.adapter.onEnter = (m) => {
        if (!session) return
        if (m.id === 'move' || m.id === 'rotate') session.controller.startTransform(m.id, m.ids)
        else if (m.id === 'continue') session.controller.startContinue(m.ids)
        else if (m.id === 'select') session.controller.select(m.ids)
        else session.controller.select([])
      }
      version.value = session.version
      if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__oreEdit = session
      active.value = true
      const s = session
      void issues.attach(s, () => s.controller.mode === 'draw' || s.controller.mode === 'move' || s.controller.mode === 'rotate' || !!s.adapter.dragging())
      window.addEventListener('keydown', onKey)
      sync()
      return true
    } catch (e) {
      app.fail(e, t('edit.startFailed'))
      return false
    } finally {
      starting.value = false
    }
  }

  function teardown() {
    window.removeEventListener('keydown', onKey)
    issues.detach()
    needsPreset.value = null
    session?.dispose()
    session = null
    active.value = false
    mode.value = 'browse'
    selected.value = []
    editCount.value = 0
    undoLabel.value = null
    redoLabel.value = null
    draftId.value = null
    draftTitle.value = null
    menu.value = null
    hover.value = null
    loadingTiles.value = 0
  }

  function exit(force = false) {
    if (!active.value) return
    if (!force && editCount.value > 0) {
      exitConfirm.value = true
      return
    }
    exitConfirm.value = false
    teardown()
  }

  function setMode(m: 'add-point' | 'add-line' | 'add-area') {
    if (!session) return
    menu.value = null
    if (mode.value === 'draw') session.controller.finishDrawing()
    session.controller.setMode(mode.value === m ? (selected.value.length ? 'select' : 'browse') : m)
  }

  function undo() {
    if (!session || !undoLabel.value) return
    session.adapter.undo()
    session.controller.afterUndoRedo()
    sync()
  }

  function redo() {
    if (!session || !redoLabel.value) return
    session.adapter.redo()
    session.controller.afterUndoRedo()
    sync()
  }

  function runOperation(id: string) {
    if (!session) return
    menu.value = null
    const ids = [...selected.value]
    const r = session.adapter.runOperation(id, ids)
    if (!r.ok && r.reason) app.toast(r.reason)
    if (r.ok && id !== 'move' && id !== 'rotate' && id !== 'continue') session.controller.select(session.adapter.selected)
    sync()
  }

  function setTags(id: string, tags: Tags) {
    session?.adapter.setTags(id, tags)
    sync()
  }

  function select(ids: string[]) {
    session?.controller.select(ids)
  }

  function onKey(e: KeyboardEvent) {
    if (!session || isTyping(e) || app.settingsOpen || saveDialog.value || takeOver.value || exitConfirm.value || issues.summary) return
    const mod = e.ctrlKey || e.metaKey
    const key = e.key.length === 1 ? e.key.toLowerCase() : e.key
    if (mod && key === 'z') {
      e.preventDefault()
      if (e.shiftKey) redo()
      else undo()
      return
    }
    if (mod && key === 'y') {
      e.preventDefault()
      redo()
      return
    }
    if (mod && key === 's') {
      e.preventDefault()
      void requestSave()
      return
    }
    if (((mod && key === 'Backspace') || (!mod && key === 'Delete')) && selected.value.length && (mode.value === 'select' || mode.value === 'browse')) {
      e.preventDefault()
      runOperation('delete')
      return
    }
    if (mod || e.altKey) return
    if (key === 'Escape') {
      if (menu.value) {
        menu.value = null
        e.preventDefault()
        return
      }
      if (session.controller.escape()) e.preventDefault()
      return
    }
    if (key === 'Enter' && mode.value === 'draw') {
      e.preventDefault()
      session.controller.finishDrawing()
      return
    }
    const modes: Record<string, 'add-point' | 'add-line' | 'add-area'> = { '1': 'add-point', '2': 'add-line', '3': 'add-area' }
    if (modes[key]) {
      e.preventDefault()
      setMode(modes[key])
      return
    }
    if (!selected.value.length || (mode.value !== 'select' && mode.value !== 'browse')) return
    const op = Object.entries(session.operationKeys).find(([, k]) => k === key)?.[0]
    if (op) {
      e.preventDefault()
      const info = operations.value.find((o) => o.id === op)
      if (!info) return
      if (info.disabled) app.toast(info.tooltip || t('edit.opUnavailable', { op: info.title }))
      else runOperation(op)
    }
  }

  async function requestSave() {
    if (!session || saving.value || issues.summary) return
    if (!editCount.value && !draftId.value) {
      app.toast(t('edit.nothingToSave'))
      return
    }
    if (mode.value === 'draw') session.controller.finishDrawing()
    if (!(await issues.confirmSave())) return
    if (draftId.value) void save(null)
    else saveDialog.value = true
  }

  function applyIssueFix(issueId: string, fixId: string) {
    const issue = issues.issues.find((i) => i.id === issueId)
    if (!issue || !session) return
    menu.value = null
    issues.applyFix(issue, fixId, sync)
  }

  function askTakeOver(openChanges: OpenChangeElsewhere[]): Promise<boolean> {
    return new Promise((resolve) => {
      takeOver.value = {
        openChanges,
        resolve: (ok) => {
          takeOver.value = null
          resolve(ok)
        },
      }
    })
  }

  async function submit(req: SaveRequest | null, take: boolean): Promise<OsmChangeImportResult> {
    const a = session!.adapter
    const osmChange = a.osmChange()
    if (draftId.value) return api.importOsmChange(draftId.value, { osmChange, replace: replaceSafe.value, takeOver: take })
    return api.draftFromOsmChange({
      title: req?.title || t('edit.defaultDraftTitle'),
      osmChange,
      changesetComment: req?.changesetComment || null,
      area: mapStore.area?.name ?? null,
      takeOver: take,
    })
  }

  function remaps(results: ProposalResult[]) {
    return results
      .filter((r) => r.sourceRef && refString(r.sourceRef) !== refString(r.ref))
      .map((r) => ({ from: refString(r.sourceRef!), to: refString(r.ref) }))
  }

  async function save(req: SaveRequest | null): Promise<boolean> {
    if (!session || saving.value) return false
    saving.value = true
    try {
      let result: OsmChangeImportResult
      try {
        result = await submit(req, false)
      } catch (e) {
        if (e instanceof ApiError && e.code === 'open_change_elsewhere' && e.openChanges?.length) {
          if (!(await askTakeOver(e.openChanges))) return false
          result = await submit(req, true)
        } else throw e
      }
      session.adapter.remapIds(remaps(result.results))
      saveDialog.value = false
      const id = result.draft.draft.id
      const failed = result.results.filter((r) => r.outcome === 'error')
      const saved = tn('edit.saved', result.results.length - failed.length, { title: result.draft.draft.title })
      app.toast(failed.length ? t('edit.savedWithFailures', { saved, failed: formatNumber(failed.length) }) : saved, failed.length ? 'error' : 'success')
      teardown()
      await drafts.loadDrafts()
      await drafts.open(id, { review: true })
      return true
    } catch (e) {
      app.toast(t('edit.saveFailed', { error: errorMessage(e) }), 'error', 10000)
      return false
    } finally {
      saving.value = false
    }
  }

  function draftBounds(d: DraftDetail): { west: number; south: number; east: number; north: number } | null {
    let west = Infinity
    let south = Infinity
    let east = -Infinity
    let north = -Infinity
    for (const c of d.changes) {
      if (c.status === 'rejected' || c.status === 'uploaded' || c.ref.type === 'relation') continue
      const b = geometryBBox(asGeometry(c.newGeom), asGeometry(c.baseGeom))
      const pts = b ? [[b.west, b.south], [b.east, b.north]] : c.centroid ? [[c.centroid.lng, c.centroid.lat]] : []
      for (const [x, y] of pts) {
        west = Math.min(west, x)
        east = Math.max(east, x)
        south = Math.min(south, y)
        north = Math.max(north, y)
      }
    }
    return Number.isFinite(west) ? { west, south, east, north } : null
  }

  async function editDraft(id: string) {
    if (active.value && editCount.value > 0 && draftId.value !== id) {
      app.toast(t('edit.finishCurrent'), 'error')
      return
    }
    try {
      const [detail, xml] = await Promise.all([api.draft(id), api.draftOsmChange(id)])
      const b = draftBounds(detail)
      if (b) {
        mapStore.fitBBox(b, 18, false)
        const map = mapStore.instance()
        if (map && map.getZoom() < EDIT_MIN_ZOOM) map.jumpTo({ zoom: EDIT_MIN_ZOOM })
      }
      if (active.value) teardown()
      if (!(await enter()) || !session) return
      const pad = 0.0005
      if (b) {
        const s = session
        const want = s.loader.request(
          [
            [b.west - pad, b.south - pad],
            [b.east + pad, b.north + pad],
          ],
          120,
        )
        if (want > 120) app.toast(t('edit.largeDraft'), 'error')
        await s.loader.idle()
        if (session !== s) return
      }
      const report = session.adapter.applyOsmChange(xml, t('edit.ann.loadedDraftTitle', { title: detail.draft.title }))
      draftId.value = id
      draftTitle.value = detail.draft.title
      replaceSafe.value = report.missing.length === 0
      session.controller.refreshHidden()
      session.controller.select([])
      sync()
      const parts = [tn('edit.loadedChanges', report.created + report.modified + report.deleted)]
      if (report.stale.length) parts.push(tn('edit.loadedStale', report.stale.length))
      if (report.missing.length) parts.push(tn('edit.loadedMissing', report.missing.length))
      app.toast(t('edit.editingDraft', { title: detail.draft.title, details: parts.join('; ') }), report.missing.length ? 'error' : 'success', 8000)
    } catch (e) {
      app.fail(e, t('edit.editDraftFailed'))
    }
  }

  function describe(id: string): EntitySnapshot | null {
    void revision.value
    return session?.adapter.snapshot(id) ?? null
  }

  return {
    active, starting, mode, selected, revision, editCount, undoLabel, redoLabel, loadingTiles, draftId, draftTitle,
    saving, saveDialog, exitConfirm, takeOver, menu, hover, notice, version, zoomOk, selection, operations, needsPreset,
    enter, exit, teardown, setMode, undo, redo, runOperation, setTags, select, requestSave, save, editDraft, describe,
    onNeedsPreset, requestPreset, clearNeedsPreset, applyIssueFix,
  }
})
