import { defineStore } from 'pinia'
import { computed, ref, shallowRef, watch } from 'vue'
import type { OsmRef, SmartPreset } from '@/generated/models'
import type { EntitySnapshot } from '@/editor/types'
import { currentEditSession, useEditorStore, EDIT_MIN_ZOOM } from './editor'
import { useMapStore } from './map'
import { useAppStore } from './app'
import { usePresetsStore, toUserData } from './presets'
import '@/presets/appStrings'
import type { Geometry, PresetDef, TagPatch, Tags } from '@/presets/types'
import type { SmartPreview } from '@/editor/id/presetOps'

type SessionModule = typeof import('@/editor/session')
let sessionModule: SessionModule | null = null

const opsReady = ref(false)

async function ops() {
  sessionModule ??= await import('@/editor/session')
  opsReady.value = true
  return sessionModule.presetOps
}

export function entityIdOf(ref: OsmRef): string {
  return ref.type[0] + String(ref.id)
}

export interface SmartRun {
  preset: SmartPreset
  selfId: string
  params: Record<string, string>
  preview: SmartPreview | null
  error: string | null
  summary: string[]
}

export const useEditPresetsStore = defineStore('presetEdit', () => {
  const app = useAppStore()
  const editor = useEditorStore()
  const mapStore = useMapStore()
  const presets = usePresetsStore()

  const drawPreset = ref<{ id: string; kind: 'point' | 'line' | 'area' } | null>(null)
  const smartRun = shallowRef<SmartRun | null>(null)

  watch(
    () => [editor.active, presets.catalog, presets.user] as const,
    async ([active]) => {
      if (!active) return
      const o = await ops()
      o.mergeUserPresets(toUserData(presets.user, 'en'))
    },
    { immediate: true },
  )

  watch(
    () => editor.mode,
    (m) => {
      if (m === 'select' || m === 'browse') {
        if (drawPreset.value) {
          const s = currentEditSession()
          if (s) s.adapter.drawTags = null
          drawPreset.value = null
        }
      }
    },
  )

  watch(
    () => editor.active,
    (a) => {
      if (!a) {
        smartRun.value = null
        drawPreset.value = null
      }
    },
  )

  const t = presets.t

  function presetOf(tags: Tags, geometry: Geometry): PresetDef | null {
    return presets.catalog?.match(tags, geometry) ?? null
  }

  async function ensureEditing(ref: OsmRef): Promise<boolean> {
    const id = entityIdOf(ref)
    if (!editor.active) {
      if (mapStore.zoom < EDIT_MIN_ZOOM) return false
      if (!(await editor.enter())) return false
    }
    const s = currentEditSession()
    if (!s) return false
    if (!s.adapter.graph().hasEntity(id)) {
      const map = mapStore.instance()
      if (map) {
        const b = map.getBounds()
        s.loader.request([
          [b.getWest(), b.getSouth()],
          [b.getEast(), b.getNorth()],
        ])
      }
      await s.loader.idle()
      if (!s.adapter.graph().hasEntity(id)) {
        app.toast(`${id} is not in the loaded data; zoom to it and try again`, 'error')
        return false
      }
    }
    editor.select([id])
    return true
  }

  async function patch(ids: string[], p: TagPatch) {
    const s = currentEditSession()
    if (!s || !ids.length) return
    const o = await ops()
    o.patchTags(s.adapter, ids, p, t('ann.changeTags'))
  }

  async function changePreset(ids: string[], toId: string) {
    const s = currentEditSession()
    const cat = presets.catalog
    const to = cat?.preset(toId)
    if (!s || !cat || !to || !ids.length) return
    const o = await ops()
    const n = o.changePreset(s.adapter, {
      ids,
      toId,
      annotation: t(ids.length > 1 ? 'ann.applyPresetMulti' : 'ann.changePreset', { name: to.name, n: ids.length }),
      useIdAction: to.apply === 'merge',
      computeTags: (_id, tags, geometry) => cat.changePreset(tags, geometry, cat.match(tags, geometry), to),
    })
    if (n) presets.useRecent(toId)
    editor.select(ids)
  }

  function canDraw(p: PresetDef): ('point' | 'line' | 'area')[] {
    const out: ('point' | 'line' | 'area')[] = []
    if (p.geometry.includes('point') || p.geometry.includes('vertex')) out.push('point')
    if (p.geometry.includes('line')) out.push('line')
    if (p.geometry.includes('area')) out.push('area')
    return out
  }

  async function drawWith(presetId: string, kind?: 'point' | 'line' | 'area') {
    const cat = presets.catalog
    const p = cat?.preset(presetId)
    if (!cat || !p) return
    const k = kind ?? canDraw(p)[0]
    if (!k) {
      app.toast(t('draw.unsupported'), 'error')
      return
    }
    if (!editor.active && !(await editor.enter())) return
    const s = currentEditSession()
    if (!s) return
    const geometry: Geometry = k === 'point' ? (p.geometry.includes('point') ? 'point' : 'vertex') : k
    editor.select([])
    s.adapter.drawTags = cat.setTags(p, {}, geometry)
    drawPreset.value = { id: presetId, kind: k }
    presets.useRecent(presetId)
    editor.setMode(k === 'point' ? 'add-point' : k === 'line' ? 'add-line' : 'add-area')
  }

  function cancelDraw() {
    const s = currentEditSession()
    if (s) s.adapter.drawTags = null
    drawPreset.value = null
  }

  function smartFor(entities: EntitySnapshot[]): SmartPreset[] {
    const s = currentEditSession()
    if (!opsReady.value || !s || entities.length !== 1 || !sessionModule) return []
    const o = sessionModule.presetOps
    return presets.smart.filter((p) => o.smartApplies(s.adapter, p, entities[0].id))
  }

  function defaults(p: SmartPreset): Record<string, string> {
    const out: Record<string, string> = {}
    for (const [k, v] of Object.entries(p.params)) out[k] = v.default ?? (v.type === 'check' ? 'no' : '')
    return out
  }

  function openSmart(preset: SmartPreset, selfId: string) {
    if (smartRun.value?.preview) return
    smartRun.value = { preset, selfId, params: defaults(preset), preview: null, error: null, summary: [] }
  }

  async function previewSmart(params: Record<string, string>) {
    const run = smartRun.value
    const s = currentEditSession()
    if (!run || !s) return
    const o = await ops()
    if (run.preview) o.cancelSmart(s.adapter, run.preview)
    try {
      const selfTags = JSON.stringify((s.adapter.graph().hasEntity(run.selfId) as { tags?: Tags } | undefined)?.tags ?? {})
      const preview = o.previewSmart(s.adapter, run.preset, run.selfId, params)
      const g = s.adapter.graph()
      const ids = [...new Set([...preview.result.created, ...preview.result.modified])].filter(
        (id) => id[0] === 'w' && JSON.stringify((g.hasEntity(id) as { tags?: Tags } | undefined)?.tags ?? {}) !== selfTags,
      )
      smartRun.value = { ...run, params, preview, error: null, summary: preview.result.summary }
      editor.select(ids.length ? ids : [run.selfId])
    } catch (e) {
      smartRun.value = { ...run, params, preview: null, error: e instanceof Error ? e.message : String(e), summary: [] }
      editor.select([run.selfId])
    }
  }

  async function confirmSmart() {
    const run = smartRun.value
    if (!run?.preview) return
    smartRun.value = null
    app.toast(run.preview.result.annotation, 'success')
  }

  async function cancelSmart() {
    const run = smartRun.value
    const s = currentEditSession()
    smartRun.value = null
    if (!run?.preview || !s) return
    const o = await ops()
    if (!o.cancelSmart(s.adapter, run.preview)) app.toast(t('smart.cannotCancel'), 'error')
    editor.select([run.selfId].filter((id) => s.adapter.graph().hasEntity(id)))
  }

  const busy = computed(() => !!smartRun.value?.preview)

  return {
    drawPreset, smartRun, busy, presetOf, ensureEditing, patch, changePreset, canDraw, drawWith, cancelDraw, smartFor, openSmart, previewSmart, confirmSmart, cancelSmart,
  }
})
