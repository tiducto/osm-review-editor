<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import type { Map as MlMap, VectorTileSource } from 'maplibre-gl'
import { useMapStore } from '@/stores/map'
import { useHighlightsStore } from '@/stores/highlights'
import { BACKGROUND, LAYER, OSM_SRC, TILE_MIN_ZOOM } from './osm/style'
import { rawTileUrl } from './osm/protocol'
import { dimLayer, filterKeys, highlightLayers, HL_DIM, HL_PREFIX, HL_SRC } from './highlightStyle'

const mapStore = useMapStore()
const hl = useHighlightsStore()

let bound: MlMap | null = null
let installed: string[] = []
let tilesUrl = ''

function ensureSource(m: MlMap) {
  const keys = filterKeys(hl.activeFiles)
  const url = rawTileUrl(mapStore.tilesVersion, keys)
  const src = m.getSource(HL_SRC) as VectorTileSource | undefined
  if (!src) {
    m.addSource(HL_SRC, { type: 'vector', tiles: [url], minzoom: TILE_MIN_ZOOM, maxzoom: 14 })
  } else if (url !== tilesUrl) {
    src.setTiles([url])
  }
  tilesUrl = url
}

function render() {
  const m = bound
  if (!m || !m.getLayer(LAYER.highlightSlot)) return
  for (const id of installed) if (m.getLayer(id)) m.removeLayer(id)
  installed = []
  ensureSource(m)
  if (hl.activeFiles.length && hl.dimOthers) {
    m.addLayer(dimLayer(BACKGROUND), LAYER.highlightSlot)
    installed.push(HL_DIM)
  }
  hl.activeFiles.forEach((file, slot) => {
    const evaluated = hl.needsEvaluation(file) ? hl.evaluatedFor(file.path) : undefined
    for (const layer of highlightLayers({ file, slot, evaluated, baseSource: OSM_SRC })) {
      try {
        m.addLayer(layer, LAYER.highlightSlot)
        installed.push(layer.id)
      } catch (e) {
        console.warn('highlight layer', layer.id, e)
      }
    }
  })
}

function onStyle() {
  installed = []
  tilesUrl = ''
  render()
}

function bind() {
  const m = mapStore.instance()
  if (!m || m === bound) return
  bound = m
  m.on('style.load', onStyle)
  if (m.isStyleLoaded() || m.getLayer(LAYER.highlightSlot)) render()
  else m.once('load', render)
}

watch(() => mapStore.ready, (r) => (r ? bind() : (bound = null)), { immediate: true })
watch(() => [hl.activeFiles, hl.evalVersion, hl.dimOthers], render)
watch(
  () => mapStore.tilesVersion,
  () => {
    if (bound) ensureSource(bound)
  },
)

onBeforeUnmount(() => {
  const m = bound
  if (!m) return
  m.off('style.load', onStyle)
  for (const id of installed) if (m.getLayer(id)) m.removeLayer(id)
  if (m.getSource(HL_SRC)) m.removeSource(HL_SRC)
})

defineExpose({ prefix: HL_PREFIX })
</script>

<template>
  <span class="hidden" />
</template>
