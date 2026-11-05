<script setup lang="ts">
import { computed, ref } from 'vue'
import type { BBox, OsmRef, PresetBulkApplyRequest, PresetBulkApplyResult } from '@/generated/models'
import { presetsApi } from '@/api/presets'
import { rulesApi } from '@/api/rules'
import { errorMessage } from '@/api/client'
import { usePresetsStore } from '@/stores/presets'
import { useHighlightsStore } from '@/stores/highlights'
import { useScansStore } from '@/stores/scans'
import { useMapStore } from '@/stores/map'
import { useDraftsStore } from '@/stores/drafts'
import { useAppStore } from '@/stores/app'
import { bboxChunks, buildBulkRequest, refsFromEvaluations, refsFromIssues } from '@/presets/bulk'
import Modal from '../Modal.vue'
import Spinner from '../Spinner.vue'
import PresetPicker from './PresetPicker.vue'
import PresetIcon from './PresetIcon.vue'

const props = defineProps<{ presetId: string | null }>()
const emit = defineEmits<{ close: [] }>()

const presets = usePresetsStore()
const hl = useHighlightsStore()
const scans = useScansStore()
const mapStore = useMapStore()
const drafts = useDraftsStore()
const app = useAppStore()
const t = presets.t

const presetId = ref<string | null>(props.presetId)
const picking = ref(!props.presetId)
const source = ref<'highlight' | 'issues'>(hl.active.length || !scans.picked.size ? 'highlight' : 'issues')
const hlPath = ref<string>(hl.active[0] ?? hl.files[0]?.path ?? '')
const scope = ref<'area' | 'view'>(mapStore.area ? 'area' : 'view')
const busy = ref(false)
const error = ref<string | null>(null)
const result = ref<PresetBulkApplyResult | null>(null)
const refs = ref<OsmRef[]>([])
const title = ref('')

const preset = computed(() => (presetId.value ? presets.catalog?.preset(presetId.value) : null))

function scopeBBox(): BBox | null {
  if (scope.value === 'area' && mapStore.area) return mapStore.area.bbox
  const m = mapStore.instance()
  if (!m) return null
  const b = m.getBounds()
  return { west: b.getWest(), south: b.getSouth(), east: b.getEast(), north: b.getNorth() }
}

async function collect(): Promise<OsmRef[]> {
  if (source.value === 'issues') {
    if (!scans.report || !scans.picked.size) throw new Error(t('bulk.noIssues'))
    return refsFromIssues(scans.report.issues, scans.picked)
  }
  if (!hlPath.value) throw new Error(t('bulk.noHighlights'))
  const b = scopeBBox()
  const chunks = b ? bboxChunks(b) : null
  if (!chunks) throw new Error(t('bulk.tooLarge'))
  const evals = await Promise.all(chunks.map((c) => rulesApi.evaluateBBox(hlPath.value, c)))
  return refsFromEvaluations(evals)
}

function sourceLabel() {
  return source.value === 'issues' ? `${scans.picked.size} scan issues` : hlPath.value
}

