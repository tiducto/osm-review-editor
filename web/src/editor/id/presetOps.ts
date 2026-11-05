import * as iD from '@openstreetmap/id/modules/index'
import type { SmartPreset } from '@/generated/models'
import type { UserPresetData } from '@/presets/catalog'
import type { Geometry, TagPatch, Tags } from '@/presets/types'
import { runSmartPreset, smartPresetApplies, type SmartRunResult } from '../smart/execute'
import type { IdAdapter } from './adapter'

let mergedKey = ''

export function mergeUserPresets(list: UserPresetData[]) {
  const key = JSON.stringify(list.map((p) => [p.id, p.tags, p.addTags, p.removeTags, p.fields, p.moreFields, p.geometry, p.name]))
  if (key === mergedKey) return
  mergedKey = key
  const presets: Record<string, unknown> = {}
  for (const p of list) {
    presets[p.id] = {
      name: p.name,
      tags: p.tags,
      addTags: p.addTags,
      removeTags: p.removeTags,
      geometry: p.geometry,
      fields: p.fields,
      moreFields: p.moreFields,
      icon: p.icon ?? undefined,
      terms: p.terms,
      matchScore: p.matchScore,
      searchable: p.searchable,
    }
  }
  iD.presetManager.merge({ presets })
}

export interface ChangePresetRequest {
  ids: string[]
  toId: string
  annotation: string
  computeTags: (id: string, tags: Tags, geometry: Geometry) => Tags
  useIdAction: boolean
}

export function changePreset(adapter: IdAdapter, req: ChangePresetRequest): number {
  const g = adapter.graph() as any
  const target = iD.presetManager.item(req.toId)
  const actions: any[] = []
  for (const id of req.ids) {
    const e = g.hasEntity(id)
    if (!e) continue
    if (req.useIdAction && target) {
      const old = iD.presetManager.match(e, g)
      actions.push(iD.actionChangePreset(id, old, target))
    } else {
      const next = req.computeTags(id, { ...e.tags }, e.geometry(g))
      actions.push(iD.actionChangeTags(id, next))
    }
  }
  if (!actions.length) return 0
  adapter.history.perform(...actions, req.annotation)
  return actions.length
}

export function patchTags(adapter: IdAdapter, ids: string[], patch: TagPatch, annotation: string): number {
  const g = adapter.graph() as any
  const entities = ids.map((id) => g.hasEntity(id)).filter(Boolean)
  if (entities.length === 1) {
    const e = entities[0]
    const next: Tags = { ...e.tags }
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === '') delete next[k]
      else next[k] = v
    }
    adapter.setTags(e.id, next)
    return 1
  }
  const actions = entities.map((e: any) => {
    const next: Tags = { ...e.tags }
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === '') delete next[k]
      else next[k] = v
    }
    return iD.actionChangeTags(e.id, next)
  })
  if (!actions.length) return 0
  adapter.history.perform(...actions, annotation)
  return actions.length
}

export function smartApplies(adapter: IdAdapter, preset: SmartPreset, id: string): boolean {
  const g = adapter.graph() as any
  const e = g.hasEntity(id)
  if (!e) return false
  try {
    return smartPresetApplies(preset, e, g)
  } catch {
    return false
  }
}

export interface SmartPreview {
  result: SmartRunResult
  graphAfter: unknown
}

export function previewSmart(adapter: IdAdapter, preset: SmartPreset, selfId: string, params: Record<string, string>): SmartPreview {
  const before = adapter.graph()
  const result = runSmartPreset(before, preset, selfId, params)
  adapter.history.perform(() => result.graph, result.annotation)
  return { result, graphAfter: adapter.graph() }
}

export function cancelSmart(adapter: IdAdapter, preview: SmartPreview): boolean {
  if (adapter.graph() !== preview.graphAfter) return false
  adapter.history.pop()
  return true
}
