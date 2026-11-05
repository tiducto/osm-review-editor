import { defineStore } from 'pinia'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import type { Change, Draft, DraftDetail, DraftStatus, MapFeature, OsmRef, ReviewRequest } from '@/generated/models'
import type { MergeProgress } from '@/generated/models'
import { api, ApiError } from '@/api/client'
import { useAppStore } from './app'
import { useMapStore } from './map'
import { useRulesStore } from './rules'
import { asGeometry, bboxAround, geometryBBox } from '@/lib/geo'
import { isDecided } from '@/lib/changes'
import { refString } from '@/lib/osm'
import { parseAuthor, type AuthorKind } from '@/lib/authors'
import { t, tn } from '@/i18n'

export type DrawerTab = 'drafts' | 'changes'

export interface ChangeContext {
  changeId: string
  note: string | null
  refs: OsmRef[]
  features: MapFeature[]
}

export const useDraftsStore = defineStore('drafts', () => {
  const app = useAppStore()
  const mapStore = useMapStore()
  const rulesStore = useRulesStore()

  const drafts = ref<Draft[]>([])
  const listLoading = ref(false)
  const statusFilter = ref<DraftStatus | ''>('open')
  const authorFilter = ref<AuthorKind | ''>('')
  const visibleDrafts = computed(() =>
    authorFilter.value ? drafts.value.filter((d) => parseAuthor(d.author).kind === authorFilter.value) : drafts.value,
  )

  const current = shallowRef<DraftDetail | null>(null)
  const currentLoading = ref(false)
  const reviewing = ref(false)
  const index = ref(0)
  const showBefore = ref(false)
  const busy = ref(false)
  const context = shallowRef<ChangeContext | null>(null)

  const drawerTab = ref<DrawerTab>('drafts')

  const progress = reactive(new Map<string, MergeProgress>())

  const changes = computed<Change[]>(() => current.value?.changes ?? [])
  const currentChange = computed<Change | null>(() => changes.value[index.value] ?? null)
  const decidedCount = computed(() => changes.value.filter(isDecided).length)
  const pendingCount = computed(() => changes.value.filter((c) => c.status === 'pending').length)
  const approvedCount = computed(() => changes.value.filter((c) => c.status === 'approved').length)
  const conflictCount = computed(() => changes.value.filter((c) => c.status === 'conflict').length)

  async function loadDrafts() {
    listLoading.value = true
    try {
      drafts.value = await api.drafts({ status: statusFilter.value || undefined })
    } catch (e) {
      app.fail(e, t('drafts.err.load'))
    } finally {
      listLoading.value = false
    }
  }
  watch(statusFilter, () => void loadDrafts())

  function setDetail(d: DraftDetail) {
    current.value = d
    const i = drafts.value.findIndex((x) => x.id === d.draft.id)
    if (i >= 0) drafts.value.splice(i, 1, d.draft)
    if (index.value >= d.changes.length) index.value = Math.max(0, d.changes.length - 1)
  }

  async function open(id: string, opts: { review?: boolean; changeId?: string } = {}) {
    currentLoading.value = true
    try {
      const d = await api.draft(id)
      const switching = current.value?.draft.id !== id
      setDetail(d)
      if (switching) {
        const firstOpen = d.changes.findIndex((c) => !isDecided(c))
        index.value = Math.max(0, firstOpen)
      }
      if (opts.changeId) {
        const i = d.changes.findIndex((c) => c.id === opts.changeId)
        if (i >= 0) index.value = i
      }
      if (opts.review ?? true) startReview()
      drawerTab.value = 'changes'
    } catch (e) {
      app.fail(e, t('drafts.err.open'))
    } finally {
      currentLoading.value = false
    }
  }

  function startReview() {
    if (!current.value) return
    reviewing.value = true
    showBefore.value = false
    mapStore.select(null)
    focusCurrent()
  }

  function stopReview() {
    reviewing.value = false
    showBefore.value = false
    context.value = null
  }

  function close() {
    stopReview()
    current.value = null
    drawerTab.value = 'drafts'
  }

  function focusCurrent() {
    const c = currentChange.value
    if (!c) return
    const b = geometryBBox(asGeometry(c.newGeom), asGeometry(c.baseGeom))
    if (b) mapStore.fitBBox(b, 18.5)
    else if (c.centroid) mapStore.flyTo(c.centroid, 18)
  }

  function goTo(i: number) {
    if (!changes.value.length) return
    index.value = (i + changes.value.length) % changes.value.length
    showBefore.value = false
    focusCurrent()
  }
  const next = () => goTo(index.value + 1)
  const prev = () => goTo(index.value - 1)

  function nextOpen() {
    const n = changes.value.length
    for (let k = 1; k <= n; k++) {
      const i = (index.value + k) % n
      if (!isDecided(changes.value[i])) return goTo(i)
    }
  }

  function replaceChange(c: Change) {
    if (!current.value) return
    const list = current.value.changes.map((x) => (x.id === c.id ? c : x))
    current.value = { ...current.value, changes: list, draft: { ...current.value.draft, counts: recount(list) } }
  }

  function recount(list: Change[]): Draft['counts'] {
    const counts = { total: list.length, pending: 0, approved: 0, rejected: 0, conflict: 0, uploaded: 0 }
    for (const c of list) counts[c.status]++
    return counts
  }

  const cascadePrompt = shallowRef<{ decision: ReviewRequest['decision']; message: string; links: Change['dependsOn']; resolve: (ok: boolean) => void } | null>(null)

  function askCascade(c: Change, req: ReviewRequest, message: string): Promise<boolean> {
    const links = req.decision === 'approve' || req.decision === 'edit' ? c.dependsOn : c.requiredBy
    return new Promise((resolve) => {
      cascadePrompt.value = {
        decision: req.decision,
        message,
        links,
        resolve: (ok) => {
          cascadePrompt.value = null
          resolve(ok)
        },
      }
    })
  }

  async function review(req: ReviewRequest, advance = true): Promise<boolean> {
    const c = currentChange.value
    if (!c || busy.value) return false
    busy.value = true
    try {
      try {
        replaceChange(await api.review(c.id, req))
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 409 || req.cascade || req.decision === 'reset') throw e
        busy.value = false
        if (!(await askCascade(c, req, e.detail ?? e.error))) return false
        busy.value = true
        replaceChange(await api.review(c.id, { ...req, cascade: true }))
        setDetail(await api.draft(c.draftId))
      }
      if (advance) nextOpen()
      return true
    } catch (e) {
      app.fail(e, t('drafts.err.review'))
      return false
    } finally {
      busy.value = false
    }
  }

  function imageryTag(): string | null {
    const i = mapStore.activeImagery
    if (!i) return null
    return i.sourceTag.trim() || i.name
  }

  const approve = () => review({ decision: 'approve', imagery: imageryTag() })
  const reject = (comment: string) => review({ decision: 'reject', comment: comment || null })
  const editTags = (tags: Record<string, string>, comment?: string) =>
    review({ decision: 'edit', tags, comment: comment || null, imagery: imageryTag() })
  const reset = () => review({ decision: 'reset' }, false)

  async function sendToId() {
    const ok = await review({ decision: 'send_to_id' }, false)
    if (ok && currentChange.value) await openInId(currentChange.value.ref)
  }

  async function resolve(tags: Record<string, string>) {
    const c = currentChange.value
    if (!c) return
    busy.value = true
    try {
      replaceChange(await api.resolve(c.id, { tags }))
      app.toast(t('drafts.toast.resolved'), 'success')
    } catch (e) {
      app.fail(e, t('drafts.err.resolve'))
    } finally {
      busy.value = false
    }
  }

  async function approveRemaining(ruleId: string) {
    const d = current.value
    if (!d) return
    busy.value = true
    try {
      const before = d.changes.filter((c) => c.status === 'approved').length
      setDetail(await api.approveRemaining(d.draft.id, ruleId))
      const after = current.value?.changes.filter((c) => c.status === 'approved').length ?? before
      app.toast(tn('drafts.toast.approvedRemaining', after - before), 'success')
      nextOpen()
    } catch (e) {
      app.fail(e, t('drafts.err.approveRemaining'))
    } finally {
      busy.value = false
    }
  }

  async function refreshLive() {
    const d = current.value
    if (!d) return
    busy.value = true
    try {
      setDetail(await api.refreshDraft(d.draft.id))
      app.toast(t('drafts.toast.refreshed'), 'success')
    } catch (e) {
      app.fail(e, t('drafts.err.refresh'))
    } finally {
      busy.value = false
    }
  }

  async function fromRule(ruleId: string) {
    const area = mapStore.area?.name ?? null
    const polygon = area ? null : mapStore.polygon
    const bbox = area || polygon ? null : mapStore.bbox
    if (!area && !polygon && !bbox) return
    busy.value = true
    try {
      const d = await api.draftFromRule(polygon ? { ruleId, polygon } : area ? { ruleId, area } : { ruleId, bbox })
      drafts.value = [d.draft, ...drafts.value.filter((x) => x.id !== d.draft.id)]
      current.value = d
      index.value = 0
      drawerTab.value = 'changes'
      if (d.changes.length) {
        app.toast(tn('drafts.toast.created', d.changes.length), 'success')
        startReview()
      } else {
        app.toast(t('drafts.toast.nothingToFix'))
      }
    } catch (e) {
      app.fail(e, t('drafts.err.create'))
    } finally {
      busy.value = false
    }
  }

  async function setStatus(id: string, status: DraftStatus) {
    try {
      const d = await api.updateDraft(id, { status })
      const i = drafts.value.findIndex((x) => x.id === id)
      if (i >= 0) drafts.value.splice(i, 1, d)
      if (current.value?.draft.id === id) current.value = { ...current.value, draft: d }
      if (statusFilter.value && d.status !== statusFilter.value) drafts.value = drafts.value.filter((x) => x.id !== id)
    } catch (e) {
      app.fail(e, t('drafts.err.update'))
    }
  }

  async function updateChangeset(id: string, comment: string, source: string): Promise<boolean> {
    try {
      const d = await api.updateDraft(id, { changesetComment: comment.trim(), changesetSource: source.trim() })
      const i = drafts.value.findIndex((x) => x.id === id)
      if (i >= 0) drafts.value.splice(i, 1, d)
      if (current.value?.draft.id === id) current.value = { ...current.value, draft: d }
      app.toast(t('drafts.toast.changesetSaved'), 'success')
      return true
    } catch (e) {
      app.fail(e, t('drafts.err.changeset'))
      return false
    }
  }

  async function rename(id: string, title: string) {
    try {
      const d = await api.updateDraft(id, { title })
      const i = drafts.value.findIndex((x) => x.id === id)
      if (i >= 0) drafts.value.splice(i, 1, d)
      if (current.value?.draft.id === id) current.value = { ...current.value, draft: d }
    } catch (e) {
      app.fail(e, t('drafts.err.rename'))
    }
  }

  async function onDraftUpdated(id: string) {
    await loadDrafts()
    if (current.value?.draft.id === id) {
      try {
        setDetail(await api.draft(id))
      } catch {}
    }
  }

  function onMergeProgress(p: MergeProgress) {
    progress.set(p.draftId, p)
    if (p.finished) {
      void onDraftUpdated(p.draftId)
      if (p.failed) app.toast(t('drafts.toast.uploadFailed', { message: p.message }), 'error')
      else app.toast(t('drafts.toast.uploadFinished'), 'success')
    }
  }

  let contextSeq = 0
  watch(
    () => (reviewing.value ? currentChange.value?.id : undefined),
    async () => {
      const c = currentChange.value
      const seq = ++contextSeq
      if (!c || !c.ruleId || !reviewing.value) {
        context.value = null
        return
      }
      if (context.value?.changeId === c.id) return
      context.value = { changeId: c.id, note: null, refs: [], features: [] }
      const near = c.centroid ? bboxAround(c.centroid, 120) : geometryBBox(asGeometry(c.baseGeom))
      const issue = await rulesStore.findIssue(c.ruleId, c.ref, near)
      if (seq !== contextSeq || !issue) return
      const refs = issue.context.slice(0, 12)
      context.value = { changeId: c.id, note: issue.note, refs, features: [] }
      const settled = await Promise.allSettled(refs.map((r) => api.object(r)))
      if (seq !== contextSeq) return
      const features = settled.flatMap((s) => (s.status === 'fulfilled' ? [s.value] : []))
      context.value = { changeId: c.id, note: issue.note, refs, features }
    },
  )

  const savingManual = ref(false)
  async function saveManualEdit(ref: OsmRef, tags: Record<string, string>, baseTags: Record<string, string>, reason?: string) {
    savingManual.value = true
    try {
      const c = await api.manualEdit({ ref, tags, baseTags, reason: reason || null })
      if (c.status === 'conflict') {
        app.toast(t('drafts.toast.manualConflict', { ref: refString(ref) }), 'error', 9000)
      } else {
        mapStore.patchSelectedTags(c.newTags)
        app.toast(t('drafts.toast.manualSaved', { ref: refString(ref) }), 'success')
      }
      if (current.value?.draft.id === c.draftId) {
        if (current.value.changes.some((x) => x.id === c.id)) replaceChange(c)
        else void onDraftUpdated(c.draftId)
      }
      await loadDrafts()
      return c
    } catch (e) {
      app.fail(e, t('drafts.err.save'))
      return null
    } finally {
      savingManual.value = false
    }
  }

  async function openInId(ref: OsmRef) {
    const w = window.open('about:blank', '_blank')
    try {
      const links = await api.links(ref)
      if (w) {
        w.opener = null
        w.location.href = links.iD
      } else {
        window.open(links.iD, '_blank', 'noopener')
      }
    } catch (e) {
      w?.close()
      app.fail(e, t('drafts.err.openInId'))
    }
  }

  return {
    drafts, visibleDrafts, listLoading, statusFilter, authorFilter,
    current, currentLoading, reviewing, index, showBefore, busy, context,
    drawerTab, progress,
    changes, currentChange, decidedCount, pendingCount, approvedCount, conflictCount,
    loadDrafts, open, startReview, stopReview, close, focusCurrent, goTo, next, prev, nextOpen,
    review, cascadePrompt, approve, reject, editTags, reset, sendToId, resolve, approveRemaining, refreshLive, fromRule,
    setStatus, rename, updateChangeset, onDraftUpdated, onMergeProgress,
    savingManual, saveManualEdit, openInId,
  }
})
