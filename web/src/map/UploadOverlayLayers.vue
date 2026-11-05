<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue'
import type { GeoJSONSource, Map as MlMap } from 'maplibre-gl'
import type { FeatureCollection } from 'geojson'
import { apiRequest, isAbort } from '@/api/client'
import { useMapStore } from '@/stores/map'
import { useMirrorStore } from '@/stores/mirror'

const SRC = 'ore-uploads'
const LAYERS = ['ore-uploads-fill', 'ore-uploads-line', 'ore-uploads-point']
const MIN_ZOOM = 11
const UPLOAD = '#7092ff'
const DELETED = '#e06e5f'

const mapStore = useMapStore()
const mirror = useMirrorStore()
const data = shallowRef<FeatureCollection>({ type: 'FeatureCollection', features: [] })
let bound: MlMap | null = null
let timer: ReturnType<typeof setTimeout> | null = null
let ctrl: AbortController | null = null
let supported = true

const color = ['case', ['==', ['get', 'action'], 'delete'], DELETED, UPLOAD]

function install(m: MlMap) {
  if (!m.getSource(SRC)) m.addSource(SRC, { type: 'geojson', data: data.value })
  const before = m.getLayer('ore-highlight-slot') ? 'ore-highlight-slot' : undefined
  if (!m.getLayer('ore-uploads-fill')) {
    m.addLayer(
      {
        id: 'ore-uploads-fill',
        type: 'fill',
        source: SRC,
        filter: ['==', ['geometry-type'], 'Polygon'],
        paint: { 'fill-color': color as never, 'fill-opacity': 0.08 },
      },
      before,
    )
  }
  if (!m.getLayer('ore-uploads-line')) {
    m.addLayer(
      {
        id: 'ore-uploads-line',
        type: 'line',
        source: SRC,
        filter: ['in', ['geometry-type'], ['literal', ['LineString', 'Polygon']]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': color as never,
          'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1.5, 17, 3.5],
          'line-opacity': 0.85,
          'line-dasharray': [2, 1.5],
        },
      },
      before,
    )
  }
  if (!m.getLayer('ore-uploads-point')) {
    m.addLayer(
      {
        id: 'ore-uploads-point',
        type: 'circle',
        source: SRC,
        filter: ['==', ['geometry-type'], 'Point'],
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 2.5, 17, 5],
          'circle-color': '#ffffff',
          'circle-stroke-color': color as never,
          'circle-stroke-width': 2,
          'circle-opacity': 0.9,
        },
      },
      before,
    )
  }
  sync()
}

function sync() {
  const m = bound
  if (!m) return
  ;(m.getSource(SRC) as GeoJSONSource | undefined)?.setData(data.value)
  const visible = data.value.features.length > 0
  for (const id of LAYERS) if (m.getLayer(id)) m.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none')
}

async function load() {
  timer = null
  const b = mapStore.bbox
  if (!supported || !b || mapStore.zoom < MIN_ZOOM) {
    if (data.value.features.length) {
      data.value = { type: 'FeatureCollection', features: [] }
      sync()
    }
    return
  }
  ctrl?.abort()
  ctrl = new AbortController()
  try {
    const fc = await apiRequest<FeatureCollection>('GET', `/api/overlay?bbox=${b.west},${b.south},${b.east},${b.north}`, undefined, ctrl.signal)
    data.value = { type: 'FeatureCollection', features: fc?.features ?? [] }
    sync()
  } catch (e) {
    if (isAbort(e)) return
    if ((e as { status?: number }).status === 404) supported = false
  }
}

function schedule(ms = 400) {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), ms)
}

function onStyle() {
  if (bound) install(bound)
}

function bind() {
  const m = mapStore.instance()
  if (!m || m === bound) return
  bound = m
  m.on('style.load', onStyle)
  if (m.isStyleLoaded() || m.getLayer('ore-highlight-slot')) install(m)
  else m.once('load', () => install(m))
  schedule(0)
}

watch(() => mapStore.ready, (r) => (r ? bind() : (bound = null)), { immediate: true })
watch(() => mapStore.bbox, () => schedule())
watch(() => mirror.overlayVersion, () => schedule(200))

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  ctrl?.abort()
  const m = bound
  if (!m) return
  m.off('style.load', onStyle)
  for (const id of LAYERS) if (m.getLayer(id)) m.removeLayer(id)
  if (m.getSource(SRC)) m.removeSource(SRC)
})
</script>

<template>
  <span class="hidden" />
</template>
