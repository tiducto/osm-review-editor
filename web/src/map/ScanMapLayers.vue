<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import type { GeoJSONSource, Map as MlMap, MapLayerMouseEvent } from 'maplibre-gl'
import type { FeatureCollection } from 'geojson'
import { useMapStore } from '@/stores/map'
import { useScansStore } from '@/stores/scans'
import { useDraftsStore } from '@/stores/drafts'
import { severityColor, severityRank } from '@/lib/colors'

const SRC = 'ore-scan'
const LAYERS = ['ore-scan-halo', 'ore-scan-point']
const RESOLVED = '#94a3b8'

const mapStore = useMapStore()
const scans = useScansStore()
const drafts = useDraftsStore()

let bound: MlMap | null = null

const data = computed<FeatureCollection>(() => ({
  type: 'FeatureCollection',
  features: scans.report
    ? scans.listed.map(({ issue, state }) => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [issue.centroid.lng, issue.centroid.lat] },
        properties: {
          id: issue.id,
          color: state === 'resolved' ? RESOLVED : severityColor[issue.severity],
          sev: severityRank[issue.severity],
          isNew: state === 'new' && !!scans.diff?.against,
          picked: scans.picked.has(issue.id),
          focused: scans.focused === issue.id,
        },
      }))
    : [],
}))

function install(m: MlMap) {
  if (!m.getSource(SRC)) m.addSource(SRC, { type: 'geojson', data: data.value })
  if (!m.getLayer('ore-scan-halo')) {
    m.addLayer({
      id: 'ore-scan-halo',
      type: 'circle',
      source: SRC,
      filter: ['any', ['get', 'focused'], ['get', 'picked'], ['get', 'isNew']],
      paint: {
        'circle-radius': ['case', ['get', 'focused'], 14, 10],
        'circle-color': ['case', ['get', 'focused'], '#2563eb', ['get', 'picked'], '#2563eb', '#f59e0b'],
        'circle-opacity': ['case', ['get', 'focused'], 0.35, 0.25],
      },
    })
  }
  if (!m.getLayer('ore-scan-point')) {
    m.addLayer({
      id: 'ore-scan-point',
      type: 'circle',
      source: SRC,
      layout: { 'circle-sort-key': ['get', 'sev'] },
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 3.5, 16, 6, 19, 8],
        'circle-color': ['get', 'color'],
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 1.75,
      },
    })
  }
  sync()
}

function sync() {
  const m = bound
  if (!m) return
  const src = m.getSource(SRC) as GeoJSONSource | undefined
  src?.setData(data.value)
  const visible = !drafts.reviewing && !!scans.report
  for (const id of LAYERS) if (m.getLayer(id)) m.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none')
}

function onClick(e: MapLayerMouseEvent) {
  const id = e.features?.[0]?.properties?.id
  const hit = scans.listed.find((l) => l.issue.id === Number(id))
  if (hit) scans.focus(hit.issue)
}
function onEnter() {
  if (bound) bound.getCanvas().style.cursor = 'pointer'
}
function onLeave() {
  if (bound) bound.getCanvas().style.cursor = ''
}

function onStyle() {
  if (bound) install(bound)
}

function bind() {
  const m = mapStore.instance()
  if (!m || m === bound) return
  bound = m
  m.on('style.load', onStyle)
  m.on('click', 'ore-scan-point', onClick)
  m.on('mouseenter', 'ore-scan-point', onEnter)
  m.on('mouseleave', 'ore-scan-point', onLeave)
  if (m.isStyleLoaded() || m.getLayer('ore-highlight-slot')) install(m)
  else m.once('load', () => install(m))
}

watch(() => mapStore.ready, (r) => (r ? bind() : (bound = null)), { immediate: true })
watch(data, sync)
watch(() => drafts.reviewing, sync)

onBeforeUnmount(() => {
  const m = bound
  if (!m) return
  m.off('style.load', onStyle)
  m.off('click', 'ore-scan-point', onClick)
  m.off('mouseenter', 'ore-scan-point', onEnter)
  m.off('mouseleave', 'ore-scan-point', onLeave)
  for (const id of LAYERS) if (m.getLayer(id)) m.removeLayer(id)
  if (m.getSource(SRC)) m.removeSource(SRC)
})
</script>

<template>
  <span class="hidden" />
</template>
