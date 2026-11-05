<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDraftsStore } from '@/stores/drafts'
import { useRulesStore } from '@/stores/rules'
import { useAppStore } from '@/stores/app'
import { changeKind, lastReview } from '@/lib/changes'
import { changeKindColor, changeKindLabel } from '@/lib/colors'
import { featureTitle, refString } from '@/lib/osm'
import { diffSize, tagDiff, tagDiffSummary } from '@/lib/tagdiff'
import { hasModifier, isTyping } from '@/lib/keyboard'
import { matchPreset } from '@/lib/presets'
import { relativeTime } from '@/lib/format'
import AuthorBadge from './AuthorBadge.vue'
import StatusPill from './StatusPill.vue'
import TagDiffView from './TagDiffView.vue'
import TagEditor from './TagEditor.vue'
import PresetFields from './PresetFields.vue'
import ConflictResolver from './ConflictResolver.vue'
import DraftChangeset from './DraftChangeset.vue'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import { useEditorStore } from '@/stores/editor'
import type { ChangeLink } from '@/generated/models'

const drafts = useDraftsStore()
const rules = useRulesStore()
const app = useAppStore()
const editor = useEditorStore()

const editable = computed(() => draft.value?.status === 'open' || draft.value?.status === 'partially_merged')

function linkIndex(l: ChangeLink): number {
  return drafts.changes.findIndex((x) => x.id === l.changeId)
}

function goToLink(l: ChangeLink) {
  const i = linkIndex(l)
  if (i >= 0) drafts.goTo(i)
}

const actionLabel: Record<string, string> = { create: 'new', modify: 'modified', delete: 'deleted' }

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
const editPreset = computed(() => matchPreset(editTags.value))

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

