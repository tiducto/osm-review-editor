<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { SmartPreset } from '@/generated/models'
import { presetsApi } from '@/api/presets'
import { usePresetsStore } from '@/stores/presets'
import { useEditPresetsStore } from '@/stores/presetEdit'
import { useEditorStore } from '@/stores/editor'
import { useMapStore } from '@/stores/map'
import { useRuleFilesStore } from '@/stores/ruleFiles'
import { usePanelStore } from '@/stores/panel'
import { useAppStore } from '@/stores/app'
import { slug } from '@/lib/ruleTemplates'
import { fold } from '@/presets/catalog'
import type { CategoryDef, Geometry, PresetDef, Tags } from '@/presets/types'
import PresetIcon from './PresetIcon.vue'
import BulkApplyDialog from './BulkApplyDialog.vue'
import Icon from '../Icon.vue'
import Spinner from '../Spinner.vue'
import { useI18n } from '@/i18n'

const presets = usePresetsStore()
const presetEdit = useEditPresetsStore()
const editor = useEditorStore()
const mapStore = useMapStore()
const ruleFiles = useRuleFilesStore()
const panel = usePanelStore()
const app = useAppStore()
const { t, tn } = useI18n()

const query = ref('')
const openId = ref<string | null>(null)
const openCat = ref<string | null>(null)
const bulkFor = ref<string | null | undefined>(undefined)
const working = ref(false)

onMounted(() => void presets.ensure().catch((e) => app.fail(e, t('library.title'))))

const userPresets = computed<PresetDef[]>(() => {
  const cat = presets.catalog
  if (!cat) return []
  const q = query.value.trim()
  const list = cat.userIds.map((id) => cat.preset(id)).filter((p): p is PresetDef => !!p)
  return q ? cat.search(q).filter((p) => p.origin === 'user') : list
})

const smartPresets = computed<SmartPreset[]>(() => {
  const q = query.value.trim().toLowerCase()
  return presets.smart.filter((s) => !q || s.name.toLowerCase().includes(q) || s.id.includes(q))
})

const results = computed<PresetDef[]>(() => {
  const cat = presets.catalog
  const q = query.value.trim()
  if (!cat || !q) return []
  return cat.search(q, undefined, 80).filter((p) => p.origin === 'id')
})

const categories = computed<CategoryDef[]>(() => presets.catalog?.categories() ?? [])

const selectionIds = computed(() => (editor.active ? editor.selection.map((s) => s.id) : []))

const selectedFeature = computed<{ tags: Tags; geometry: Geometry } | null>(() => {
  if (editor.active && editor.selection.length === 1) return { tags: editor.selection[0].tags, geometry: editor.selection[0].geometry }
  const f = mapStore.selectedFeature
  if (!f) return null
  const g = (f.geometry as { type?: string } | null)?.type
  const geometry: Geometry = f.ref.type === 'node' ? 'point' : g === 'Polygon' || g === 'MultiPolygon' ? 'area' : f.ref.type === 'relation' ? 'relation' : 'line'
  return { tags: f.tags, geometry }
})

function drawLabel(k: 'point' | 'line' | 'area'): string {
  return t(k === 'point' ? 'draw.point' : k === 'line' ? 'draw.line' : 'draw.area')
}

function members(c: CategoryDef): PresetDef[] {
  const cat = presets.catalog
  return cat ? c.members.map((id) => cat.preset(id)).filter((p): p is PresetDef => !!p) : []
}

function fits(p: PresetDef): boolean {
  return editor.selection.length > 0 && editor.selection.every((s) => p.geometry.includes(s.geometry))
}

