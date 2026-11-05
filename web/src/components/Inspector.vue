<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Change, EditorLinks, Issue } from '@/generated/models'
import { api } from '@/api/client'
import { useMapStore } from '@/stores/map'
import { useRulesStore } from '@/stores/rules'
import { useDraftsStore } from '@/stores/drafts'
import { featureTitle, refLabel, refString } from '@/lib/osm'
import { usePresetsStore } from '@/stores/presets'
import { useEditPresetsStore } from '@/stores/presetEdit'
import { EDIT_MIN_ZOOM, useEditorStore } from '@/stores/editor'
import type { Geometry, TagPatch } from '@/presets/types'
import { applyTagEdit, diffSize, tagDiff, tagDiffSummary } from '@/lib/tagdiff'
import { severityColor } from '@/lib/colors'
import PresetEditor, { type EditableEntity } from './presets/PresetEditor.vue'
import Icon from './Icon.vue'
import Spinner from './Spinner.vue'

const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()
const presets = usePresetsStore()
const presetEdit = useEditPresetsStore()
const editor = useEditorStore()

const feature = computed(() => mapStore.selectedFeature)
const base = computed<Record<string, string>>(() => feature.value?.tags ?? {})
const edited = ref<Record<string, string>>({})
const startedFrom = ref<Record<string, string>>({})
const reason = ref('')
const links = ref<EditorLinks | null>(null)
const conflict = ref<Change | null>(null)
const conflictKeys = ref<{ key: string; base: string | null; mine: string | null; live: string | null }[]>([])

watch(
  () => feature.value,
  (f, old) => {
    edited.value = { ...(f?.tags ?? {}) }
    startedFrom.value = { ...(f?.tags ?? {}) }
    reason.value = ''
    if (!old || !f || old.ref.type !== f.ref.type || old.ref.id !== f.ref.id) conflict.value = null
  },
  { immediate: true },
)

watch(
  () => mapStore.selected,
  async (ref) => {
    links.value = null
    if (!ref) return
    try {
      const l = await api.links(ref)
      if (mapStore.selected === ref) links.value = l
    } catch {}
  },
  { immediate: true },
)

const diff = computed(() => tagDiff(base.value, edited.value))
const dirty = computed(() => diffSize(diff.value) > 0)
const summary = computed(() => tagDiffSummary(base.value, edited.value))
const geometry = computed<Geometry>(() => {
  const f = feature.value
  if (!f) return 'point'
  const g = f.geometry as { type?: string; coordinates?: unknown } | null
  const type = g?.type
  if (f.ref.type === 'node') return 'point'
  if (f.ref.type === 'relation') return f.tags.type === 'multipolygon' ? 'area' : 'relation'
  if (type === 'Polygon' || type === 'MultiPolygon') return 'area'
  const c = (g?.coordinates ?? []) as number[][]
  const closed = c.length > 3 && c[0][0] === c[c.length - 1][0] && c[0][1] === c[c.length - 1][1]
  return closed && presets.catalog?.isArea(f.tags) ? 'area' : 'line'
})

const entities = computed<EditableEntity[]>(() =>
  mapStore.selected && feature.value
    ? [{ id: refString(mapStore.selected), tags: edited.value, geometry: geometry.value, baseTags: base.value, title: featureTitle(feature.value.ref, edited.value) }]
    : [],
)

const editable = computed(() => mapStore.zoom >= EDIT_MIN_ZOOM && !dirty.value)

async function onPatch(patch: TagPatch) {
  const ref = mapStore.selected
  if (!ref) return
  if (editable.value && (await presetEdit.ensureEditing(ref))) {
    await presetEdit.patch([editor.selected[0]], patch)
    return
  }
  const next = { ...edited.value }
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined || v === '') delete next[k]
    else next[k] = v
  }
  edited.value = next
}

async function onPreset(id: string) {
  const ref = mapStore.selected
  const cat = presets.catalog
  const to = cat?.preset(id)
  if (!ref || !cat || !to) return
  if (editable.value && (await presetEdit.ensureEditing(ref))) {
    await presetEdit.changePreset([editor.selected[0]], id)
    return
  }
  edited.value = cat.changePreset(edited.value, geometry.value, cat.match(edited.value, geometry.value), to)
  presets.useRecent(id)
}
const issues = computed(() => (mapStore.selected ? rules.issuesFor(mapStore.selected) : []))

