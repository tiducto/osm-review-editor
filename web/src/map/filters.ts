import type { FilterSpecification, Map as MlMap } from 'maplibre-gl'

interface LayerFilters {
  base: FilterSpecification | undefined
  parts: Map<string, FilterSpecification>
}

const state = new WeakMap<MlMap, Map<string, LayerFilters>>()

function entry(map: MlMap, layerId: string): LayerFilters {
  let layers = state.get(map)
  if (!layers) {
    layers = new Map()
    state.set(map, layers)
  }
  let e = layers.get(layerId)
  if (!e) {
    e = { base: map.getFilter(layerId) as FilterSpecification | undefined, parts: new Map() }
    layers.set(layerId, e)
  }
  return e
}

export function setFilterPart(map: MlMap, layerId: string, key: string, part: FilterSpecification | null): void {
  if (!map.getLayer(layerId)) return
  const e = entry(map, layerId)
  if (part) e.parts.set(key, part)
  else if (!e.parts.delete(key)) return
  const all = [e.base, ...e.parts.values()].filter(Boolean) as FilterSpecification[]
  const filter = all.length === 0 ? null : all.length === 1 ? all[0] : (['all', ...all] as FilterSpecification)
  map.setFilter(layerId, filter)
}

export function resetFilters(map: MlMap): void {
  state.delete(map)
}