async function count() {
  if (!presetId.value) return
  busy.value = true
  error.value = null
  result.value = null
  try {
    refs.value = await collect()
    if (!refs.value.length) throw new Error(t('bulk.noFeatures'))
    const req = buildBulkRequest({ presetId: presetId.value, refs: refs.value, dryRun: true, area: mapStore.area?.name ?? null, locale: presets.locale })
    result.value = await presetsApi.bulk<PresetBulkApplyRequest, PresetBulkApplyResult>(req)
    title.value = `${preset.value?.name ?? presetId.value} · ${result.value.changed}`
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function create() {
  if (!presetId.value || !result.value?.changed) return
  busy.value = true
  error.value = null
  try {
    const req = buildBulkRequest({
      presetId: presetId.value,
      refs: refs.value,
      dryRun: false,
      title: title.value,
      area: mapStore.area?.name ?? null,
      locale: presets.locale,
      source: sourceLabel(),
    })
    const r = await presetsApi.bulk<PresetBulkApplyRequest, PresetBulkApplyResult>(req)
    if (!r.draft) throw new Error(t('bulk.noFeatures'))
    app.toast(t('bulk.created', { title: r.draft.draft.title, n: r.draft.changes.length }), 'success')
    emit('close')
    void drafts.loadDrafts()
    await drafts.open(r.draft.draft.id, { review: true })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function choose(id: string) {
  presetId.value = id
  picking.value = false
  result.value = null
}

function diffRows(before: Record<string, string>, after: Record<string, string>) {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()
  return keys.filter((k) => before[k] !== after[k]).map((k) => ({ k, from: before[k] ?? null, to: after[k] ?? null }))
}
</script>

<template>
  <Modal :title="t('bulk.title')" width="620px" @close="emit('close')">
    <div class="space-y-3 px-5 py-4" data-testid="bulk-dialog">
      <div class="space-y-1">
        <div class="text-[11.5px] font-medium text-fg-2">{{ t('bulk.preset') }}</div>
        <div v-if="picking" class="h-72 overflow-hidden rounded-md border border-line">
          <PresetPicker class="h-full" :geometries="[]" :closable="!!presetId" @choose="choose" @close="picking = false" />
        </div>
        <button v-else class="flex w-full items-center gap-2 rounded-md border border-line px-2 py-1.5 text-left hover:bg-surface-2" data-testid="bulk-preset" @click="picking = true">
          <PresetIcon :icon="preset?.icon" :size="26" />
          <span class="flex-1 truncate">{{ preset?.name ?? t('bulk.pick') }}</span>
          <span class="font-mono text-[11px] text-fg-3">{{ presetId }}</span>
        </button>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <label class="flex flex-col gap-1">
          <span class="text-[11.5px] font-medium text-fg-2">{{ t('bulk.source') }}</span>
          <select v-model="source" class="input" data-testid="bulk-source">
            <option value="highlight">{{ t('bulk.highlight') }}</option>
            <option value="issues">{{ t('bulk.issues') }} ({{ scans.picked.size }})</option>
          </select>
        </label>
        <label v-if="source === 'highlight'" class="flex flex-col gap-1">
          <span class="text-[11.5px] font-medium text-fg-2">{{ t('bulk.highlight') }}</span>
          <select v-model="hlPath" class="input" data-testid="bulk-highlight">
            <option v-for="f in hl.files" :key="f.path" :value="f.path">{{ f.title }}{{ hl.active.includes(f.path) ? ' ●' : '' }}</option>
          </select>
        </label>
        <label v-if="source === 'highlight'" class="flex flex-col gap-1">
          <span class="text-[11.5px] font-medium text-fg-2">{{ t('bulk.area') }}</span>
          <select v-model="scope" class="input">
            <option v-if="mapStore.area" value="area">{{ mapStore.area.name }}</option>
            <option value="view">{{ t('bulk.view') }}</option>
          </select>
        </label>
      </div>

      <div class="flex items-center gap-2">
        <button class="btn" :disabled="!presetId || busy" data-testid="bulk-count" @click="count">
          <Spinner v-if="busy && !result" :size="12" /> {{ t('bulk.count') }}
        </button>
        <span v-if="busy && !result" class="text-[12px] text-fg-3">{{ t('bulk.loading') }}</span>
      </div>

      <div v-if="error" class="rounded-md border border-removed/40 bg-removed-soft px-2.5 py-2 text-[12px] text-removed">{{ error }}</div>

      <div v-if="result" class="space-y-2" data-testid="bulk-result">
        <div class="font-medium">
          {{ t('bulk.total', { total: result.total, changed: result.changed, unchanged: result.unchanged, skipped: result.skipped.length }) }}
        </div>
        <div class="scroll-thin max-h-64 overflow-y-auto rounded-md border border-line">
          <div v-for="item in result.preview" :key="`${item.ref.type}${item.ref.id}`" class="border-b border-line px-2.5 py-1.5 last:border-b-0">
            <div class="flex items-center gap-2 text-[12px]">
              <span class="font-mono text-fg-3">{{ item.ref.type[0] }}{{ item.ref.id }}</span>
              <span class="truncate">{{ item.name ?? '' }}</span>
              <span class="ml-auto truncate font-mono text-[10.5px] text-fg-3">{{ item.fromPresetId }}</span>
            </div>
            <div class="flex flex-wrap gap-x-3 font-mono text-[11px]">
              <span v-for="d in diffRows(item.before, item.after)" :key="d.k">
                <span class="text-fg-2">{{ d.k }}</span>
                <span v-if="d.from !== null" class="text-removed line-through">={{ d.from }}</span>
                <span v-if="d.to !== null" class="text-added">={{ d.to }}</span>
              </span>
            </div>
          </div>
          <div v-if="result.truncated" class="px-2.5 py-1.5 text-[11px] text-fg-3">…</div>
          <div v-for="s in result.skipped.slice(0, 20)" :key="`s${s.ref.type}${s.ref.id}`" class="border-t border-line px-2.5 py-1 text-[11px] text-fg-3">
            <span class="font-mono">{{ s.ref.type[0] }}{{ s.ref.id }}</span> {{ s.message }}
          </div>
        </div>
        <label class="flex flex-col gap-1">
          <span class="text-[11.5px] font-medium text-fg-2">{{ t('bulk.draftTitle') }}</span>
          <input v-model="title" class="input" />
        </label>
        <div class="flex justify-end">
          <button class="btn btn-primary" :disabled="busy || !result.changed" data-testid="bulk-create" @click="create">
            <Spinner v-if="busy" :size="12" /> {{ t('bulk.create') }}
          </button>
        </div>
      </div>
    </div>
  </Modal>
</template>