function applyFix(i: Issue) {
  if (!i.fix) return
  const from = i.fix.replaceAll ?? edited.value
  edited.value = applyTagEdit(from, i.fix.set ?? {}, i.fix.remove ?? [])
  if (!reason.value) reason.value = rules.ruleById.get(i.ruleId)?.title ?? i.ruleId
}

function fixSummary(i: Issue): string {
  if (!i.fix) return ''
  const after = applyTagEdit(i.fix.replaceAll ?? i.tags, i.fix.set ?? {}, i.fix.remove ?? [])
  return tagDiffSummary(i.tags, after)
}

async function save() {
  const ref = mapStore.selected
  if (!ref || !dirty.value || drafts.savingManual) return
  const from = { ...startedFrom.value }
  const mine = { ...edited.value }
  const c = await drafts.saveManualEdit(ref, mine, from, reason.value)
  if (c?.status === 'conflict') {
    conflict.value = c
    const live = c.liveTags ?? {}
    const keys = new Set([...Object.keys(from), ...Object.keys(mine)])
    conflictKeys.value = [...keys]
      .filter((k) => from[k] !== mine[k] && live[k] !== from[k] && live[k] !== mine[k])
      .map((k) => ({ key: k, base: from[k] ?? null, mine: mine[k] ?? null, live: live[k] ?? null }))
  } else if (c) {
    conflict.value = null
  }
}

function resolveConflict() {
  const c = conflict.value
  if (!c) return
  conflict.value = null
  void drafts.open(c.draftId, { review: true, changeId: c.id })
}

function discard() {
  edited.value = { ...base.value }
  reason.value = ''
}

