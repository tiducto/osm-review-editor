<script setup lang="ts">
import { useI18n } from '@/i18n'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDraftsStore } from '@/stores/drafts'
import { useRulesStore } from '@/stores/rules'
import { useAppStore } from '@/stores/app'
import { changeKind, geometrySummaryText, lastReview } from '@/lib/changes'
import { changeKindColor, changeKindLabel } from '@/lib/colors'
import { featureTitle, refString } from '@/lib/osm'
import { diffSize, tagDiff, tagDiffSummary } from '@/lib/tagdiff'
import { hasModifier, isTyping } from '@/lib/keyboard'
import { geometryOf } from '@/presets/catalog'
import type { Geometry, PresetDef, TagPatch } from '@/presets/types'
import { usePresetsStore } from '@/stores/presets'
import { relativeTime } from '@/lib/format'
import AuthorBadge from './AuthorBadge.vue'
import StatusPill from './StatusPill.vue'
import TagDiffView from './TagDiffView.vue'
import PresetEditor, { type EditableEntity } from './presets/PresetEditor.vue'
import PresetIcon from './presets/PresetIcon.vue'
import ConflictResolver from './ConflictResolver.vue'
import DraftChangeset from './DraftChangeset.vue'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import { useEditorStore } from '@/stores/editor'
import { usePanelStore } from '@/stores/panel'
import type { ChangeLink } from '@/generated/models'

const drafts = useDraftsStore()
const rules = useRulesStore()
const app = useAppStore()
const editor = useEditorStore()
const presets = usePresetsStore()
const { t, tn } = useI18n()
void presets.ensure().catch(() => {})

const editable = computed(() => draft.value?.status === 'open' || draft.value?.status === 'partially_merged')

function linkIndex(l: ChangeLink): number {
  return drafts.changes.findIndex((x) => x.id === l.changeId)
}

function goToLink(l: ChangeLink) {
  const i = linkIndex(l)
  if (i >= 0) drafts.goTo(i)
}

function actionLabel(a: string): string {
  return a === 'create' ? t('shell.review.action.create') : a === 'modify' ? t('shell.review.action.modify') : a === 'delete' ? t('shell.review.action.delete') : a
}

type Mode = 'view' | 'edit' | 'reject'
const mode = ref<Mode>('view')
const editTags = ref<Record<string, string>>({})
const comment = ref('')
const commentEl = ref<HTMLTextAreaElement | null>(null)
const cardEl = ref<HTMLElement | null>(null)

const draft = computed(() => drafts.current?.draft ?? null)
const c = computed(() => drafts.currentChange)
const total = computed(() => drafts.changes.length)
const decided = computed(() => drafts.decidedCount)
const allDone = computed(() => total.value > 0 && drafts.pendingCount === 0 && drafts.conflictCount === 0)

const kind = computed(() => (c.value ? changeKind(c.value) : null))
const rule = computed(() => (c.value?.ruleId ? rules.ruleById.get(c.value.ruleId) : undefined))
const diff = computed(() => (c.value ? c.value.diff : null))
const rejection = computed(() => (c.value?.status === 'rejected' ? lastReview(c.value, 'reject') : null))
const lastEdit = computed(() => (c.value ? lastReview(c.value, 'edit') : null))

const editDiff = computed(() => (c.value ? tagDiff(c.value.baseTags, editTags.value) : null))
function isClosed(g: unknown): boolean {
  const geom = g as { type?: string; coordinates?: number[][] } | null
  if (!geom) return false
  if (geom.type === 'Polygon' || geom.type === 'MultiPolygon') return true
  const cs = geom.type === 'LineString' ? (geom.coordinates ?? []) : []
  return cs.length > 3 && cs[0][0] === cs[cs.length - 1][0] && cs[0][1] === cs[cs.length - 1][1]
}

const changeGeometry = computed<Geometry>(() => {
  const cur = c.value
  if (!cur) return 'point'
  const cat = presets.catalog
  if (cur.ref.type === 'node') {
    if (!cat) return 'point'
    const tags = Object.keys(cur.newTags).length ? cur.newTags : cur.baseTags
    return cat.scoreOf(cat.match(tags, 'vertex'), tags) > cat.scoreOf(cat.match(tags, 'point'), tags) ? 'vertex' : 'point'
  }
  return geometryOf(cur.ref.type, cur.newTags, {
    closed: isClosed(cur.newGeom ?? cur.baseGeom),
    isArea: (t) => cat?.isArea(t) ?? false,
  })
})