function smartPath(s: SmartPreset): string | null {
  const name = s.id.replace(/^smart\//, '')
  const path = `presets/smart/${name}.json`
  return ruleFiles.info(path) ? path : null
}

function smartRunnable(s: SmartPreset): boolean {
  return editor.active && editor.selection.length === 1 && presetEdit.smartFor(editor.selection).some((x) => x.id === s.id)
}

async function openRules(path: string) {
  panel.tab = 'rules'
  await ruleFiles.load()
  await ruleFiles.openFile(path)
}

async function importOverlay(p: PresetDef) {
  working.value = true
  try {
    const r = await presetsApi.importOverlay(p.id)
    const path = r.file?.path
    await presets.reloadUser()
    app.toast(t('library.imported', { path: path ?? p.id }), 'success')
    if (path) await openRules(path)
  } catch (e) {
    app.fail(e, t('library.import'))
  } finally {
    working.value = false
  }
}

async function detach(p: PresetDef) {
  working.value = true
  try {
    await presetsApi.detach(p.id)
    await presets.reloadUser()
    app.toast(t('library.detached', { id: p.id }), 'success')
    if (p.file) await openRules(p.file)
  } catch (e) {
    app.fail(e, t('library.detach'))
  } finally {
    working.value = false
  }
}

const SKIP_KEY = /^(name|name:.*|alt_name|old_name|addr:.*|ref|ref:.*|wikidata|wikipedia|website|phone|email|opening_hours|check_date.*|source.*|note|fixme|description|survey:date|image|start_date)$/

async function fromFeature() {
  const f = selectedFeature.value
  const cat = presets.catalog
  if (!f || !cat) {
    app.toast(t('library.noSelection'), 'error')
    return
  }
  const matched = cat.match(f.tags, f.geometry)
  const addTags: Tags = {}
  for (const [k, v] of Object.entries(f.tags)) if (!SKIP_KEY.test(k)) addTags[k] = v
  const tags = Object.keys(addTags).length ? addTags : matched.tags
  const base = slug(fold(`${matched.name}-${Object.values(addTags).slice(1, 3).join('-')}`)) || 'preset'
  const name = `${matched.name} (${t('library.custom')})`
  const preset = {
    id: `custom/${base}`,
    name,
    geometry: [f.geometry === 'vertex' ? 'vertex' : f.geometry],
    tags,
    addTags,
    ...(matched.icon ? { icon: matched.icon } : {}),
    fields: cat.isFallback(matched) ? ['name'] : [`{${matched.id}}`],
  }
  await ruleFiles.create('preset', base, JSON.stringify(preset, null, 2) + '\n')
  panel.tab = 'rules'
  await presets.reloadUser()
}
</script>

<template>
  <div class="flex h-full flex-col" data-testid="presets-library">
    <div class="space-y-2 border-b border-line px-3 py-3">
      <div class="flex items-center gap-2">
        <span class="section-title">{{ t('library.title') }}</span>
        <Spinner v-if="presets.loading || working" :size="11" class="text-fg-3" />
      </div>
      <input v-model="query" class="input" :placeholder="t('library.search')" data-testid="library-search" />
      <div class="flex flex-wrap gap-1">
        <button class="btn btn-sm" :disabled="!selectedFeature" :title="selectedFeature ? '' : t('library.noSelection')" data-testid="library-from-feature" @click="fromFeature">
          <Icon name="plus" :size="11" /> {{ t('library.fromFeature') }}
        </button>
        <button class="btn btn-sm" data-testid="library-bulk" @click="bulkFor = null"><Icon name="layers" :size="11" /> {{ t('library.bulk') }}</button>
      </div>
    </div>

    <div class="scroll-thin min-h-0 flex-1 overflow-y-auto pb-4">
      <div class="px-3 pt-3 pb-1 text-[10.5px] font-semibold tracking-wide text-fg-3 uppercase">{{ t('library.user') }}</div>
      <p v-if="!userPresets.length" class="px-3 pb-1 text-[12px] text-fg-3">{{ t('library.empty') }}</p>
      <div v-for="p in userPresets" :key="p.id" class="border-b border-line/60" :data-user-preset="p.id">
        <button class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-2" @click="openId = openId === p.id ? null : p.id">
          <PresetIcon :icon="p.icon" :size="26" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-[12.5px] font-medium">{{ p.name }}</span>
            <span class="block truncate font-mono text-[10.5px] text-fg-3">{{ p.file }}{{ p.extends ? ` · ${t('library.extends', { id: p.extends })}` : '' }}</span>
          </span>
          <Icon :name="openId === p.id ? 'chevron-up' : 'chevron-down'" :size="12" class="text-fg-3" />
        </button>
        <div v-if="openId === p.id" class="flex flex-wrap gap-1 px-3 pb-2">
          <button v-if="p.file" class="btn btn-sm" @click="openRules(p.file)"><Icon name="edit" :size="11" /> {{ t('library.openRules') }}</button>
          <button v-if="p.extends" class="btn btn-sm" data-testid="library-detach" @click="detach(p)">{{ t('library.detach') }}</button>
          <button class="btn btn-sm" :disabled="!fits(p)" :title="fits(p) ? '' : t('library.noSelection')" data-testid="library-apply" @click="presetEdit.changePreset(selectionIds, p.id)">
            {{ t('library.applySelection') }}
          </button>
          <button v-for="k in presetEdit.canDraw(p)" :key="k" class="btn btn-sm" :data-draw="k" @click="presetEdit.drawWith(p.id, k)">{{ t('library.draw') }}: {{ drawLabel(k) }}</button>
          <button class="btn btn-sm" @click="bulkFor = p.id">{{ t('library.bulk') }}</button>
        </div>
      </div>

      <div class="px-3 pt-3 pb-1 text-[10.5px] font-semibold tracking-wide text-fg-3 uppercase">{{ t('library.smart') }}</div>
      <p v-if="!smartPresets.length" class="px-3 pb-1 text-[12px] text-fg-3">{{ t('library.smartEmpty') }}</p>
      <div v-for="s in smartPresets" :key="s.id" class="border-b border-line/60 px-3 py-1.5" :data-smart-preset="s.id">
        <div class="flex items-center gap-2">
          <PresetIcon :icon="null" :size="26" smart />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-[12.5px] font-medium">{{ s.name }}</span>
            <span class="block truncate font-mono text-[10.5px] text-fg-3">{{ s.match }} · {{ tn('smart.steps', s.steps.length) }}</span>
          </span>
        </div>
        <p v-if="s.description" class="mt-0.5 text-[11.5px] text-fg-2">{{ s.description }}</p>
        <div class="mt-1 flex flex-wrap gap-1">
          <button
            class="btn btn-sm text-claude"
            :disabled="!smartRunnable(s)"
            :title="smartRunnable(s) ? '' : t('library.notApplicable')"
            @click="presetEdit.openSmart(s, editor.selection[0].id)"
          >
            <Icon name="sparkle" :size="11" /> {{ t('library.runOnSelection') }}
          </button>
          <button v-if="smartPath(s)" class="btn btn-sm" @click="openRules(smartPath(s)!)">{{ t('library.openRules') }}</button>
        </div>
      </div>

      <div class="px-3 pt-3 pb-1 text-[10.5px] font-semibold tracking-wide text-fg-3 uppercase">{{ t('library.id') }}</div>
      <template v-if="query.trim()">
        <div v-for="p in results" :key="p.id" class="border-b border-line/60" :data-id-preset="p.id">
          <button class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-2" @click="openId = openId === p.id ? null : p.id">
            <PresetIcon :icon="p.icon" :size="26" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-[12.5px]">{{ p.name }}</span>
              <span class="block truncate font-mono text-[10.5px] text-fg-3">{{ p.id }} · {{ p.geometry.join(', ') }}</span>
            </span>
          </button>
          <div v-if="openId === p.id" class="flex flex-wrap gap-1 px-3 pb-2">
            <button class="btn btn-sm" data-testid="library-import" @click="importOverlay(p)">{{ t('library.import') }}</button>
            <button class="btn btn-sm" :disabled="!fits(p)" @click="presetEdit.changePreset(selectionIds, p.id)">{{ t('library.applySelection') }}</button>
            <button v-for="k in presetEdit.canDraw(p)" :key="k" class="btn btn-sm" :data-draw="k" @click="presetEdit.drawWith(p.id, k)">{{ t('library.draw') }}: {{ drawLabel(k) }}</button>
            <button class="btn btn-sm" @click="bulkFor = p.id">{{ t('library.bulk') }}</button>
          </div>
        </div>
      </template>
      <template v-else>
        <div v-for="c in categories" :key="c.id" class="border-b border-line/60">
          <button class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-surface-2" @click="openCat = openCat === c.id ? null : c.id">
            <PresetIcon :icon="c.icon" :size="26" />
            <span class="flex-1 truncate text-[12.5px]">{{ c.name }}</span>
            <span class="text-[11px] text-fg-3">{{ c.members.length }}</span>
            <Icon :name="openCat === c.id ? 'chevron-up' : 'chevron-down'" :size="12" class="text-fg-3" />
          </button>
          <div v-if="openCat === c.id" class="pb-1 pl-5">
            <div v-for="p in members(c)" :key="p.id" :data-id-preset="p.id">
              <button class="flex w-full items-center gap-2 px-3 py-1 text-left hover:bg-surface-2" @click="openId = openId === p.id ? null : p.id">
                <PresetIcon :icon="p.icon" :size="22" />
                <span class="flex-1 truncate text-[12px]">{{ p.name }}</span>
              </button>
              <div v-if="openId === p.id" class="flex flex-wrap gap-1 px-3 pb-2">
                <button class="btn btn-sm" @click="importOverlay(p)">{{ t('library.import') }}</button>
                <button class="btn btn-sm" :disabled="!fits(p)" @click="presetEdit.changePreset(selectionIds, p.id)">{{ t('library.applySelection') }}</button>
                <button v-for="k in presetEdit.canDraw(p)" :key="k" class="btn btn-sm" @click="presetEdit.drawWith(p.id, k)">{{ t('library.draw') }}: {{ drawLabel(k) }}</button>
                <button class="btn btn-sm" @click="bulkFor = p.id">{{ t('library.bulk') }}</button>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <BulkApplyDialog v-if="bulkFor !== undefined" :preset-id="bulkFor" @close="bulkFor = undefined" />
  </div>
</template>