function copyRef() {
  if (mapStore.selected) void navigator.clipboard?.writeText(refString(mapStore.selected))
}

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && dirty.value) {
    e.preventDefault()
    void save()
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex h-10 shrink-0 items-center gap-2 border-b border-line px-3">
      <h2 class="text-[13px] font-semibold">Inspector</h2>
      <button class="btn btn-ghost btn-icon btn-sm ml-auto w-6" title="Deselect (Esc)" @click="mapStore.select(null)"><Icon name="x" :size="14" /></button>
    </div>

    <div v-if="mapStore.selected" class="scroll-thin min-h-0 flex-1 overflow-y-auto">
      <div class="space-y-2.5 border-b border-line px-4 py-3">
        <div>
          <div class="flex items-center gap-2">
            <Spinner v-if="mapStore.selectedLoading" :size="12" class="text-fg-3" />
          </div>
          <h3 class="mt-1 text-[16px] leading-snug font-semibold break-words">
            {{ feature ? featureTitle(feature.ref, feature.tags) : refLabel(mapStore.selected) }}
          </h3>
          <button class="mt-0.5 flex items-center gap-1 font-mono text-[11.5px] text-fg-3 hover:text-fg-2" title="Copy ID" @click="copyRef">
            {{ refString(mapStore.selected) }}<span v-if="feature?.version"> · v{{ feature.version }}</span>
            <Icon name="copy" :size="11" />
          </button>
        </div>
        <div class="flex flex-wrap gap-1.5">
          <button class="btn btn-sm" title="Open in iD (new tab)" @click="drafts.openInId(mapStore.selected)">
            <Icon name="edit" :size="12" /> Open in iD
          </button>
          <a class="btn btn-sm" :class="!links && 'pointer-events-none opacity-50'" :href="links?.osm" target="_blank" rel="noopener">
            <Icon name="external" :size="12" /> osm.org
          </a>
          <a class="btn btn-sm" :class="!links && 'pointer-events-none opacity-50'" :href="links?.history" target="_blank" rel="noopener">
            <Icon name="history" :size="12" /> History
          </a>
        </div>
      </div>

      <section v-if="issues.length" class="space-y-2 border-b border-line px-4 py-3">
        <div class="section-title">Issues</div>
        <div v-for="i in issues" :key="i.ruleId" class="rounded-md border border-line bg-surface-2 p-2.5">
          <div class="flex items-start gap-2">
            <span class="mt-1 size-2 shrink-0 rounded-full" :style="{ background: rules.colorOf.get(i.ruleId) }" />
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-1.5 font-medium">
                {{ rules.ruleById.get(i.ruleId)?.title ?? i.ruleId }}
                <span class="size-1.5 rounded-full" :style="{ background: severityColor[i.severity] }" :title="i.severity" />
              </div>
              <p v-if="i.note" class="mt-0.5 text-[12px] text-fg-2">{{ i.note }}</p>
              <div v-if="i.fix" class="mt-1.5">
                <span
                  class="chip"
                  :class="i.confidence === 'deterministic' ? 'bg-surface-3 text-fg-2' : 'bg-changed-soft text-changed'"
                >
                  {{ i.confidence === 'deterministic' ? 'Deterministic fix' : 'Fix needs review' }}
                </span>
              </div>
              <div v-if="i.fix" class="mt-1.5 flex items-center gap-2">
                <code class="min-w-0 flex-1 truncate font-mono text-[11.5px] text-fg-2" :title="fixSummary(i)">{{ fixSummary(i) }}</code>
                <button class="btn btn-sm shrink-0" title="Apply this fix to the tags below" @click="applyFix(i)">Apply fix</button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <template v-if="feature">
        <PresetEditor :entities="entities" @patch="onPatch" @preset="onPreset" @tags="edited = $event">
          <template #before-fields>
            <p v-if="!editable && !dirty" class="border-b border-line px-4 py-1.5 text-[11px] text-fg-3">{{ presets.t('inspector.notEditable') }}</p>
          </template>
        </PresetEditor>
      </template>
      <div v-else-if="!mapStore.selectedLoading" class="px-4 py-6 text-center text-fg-3">This object is not in the local copy.</div>
    </div>

    <div v-else class="grid flex-1 place-items-center px-6 text-center text-fg-3">
      <div>
        <Icon name="target" :size="28" class="mx-auto mb-2 opacity-60" />
        <p>Click an object or an issue on the map to inspect it.</p>
      </div>
    </div>

    <section v-if="conflict" class="scroll-thin max-h-[45%] shrink-0 space-y-2 overflow-y-auto border-t border-changed/40 bg-changed-soft px-4 py-3" data-testid="manual-conflict">
      <div class="flex items-center gap-2 font-semibold text-changed">
        <Icon name="conflict" :size="15" /> Conflict with the live object
      </div>
      <p class="text-[12px] text-fg-2">
        Someone changed this object upstream (now v{{ conflict.liveVersion ?? '?' }}) on a key you edited. Your edit is saved in the manual draft
        as a conflict and will not upload until you pick the values.
      </p>
      <table v-if="conflictKeys.length" class="w-full font-mono text-[11.5px]">
        <thead class="text-left text-[10.5px] text-fg-3">
          <tr><th class="pr-2 font-medium">Key</th><th class="pr-2 font-medium">Was</th><th class="pr-2 font-medium">Yours</th><th class="font-medium">Live</th></tr>
        </thead>
        <tbody>
          <tr v-for="k in conflictKeys" :key="k.key">
            <td class="pr-2 break-all">{{ k.key }}</td>
            <td class="pr-2 break-all text-fg-3">{{ k.base ?? '∅' }}</td>
            <td class="pr-2 break-all">{{ k.mine ?? '∅' }}</td>
            <td class="break-all text-changed">{{ k.live ?? '∅' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="flex gap-1.5">
        <button class="btn btn-sm btn-primary" @click="resolveConflict">Resolve conflict</button>
        <button class="btn btn-sm btn-ghost" @click="conflict = null">Dismiss</button>
      </div>
    </section>

    <Transition name="fade">
      <footer v-if="dirty && mapStore.selected" class="shrink-0 space-y-2 border-t border-line bg-surface-2 px-4 py-3">
        <code class="block truncate font-mono text-[11.5px] text-fg-2" :title="summary">{{ summary }}</code>
        <input v-model="reason" class="input" placeholder="Reason (optional, shown in review)" @keydown.enter.prevent="save" />
        <div class="flex items-center gap-2">
          <button class="btn btn-primary" :disabled="drafts.savingManual" @click="save">
            <Spinner v-if="drafts.savingManual" :size="12" /> Save to manual draft
          </button>
          <button class="btn btn-ghost" @click="discard">Discard</button>
          <span class="ml-auto text-[11px] text-fg-3"><span class="kbd">⌘</span> <span class="kbd">↵</span></span>
        </div>
      </footer>
    </Transition>
  </div>
</template>