const presetAfter = computed<PresetDef | null>(() => (c.value ? (presets.catalog?.match(c.value.newTags, changeGeometry.value) ?? null) : null))
const presetBefore = computed<PresetDef | null>(() => {
  const cur = c.value
  if (!cur || cur.action === 'create') return null
  const p = presets.catalog?.match(cur.baseTags, changeGeometry.value) ?? null
  return p && p.id !== presetAfter.value?.id ? p : null
})

const editEntities = computed<EditableEntity[]>(() =>
  c.value ? [{ id: refString(c.value.ref), tags: editTags.value, geometry: changeGeometry.value, baseTags: c.value.baseTags, title: featureTitle(c.value.ref, editTags.value) }] : [],
)

function onEditPatch(patch: TagPatch) {
  const next = { ...editTags.value }
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined || v === '') delete next[k]
    else next[k] = v
  }
  editTags.value = next
}

function onEditPreset(id: string) {
  const cat = presets.catalog
  const to = cat?.preset(id)
  if (!cat || !to) return
  editTags.value = cat.changePreset(editTags.value, changeGeometry.value, cat.match(editTags.value, changeGeometry.value), to)
  presets.useRecent(id)
}

const remainingSameRule = computed(() => {
  const cur = c.value
  if (!cur?.ruleId || cur.confidence !== 'deterministic') return 0
  return drafts.changes.filter((x) => x.ruleId === cur.ruleId && x.status === 'pending' && x.confidence === 'deterministic').length
})

const counts = computed(() => draft.value?.counts)

watch(
  () => c.value?.id,
  () => {
    mode.value = 'view'
    comment.value = ''
    cardEl.value?.scrollTo({ top: 0 })
  },
)

function startEdit() {
  if (!c.value || c.value.status === 'uploaded') return
  editTags.value = { ...c.value.newTags }
  mode.value = 'edit'
}

async function startReject() {
  if (!c.value || c.value.status === 'uploaded') return
  comment.value = rejection.value?.comment ?? ''
  mode.value = 'reject'
  await nextTick()
  commentEl.value?.focus()
}

async function submitReject() {
  if (await drafts.reject(comment.value.trim())) mode.value = 'view'
}

async function submitEdit() {
  if (!editDiff.value) return
  if (await drafts.editTags(editTags.value)) mode.value = 'view'
}

function cancel() {
  mode.value = 'view'
  ;(document.activeElement as HTMLElement | null)?.blur()
}

function showQueue() {
  drafts.drawerTab = 'changes'
  usePanelStore().open('drafts')
}