function onKey(e: KeyboardEvent) {
  if (app.settingsOpen || app.mergeDraftId || drafts.cascadePrompt || editor.active) return
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

const shortcuts = [
  ['↵', 'Approve'],
  ['E', 'Edit'],
  ['S', 'Skip'],
  ['R', 'Reject'],
  ['O', 'iD'],
  ['J/K', 'Next/prev'],
  ['Space', 'Before/after'],
]
</script>

<template>
  <div v-if="draft" class="flex h-full flex-col">
    <div class="shrink-0 border-b border-line px-4 pt-3 pb-2.5">
      <div class="flex items-center gap-2">
        <span class="section-title">Review</span>
        <AuthorBadge :author="draft.author" />
        <button
          v-if="editable"
          class="btn btn-sm ml-auto"
          title="Continue editing this draft's geometry in the map editor"
          data-testid="edit-draft"
          @click="editor.editDraft(draft.id)"
        >
          <Icon name="edit" :size="12" /> Edit draft
        </button>
        <button class="btn btn-ghost btn-sm text-fg-3" :class="!editable && 'ml-auto'" title="Leave review (Esc)" @click="drafts.stopReview()">
          Exit <span class="kbd">Esc</span>
        </button>
      </div>
      <h2 class="mt-1 truncate text-[14px] font-semibold" :title="draft.title">{{ draft.title }}</h2>
      <p v-if="draft.description" class="mt-0.5 line-clamp-2 text-[12px] text-fg-2" :title="draft.description">{{ draft.description }}</p>
      <DraftChangeset :draft="draft" />
      <div class="mt-2.5 flex items-center gap-3">
        <div class="flex h-1.5 flex-1 overflow-hidden rounded-full bg-surface-3">
          <div v-for="s in progressSegments" :key="s.label" :class="s.cls" class="h-full transition-[width] duration-300" :style="{ width: `${s.w * 100}%` }" />
        </div>
        <span class="font-mono text-[11.5px] text-fg-2 tabular-nums">{{ decided }}/{{ total }}</span>
      </div>
      <div class="mt-2 flex items-center gap-1">
        <button class="btn btn-ghost btn-icon btn-sm" title="Previous (K)" @click="drafts.prev()"><Icon name="chevron-up" :size="14" /></button>
        <button class="btn btn-ghost btn-icon btn-sm" title="Next (J)" @click="drafts.next()"><Icon name="chevron-down" :size="14" /></button>
        <span class="text-[12px] text-fg-2">Change <b class="font-semibold text-fg">{{ drafts.index + 1 }}</b> of {{ total }}</span>
        <span v-if="counts" class="ml-auto flex gap-2 text-[11.5px] text-fg-3">
          <span class="text-added">{{ counts.approved }} ✓</span>
          <span class="text-removed">{{ counts.rejected }} ✕</span>
          <span v-if="counts.conflict" class="text-changed">{{ counts.conflict }} !</span>
        </span>
      </div>
    </div>

    <div v-if="allDone && mode === 'view' && c?.status !== 'conflict'" class="shrink-0 border-b border-line bg-added-soft px-4 py-3">
      <div class="flex items-center gap-2 font-semibold text-added"><Icon name="check" :size="15" :stroke="2.4" /> Everything reviewed</div>
      <p class="mt-0.5 text-[12px] text-fg-2">{{ counts?.approved }} approved, {{ counts?.rejected }} rejected. Ready to upload the approved changes.</p>
      <button class="btn btn-primary mt-2" :disabled="!counts?.approved" @click="app.mergeDraftId = draft.id">
        <Icon name="merge" :size="13" /> Merge…
      </button>
    </div>

    <div v-if="c" ref="cardEl" class="scroll-thin min-h-0 flex-1 overflow-y-auto">
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
              :title="c.confidence === 'deterministic' ? 'The rule is certain about this fix' : 'The fix needs a human look'"
            >
              {{ c.confidence === 'deterministic' ? 'Deterministic' : 'Needs review' }}
            </span>
          </div>
          <h3 class="mt-1.5 text-[16px] leading-snug font-semibold break-words">{{ featureTitle(c.ref, c.newTags) }}</h3>
          <div class="mt-0.5 flex items-center gap-2 font-mono text-[11.5px] text-fg-3">
            {{ refString(c.ref) }}<span v-if="c.baseVersion">· v{{ c.baseVersion }}</span>
            <span class="font-sans">· {{ relativeTime(c.createdAt) }}</span>
          </div>
        </div>

        <div class="space-y-1.5 rounded-md border border-line bg-surface-2 p-2.5">
          <div v-if="c.ruleId" class="flex items-center gap-1.5 text-[12px]">
            <span class="size-2 rounded-full" :style="{ background: rules.colorOf.get(c.ruleId) ?? '#888' }" />
            <span class="font-medium">{{ rule?.title ?? c.ruleId }}</span>
          </div>
          <p v-if="c.reason" class="text-[12.5px] text-fg">{{ c.reason }}</p>
          <p v-else-if="!c.ruleId" class="text-[12px] text-fg-3">No reason given.</p>
          <p v-if="drafts.context?.note && !c.reason?.includes(drafts.context.note)" class="text-[12px] text-fg-2">{{ drafts.context.note }}</p>
          <p v-if="drafts.context?.refs.length" class="flex items-center gap-1.5 text-[11.5px] text-fg-3">
            <span class="h-[3px] w-3 rounded-full bg-[#64748b] opacity-60" />
            {{ drafts.context.refs.length }} related object{{ drafts.context.refs.length === 1 ? '' : 's' }} highlighted:
            <span class="truncate font-mono">{{ drafts.context.refs.map(refString).join(' ') }}</span>
          </p>
        </div>

        <div
          v-if="c.geometryChanged || c.geometrySummary || c.dependsOn.length || c.requiredBy.length"
          class="space-y-2 rounded-md border border-geom/30 bg-geom-soft p-2.5 text-[12px]"
          data-testid="geometry-info"
        >
          <div v-if="c.geometryChanged || c.geometrySummary" class="flex items-center gap-2">
            <span class="section-title">Geometry</span>
            <span class="font-mono text-[11.5px] text-fg" data-testid="geometry-summary">{{ c.geometrySummary ?? (c.geometryChanged ? 'changed' : '') }}</span>
            <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">Space</span> {{ drafts.showBefore ? 'showing before' : 'showing after' }}</span>
          </div>
          <div v-if="c.dependsOn.length">
            <div class="text-[11px] text-fg-3">Needs (uploaded together, approve these too)</div>
            <div class="mt-0.5 flex flex-wrap gap-1">
              <button
                v-for="l in c.dependsOn"
                :key="l.changeId"
                class="chip bg-surface font-mono text-fg-2 hover:text-fg"
                :disabled="linkIndex(l) < 0"
                :title="`${actionLabel[l.action] ?? l.action}, ${l.status}`"
                @click="goToLink(l)"
              >
                {{ refString(l.ref) }} <StatusPill :status="l.status" />
              </button>
            </div>
          </div>
          <div v-if="c.requiredBy.length">
            <div class="text-[11px] text-fg-3">Required by (rejecting this rejects them)</div>
            <div class="mt-0.5 flex flex-wrap gap-1">
              <button
                v-for="l in c.requiredBy"
                :key="l.changeId"
                class="chip bg-surface font-mono text-fg-2 hover:text-fg"
                :disabled="linkIndex(l) < 0"
                :title="`${actionLabel[l.action] ?? l.action}, ${l.status}`"
                @click="goToLink(l)"
              >
                {{ refString(l.ref) }} <StatusPill :status="l.status" />
              </button>
            </div>
          </div>
        </div>

        <div v-if="rejection" class="rounded-md border border-removed/30 bg-removed-soft p-2.5 text-[12px]">
          <div class="font-semibold text-removed">Rejected {{ relativeTime(rejection.createdAt) }}</div>
          <p class="mt-0.5 text-fg">{{ rejection.comment || 'No comment.' }}</p>
        </div>
        <div v-else-if="lastEdit && c.status !== 'pending'" class="text-[11.5px] text-fg-3">Tags edited by you {{ relativeTime(lastEdit.createdAt) }}.</div>

        <ConflictResolver v-if="c.status === 'conflict'" :change="c" />

        <template v-if="mode === 'edit'">
          <div v-if="editPreset" class="rounded-md border border-line p-2.5">
            <div class="section-title mb-2">{{ editPreset.name }}</div>
            <PresetFields v-model="editTags" :preset="editPreset" />
          </div>
          <TagEditor v-model="editTags" :base="c.baseTags">
            <template #title><span class="section-title">Edit tags</span></template>
          </TagEditor>
          <code v-if="editDiff" class="block truncate font-mono text-[11.5px] text-fg-2">{{ tagDiffSummary(c.baseTags, editTags) || 'No changes from base' }}</code>
          <div class="flex items-center gap-2">
            <button class="btn btn-primary" :disabled="drafts.busy || !editDiff || !diffSize(editDiff)" @click="submitEdit">
              <Spinner v-if="drafts.busy" :size="12" /> Approve with edits
            </button>
            <button class="btn btn-ghost" @click="cancel">Cancel</button>
            <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">⌘</span> <span class="kbd">↵</span></span>
          </div>
        </template>

        <div v-else-if="diff && c.status !== 'conflict'">
          <div class="mb-1.5 flex items-center">
            <span class="section-title">Tag changes</span>
            <span class="ml-auto font-mono text-[11px] text-fg-3">{{ diffSize(diff) }} key{{ diffSize(diff) === 1 ? '' : 's' }}</span>
          </div>
          <TagDiffView :diff="diff" :base="c.baseTags" />
        </div>

        <div v-if="mode === 'reject'" class="space-y-2 rounded-md border border-removed/40 p-2.5">
          <label class="text-[12px] font-medium" for="reject-comment">Why reject? <span class="font-normal text-fg-3">(feeds back to the rule or Claude)</span></label>
          <textarea id="reject-comment" ref="commentEl" v-model="comment" class="input h-auto min-h-16 py-1.5" rows="3" placeholder="e.g. the sidewalk is mapped separately" />
          <div class="flex items-center gap-2">
            <button class="btn btn-danger" :disabled="drafts.busy" @click="submitReject">Reject</button>
            <button class="btn btn-ghost" @click="cancel">Cancel</button>
            <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">↵</span> reject · <span class="kbd">⇧</span><span class="kbd">↵</span> newline</span>
          </div>
        </div>

        <div v-if="remainingSameRule > 1 && mode === 'view'" class="rounded-md border border-dashed border-line-strong p-2.5">
          <button class="btn w-full" :disabled="drafts.busy" @click="c.ruleId && drafts.approveRemaining(c.ruleId)">
            <Icon name="bolt" :size="13" /> Approve all {{ remainingSameRule }} remaining from this rule
          </button>
          <p class="mt-1 text-center text-[11px] text-fg-3">Only deterministic, still-pending changes</p>
        </div>
      </div>
    </div>
    <div v-else class="grid flex-1 place-items-center text-fg-3">This draft has no changes.</div>

    <ConfirmDialog
      v-if="drafts.cascadePrompt"
      :title="drafts.cascadePrompt.decision === 'approve' || drafts.cascadePrompt.decision === 'edit' ? 'Approve the dependencies too?' : 'Reject the dependent changes too?'"
      :confirm-label="drafts.cascadePrompt.decision === 'approve' || drafts.cascadePrompt.decision === 'edit' ? 'Approve all' : 'Reject all'"
      :danger="drafts.cascadePrompt.decision === 'reject' || drafts.cascadePrompt.decision === 'send_to_id'"
      @confirm="drafts.cascadePrompt.resolve(true)"
      @cancel="drafts.cascadePrompt.resolve(false)"
    >
      <p>{{ drafts.cascadePrompt.message }}</p>
      <p v-if="drafts.cascadePrompt.links.length" class="mt-2 font-mono text-[11.5px]" data-testid="cascade-refs">
        {{ drafts.cascadePrompt.links.map((l) => refString(l.ref)).join(' ') }}
      </p>
    </ConfirmDialog>

    <div v-if="c && mode === 'view'" class="shrink-0 border-t border-line bg-surface-2 px-3 py-2.5">
      <div class="grid grid-cols-[1.4fr_1fr_1fr_1fr] gap-1.5">
        <button
          class="btn btn-primary h-8"
          :disabled="drafts.busy || c.status === 'conflict' || c.status === 'uploaded' || c.status === 'approved'"
          @click="drafts.approve()"
        >
          <Spinner v-if="drafts.busy" :size="12" /><Icon v-else name="check" :size="14" :stroke="2.2" /> Approve <span class="kbd border-white/30 bg-white/15 text-white">↵</span>
        </button>
        <button class="btn h-8" :disabled="c.status === 'uploaded'" title="Edit tags (E)" @click="startEdit"><Icon name="edit" :size="13" /> Edit</button>
        <button class="btn h-8" title="Skip (S)" @click="drafts.nextOpen()"><Icon name="skip" :size="13" /> Skip</button>
        <button class="btn h-8 hover:text-removed" :disabled="c.status === 'uploaded'" title="Reject with comment (R)" @click="startReject">
          <Icon name="x" :size="13" /> Reject
        </button>
      </div>
      <div class="mt-1.5 flex items-center gap-1">
        <button class="btn btn-ghost btn-sm" title="Open in iD (O)" @click="drafts.openInId(c.ref)"><Icon name="external" :size="12" /> Open in iD</button>
        <button class="btn btn-ghost btn-sm" title="Hand this change off to iD and mark it" @click="drafts.sendToId()">Send to iD</button>
        <button
          v-if="c.status === 'approved' || c.status === 'rejected'"
          class="btn btn-ghost btn-sm"
          title="Put back to pending"
          :disabled="drafts.busy"
          @click="drafts.reset()"
        >
          <Icon name="undo" :size="12" /> Undo
        </button>
        <button class="btn btn-ghost btn-sm ml-auto" title="Zoom to change (F)" @click="drafts.focusCurrent()"><Icon name="target" :size="12" /></button>
      </div>
      <div class="mt-2 flex flex-wrap gap-x-2.5 gap-y-1 text-[10.5px] text-fg-3">
        <span v-for="[k, l] in shortcuts" :key="k" class="flex items-center gap-1"><span class="kbd">{{ k }}</span>{{ l }}</span>
      </div>
    </div>
  </div>
</template>