function onKey(e: KeyboardEvent) {
  if (app.mergeDraftId || drafts.cascadePrompt || editor.active) return
  if (mode.value === 'reject') {
    if (e.key === 'Escape') {
      e.preventDefault()
      cancel()
    } else if (e.key === 'Enter' && !e.shiftKey && e.target === commentEl.value) {
      e.preventDefault()
      void submitReject()
    }
    return
  }
  if (mode.value === 'edit') {
    if (e.key === 'Escape') {
      e.preventDefault()
      cancel()
    } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      void submitEdit()
    }
    return
  }
  if (isTyping(e) || hasModifier(e)) return
  const key = e.key.length === 1 ? e.key.toLowerCase() : e.key
  const actions: Record<string, () => void> = {
    Enter: () => c.value && c.value.status !== 'conflict' && c.value.status !== 'uploaded' && void drafts.approve(),
    e: startEdit,
    s: () => drafts.nextOpen(),
    r: () => void startReject(),
    o: () => c.value && void drafts.openInId(c.value.ref),
    j: () => drafts.next(),
    k: () => drafts.prev(),
    ArrowDown: () => drafts.next(),
    ArrowUp: () => drafts.prev(),
    ' ': () => (drafts.showBefore = !drafts.showBefore),
    f: () => drafts.focusCurrent(),
    Escape: () => drafts.stopReview(),
  }
  const fn = actions[key]
  if (fn) {
    e.preventDefault()
    fn()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

const progressSegments = computed(() => {
  const n = counts.value
  if (!n || !n.total) return []
  return [
    { cls: 'bg-added', w: n.approved / n.total, label: 'approved' },
    { cls: 'bg-removed', w: n.rejected / n.total, label: 'rejected' },
    { cls: 'bg-accent', w: n.uploaded / n.total, label: 'uploaded' },
    { cls: 'bg-changed', w: n.conflict / n.total, label: 'conflict' },
  ]
})

const shortcuts = computed(() => [
  ['↵', t('shell.review.key.approve')],
  ['E', t('shell.review.key.edit')],
  ['S', t('shell.review.key.skip')],
  ['R', t('shell.review.key.reject')],
  ['O', 'iD'],
  ['J/K', t('shell.review.key.nav')],
  ['Space', t('shell.review.key.beforeAfter')],
])
</script>

<template>
  <div v-if="draft" class="flex h-full flex-col">
    <div class="panel-header">
      <button
        v-if="editable"
        class="btn btn-sm"
        :title="t('shell.review.editDraftTitle')"
        data-testid="edit-draft"
        @click="editor.editDraft(draft.id)"
      >
        <Icon name="edit" :size="12" /> {{ t('shell.review.editDraft') }}
      </button>
      <span class="panel-title">{{ t('shell.review.title') }}</span>
      <button class="btn btn-ghost btn-sm text-fg-2" :title="t('shell.review.leaveTitle')" @click="drafts.stopReview()">
        {{ t('shell.review.exit') }} <span class="kbd">Esc</span>
      </button>
    </div>
    <div class="shrink-0 border-b border-line-strong bg-surface-2 px-4 pt-3 pb-2.5">
      <div class="flex min-w-0 items-center gap-2">
        <h2 class="min-w-0 flex-1 truncate text-[14px] font-bold text-fg" :title="draft.title">{{ draft.title }}</h2>
        <AuthorBadge :author="draft.author" />
      </div>
      <p v-if="draft.description" class="mt-0.5 line-clamp-2 text-[12px] text-fg-2" :title="draft.description">{{ draft.description }}</p>
      <DraftChangeset :draft="draft" />
      <div class="mt-2.5 flex items-center gap-3">
        <div class="flex h-1.5 flex-1 overflow-hidden rounded-sm bg-line">
          <div v-for="s in progressSegments" :key="s.label" :class="s.cls" class="h-full transition-[width] duration-300" :style="{ width: `${s.w * 100}%` }" />
        </div>
        <span class="font-mono text-[11.5px] font-bold text-fg-2 tabular-nums">{{ decided }}/{{ total }}</span>
      </div>
      <div class="mt-2 flex items-center gap-1">
        <button class="btn btn-ghost btn-icon btn-sm" :title="t('shell.review.prevTitle')" @click="drafts.prev()"><Icon name="chevron-up" :size="14" /></button>
        <button class="btn btn-ghost btn-icon btn-sm" :title="t('shell.review.nextTitle')" @click="drafts.next()"><Icon name="chevron-down" :size="14" /></button>
        <span class="text-[12px] text-fg-2">{{ t('shell.review.position', { i: drafts.index + 1, n: total }) }}</span>
        <button class="btn btn-ghost btn-icon btn-sm" :title="t('shell.queue.show')" data-testid="review-show-queue" @click="showQueue"><Icon name="list" :size="14" /></button>
        <span v-if="counts" class="ml-auto flex gap-2 text-[11.5px] text-fg-3">
          <span class="text-added">{{ counts.approved }} ✓</span>
          <span class="text-removed">{{ counts.rejected }} ✕</span>
          <span v-if="counts.conflict" class="text-changed">{{ counts.conflict }} !</span>
        </span>
      </div>
    </div>

    <div v-if="allDone && mode === 'view' && c?.status !== 'conflict'" class="shrink-0 border-b border-line-strong border-l-4 border-l-added bg-added-soft px-4 py-3">
      <div class="flex items-center gap-2 font-bold text-added"><Icon name="check" :size="15" :stroke="2.4" /> {{ t('shell.review.allDone') }}</div>
      <p class="mt-0.5 text-[12px] text-fg-2">{{ t('shell.review.allDoneBody', { approved: counts?.approved ?? 0, rejected: counts?.rejected ?? 0 }) }}</p>
      <button class="btn btn-primary mt-2" :disabled="!counts?.approved" @click="app.mergeDraftId = draft.id">
        <Icon name="merge" :size="13" /> {{ t('shell.review.merge') }}
      </button>
    </div>

    <div v-if="c" ref="cardEl" class="scroll-thin min-h-0 flex-1 overflow-y-auto bg-surface-2">
      <div class="space-y-3 px-4 py-3">
        <div>
          <div class="flex flex-wrap items-center gap-1.5">
            <span v-if="kind" class="chip" :style="{ background: `${changeKindColor[kind]}22`, color: changeKindColor[kind] }">
              <span class="size-1.5 rounded-full" :style="{ background: changeKindColor[kind] }" />{{ changeKindLabel[kind] }}
            </span>
            <StatusPill :status="c.status" />
            <span
              class="chip"
              :class="c.confidence === 'deterministic' ? 'bg-surface-3 text-fg-2' : 'bg-changed-soft text-changed'"
              :title="c.confidence === 'deterministic' ? t('shell.review.deterministicTitle') : t('shell.review.needsReviewTitle')"
            >
              {{ c.confidence === 'deterministic' ? t('shell.review.deterministic') : t('shell.review.needsReview') }}
            </span>
          </div>
          <div class="mt-2 flex items-center gap-2.5" data-testid="review-preset">
            <PresetIcon :icon="presetAfter?.icon" :size="34" />
            <div class="min-w-0 flex-1">
              <div class="flex min-w-0 items-center gap-1 text-[12px] text-fg-2">
                <template v-if="presetBefore">
                  <span class="truncate text-fg-3 line-through decoration-fg-3/50" :title="t('shell.review.presetChanged')" data-testid="review-preset-before">{{ presetBefore.name }}</span>
                  <Icon name="chevron-right" :size="11" class="shrink-0 text-fg-3" />
                </template>
                <span class="truncate" data-testid="review-preset-name">{{ presetAfter?.name ?? '' }}</span>
              </div>
              <h3 class="text-[16px] leading-snug font-bold text-fg break-words">{{ featureTitle(c.ref, c.newTags) }}</h3>
            </div>
          </div>
          <div class="mt-0.5 flex items-center gap-2 font-mono text-[11.5px] text-fg-3">
            <span data-testid="review-ref">{{ refString(c.ref) }}</span><span v-if="c.baseVersion">· v{{ c.baseVersion }}</span>
            <span class="font-sans">· {{ relativeTime(c.createdAt) }}</span>
          </div>
        </div>

        <div class="card space-y-1.5 p-2.5">
          <div v-if="c.ruleId" class="flex items-center gap-1.5 text-[12px]">
            <span class="size-2 rounded-full" :style="{ background: rules.colorOf.get(c.ruleId) ?? '#888' }" />
            <span class="font-bold">{{ rule?.title ?? c.ruleId }}</span>
          </div>
          <p v-if="c.reason" class="text-[12px] text-fg">{{ c.reason }}</p>
          <p v-else-if="!c.ruleId" class="text-[12px] text-fg-3">{{ t('shell.review.noReason') }}</p>
          <p v-if="drafts.context?.note && !c.reason?.includes(drafts.context.note)" class="text-[12px] text-fg-2">{{ drafts.context.note }}</p>
          <p v-if="drafts.context?.refs.length" class="flex items-center gap-1.5 text-[11.5px] text-fg-3">
            <span class="h-[3px] w-3 rounded-full bg-[#64748b] opacity-60" />
            {{ tn('shell.review.related', drafts.context.refs.length) }}
            <span class="truncate font-mono">{{ drafts.context.refs.map(refString).join(' ') }}</span>
          </p>
        </div>

        <div
          v-if="c.geometryChanged || c.geometrySummary || c.dependsOn.length || c.requiredBy.length"
          class="space-y-2 rounded-md border border-line-strong border-l-4 border-l-geom bg-geom-soft p-2.5 text-[12px]"
          data-testid="geometry-info"
        >
          <div v-if="c.geometryChanged || c.geometrySummary" class="flex items-center gap-2">
            <span class="section-title">{{ t('shell.review.geometry') }}</span>
            <span class="font-mono text-[11.5px] text-fg" data-testid="geometry-summary">{{ c.geometrySummary ? geometrySummaryText(c.geometrySummary) : (c.geometryChanged ? t('shell.review.geometryChanged') : '') }}</span>
            <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">Space</span> {{ drafts.showBefore ? t('shell.review.showingBefore') : t('shell.review.showingAfter') }}</span>
          </div>
          <div v-if="c.dependsOn.length">
            <div class="text-[11px] text-fg-3">{{ t('shell.review.needs') }}</div>
            <div class="mt-0.5 flex flex-wrap gap-1">
              <button
                v-for="l in c.dependsOn"
                :key="l.changeId"
                class="chip border border-line-strong bg-surface font-mono text-fg-2 hover:bg-surface-3 hover:text-fg"
                :disabled="linkIndex(l) < 0"
                :title="`${actionLabel(l.action)}, ${l.status}`"
                @click="goToLink(l)"
              >
                {{ refString(l.ref) }} <StatusPill :status="l.status" />
              </button>
            </div>
          </div>
          <div v-if="c.requiredBy.length">
            <div class="text-[11px] text-fg-3">{{ t('shell.review.requiredBy') }}</div>
            <div class="mt-0.5 flex flex-wrap gap-1">
              <button
                v-for="l in c.requiredBy"
                :key="l.changeId"
                class="chip border border-line-strong bg-surface font-mono text-fg-2 hover:bg-surface-3 hover:text-fg"
                :disabled="linkIndex(l) < 0"
                :title="`${actionLabel(l.action)}, ${l.status}`"
                @click="goToLink(l)"
              >
                {{ refString(l.ref) }} <StatusPill :status="l.status" />
              </button>
            </div>
          </div>
        </div>

        <div v-if="rejection" class="rounded-md border border-line-strong border-l-4 border-l-removed bg-removed-soft p-2.5 text-[12px]">
          <div class="font-bold text-removed">{{ t('shell.review.rejectedAt', { when: relativeTime(rejection.createdAt) }) }}</div>
          <p class="mt-0.5 text-fg">{{ rejection.comment || t('shell.review.noComment') }}</p>
        </div>
        <div v-else-if="lastEdit && c.status !== 'pending'" class="text-[11.5px] text-fg-3">{{ t('shell.review.editedAt', { when: relativeTime(lastEdit.createdAt) }) }}</div>

        <ConflictResolver v-if="c.status === 'conflict'" :change="c" />

        <template v-if="mode === 'edit'">
          <div class="-mx-4 flex flex-col border-y border-line-strong bg-surface" data-testid="review-edit">
            <PresetEditor :entities="editEntities" @patch="onEditPatch" @preset="onEditPreset" @tags="editTags = $event" />
          </div>
          <code v-if="editDiff" class="block truncate font-mono text-[11.5px] text-fg-2">{{ tagDiffSummary(c.baseTags, editTags) || t('shell.review.noChangesFromBase') }}</code>
          <div class="flex items-center gap-2">
            <button class="btn btn-primary" :disabled="drafts.busy || !editDiff || !diffSize(editDiff)" @click="submitEdit">
              <Spinner v-if="drafts.busy" :size="12" /> {{ t('shell.review.approveWithEdits') }}
            </button>
            <button class="btn btn-ghost" @click="cancel">{{ t('common.cancel') }}</button>
            <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">⌘</span> <span class="kbd">↵</span></span>
          </div>
        </template>

        <div v-else-if="diff && c.status !== 'conflict'">
          <div class="mb-1.5 flex items-center">
            <span class="section-title">{{ t('shell.review.tagChanges') }}</span>
            <span class="ml-auto font-mono text-[11px] text-fg-3">{{ tn('shell.review.keys', diffSize(diff)) }}</span>
          </div>
          <TagDiffView :diff="diff" :base="c.baseTags" />
        </div>

        <div v-if="mode === 'reject'" class="card space-y-2 border-l-4 border-l-removed p-2.5">
          <label class="block text-[12px] font-bold text-fg" for="reject-comment">{{ t('shell.review.whyReject') }} <span class="font-normal text-fg-3">{{ t('shell.review.whyRejectHint') }}</span></label>
          <textarea id="reject-comment" ref="commentEl" v-model="comment" class="input h-auto min-h-16 py-1.5" rows="3" :placeholder="t('shell.review.rejectPlaceholder')" />
          <div class="flex items-center gap-2">
            <button class="btn btn-danger" :disabled="drafts.busy" @click="submitReject">{{ t('shell.review.reject') }}</button>
            <button class="btn btn-ghost" @click="cancel">{{ t('common.cancel') }}</button>
            <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">↵</span> {{ t('shell.review.rejectKey') }} · <span class="kbd">⇧</span><span class="kbd">↵</span> {{ t('shell.review.newline') }}</span>
          </div>
        </div>

        <div v-if="remainingSameRule > 1 && mode === 'view'" class="card p-2.5">
          <button class="btn w-full" :disabled="drafts.busy" @click="c.ruleId && drafts.approveRemaining(c.ruleId)">
            <Icon name="bolt" :size="13" /> {{ t('shell.review.approveRemaining', { n: remainingSameRule }) }}
          </button>
          <p class="mt-1 text-center text-[11px] text-fg-3">{{ t('shell.review.approveRemainingHint') }}</p>
        </div>
      </div>
    </div>
    <div v-else class="grid flex-1 place-items-center text-fg-3">{{ t('shell.review.noChanges') }}</div>

    <ConfirmDialog
      v-if="drafts.cascadePrompt"
      :title="drafts.cascadePrompt.orphan ? t('shell.review.orphanTitle') : drafts.cascadePrompt.decision === 'approve' || drafts.cascadePrompt.decision === 'edit' ? t('shell.review.cascadeApproveTitle') : t('shell.review.cascadeRejectTitle')"
      :confirm-label="drafts.cascadePrompt.orphan ? t('shell.review.approveWithWay') : drafts.cascadePrompt.decision === 'approve' || drafts.cascadePrompt.decision === 'edit' ? t('shell.review.approveAll') : t('shell.review.rejectAll')"
      :danger="drafts.cascadePrompt.decision === 'reject' || drafts.cascadePrompt.decision === 'send_to_id'"
      @confirm="drafts.cascadePrompt.resolve(true)"
      @cancel="drafts.cascadePrompt.resolve(false)"
    >
      <p data-testid="cascade-message">{{ drafts.cascadePrompt.message }}</p>
      <p v-if="drafts.cascadePrompt.links.length" class="mt-2 font-mono text-[11.5px]" data-testid="cascade-refs">
        {{ drafts.cascadePrompt.links.map((l) => refString(l.ref)).join(' ') }}
      </p>
    </ConfirmDialog>

    <div v-if="c && mode === 'view'" class="shrink-0 border-t border-line-strong bg-surface px-3 py-2.5">
      <div class="@container grid grid-cols-[repeat(4,minmax(0,1fr))] gap-1">
        <button
          class="btn btn-primary h-8 min-w-0 gap-1 px-1.5"
          data-testid="review-approve"
          :disabled="drafts.busy || c.status === 'conflict' || c.status === 'uploaded' || c.status === 'approved'"
          @click="drafts.approve()"
        >
          <Spinner v-if="drafts.busy" :size="12" /><Icon v-else name="check" :size="14" :stroke="2.2" class="shrink-0" /> <span class="truncate">{{ t('shell.review.approve') }}</span> <span class="kbd hidden shrink-0 border-white/40 bg-white/20 text-white shadow-none @[440px]:inline-flex">↵</span>
        </button>
        <button class="btn h-8 min-w-0 gap-1 px-1.5" :disabled="c.status === 'uploaded'" :title="t('shell.review.editTitle')" @click="startEdit"><Icon name="edit" :size="13" class="hidden shrink-0 @[420px]:block" /> <span class="truncate">{{ t('common.edit') }}</span></button>
        <button class="btn h-8 min-w-0 gap-1 px-1.5" data-testid="review-skip" :title="t('shell.review.skipTitle')" @click="drafts.nextOpen()"><Icon name="skip" :size="13" class="hidden shrink-0 @[420px]:block" /> <span class="truncate">{{ t('shell.review.skip') }}</span></button>
        <button class="btn h-8 min-w-0 gap-1 px-1.5 hover:text-removed" :disabled="c.status === 'uploaded'" :title="t('shell.review.rejectTitle')" @click="startReject">
          <Icon name="x" :size="13" class="hidden shrink-0 @[420px]:block" /> <span class="truncate">{{ t('shell.review.reject') }}</span>
        </button>
      </div>
      <div class="mt-1.5 flex items-center gap-1">
        <button class="btn btn-ghost btn-sm" :title="t('shell.review.openInIdTitle')" @click="drafts.openInId(c.ref)"><Icon name="external" :size="12" /> {{ t('shell.review.openInId') }}</button>
        <button class="btn btn-ghost btn-sm" :title="t('shell.review.sendToIdTitle')" @click="drafts.sendToId()">{{ t('shell.review.sendToId') }}</button>
        <button
          v-if="c.status === 'approved' || c.status === 'rejected'"
          class="btn btn-ghost btn-sm"
          :title="t('shell.review.undoTitle')"
          :disabled="drafts.busy"
          @click="drafts.reset()"
        >
          <Icon name="undo" :size="12" /> {{ t('common.undo') }}
        </button>
        <button class="btn btn-ghost btn-sm ml-auto" :title="t('shell.review.zoomTitle')" @click="drafts.focusCurrent()"><Icon name="target" :size="12" /></button>
      </div>
      <div class="mt-2 flex flex-wrap gap-x-2.5 gap-y-1 text-[11px] text-fg-3">
        <span v-for="[k, l] in shortcuts" :key="k" class="flex items-center gap-1"><span class="kbd">{{ k }}</span>{{ l }}</span>
      </div>
    </div>
  </div>
</template>
