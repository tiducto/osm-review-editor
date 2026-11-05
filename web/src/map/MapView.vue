<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as maplibregl from 'maplibre-gl'
import type { MapGeoJSONFeature, MapMouseEvent, GeoJSONSource } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import { useAppStore } from '@/stores/app'
import { FEATURE_MIN_ZOOM, useMapStore } from '@/stores/map'
import { useRulesStore } from '@/stores/rules'
import { useDraftsStore } from '@/stores/drafts'
import { basemapUrl } from './style'
import { INTERACTIVE, ISSUE_LAYERS, SRC, installLayers, setData, setImagery, setVisible } from './layers'
import { areaToGeoJSON, beforeToGeoJSON, changesToGeoJSON, empty, featuresToGeoJSON, issuesToGeoJSON } from './data'
import { asGeometry } from '@/lib/geo'
import { PolygonDraw, drawJustEnded, type DrawState } from './draw'
import { parseRef, refString, featureTitle } from '@/lib/osm'
import { changeKindColor, changeKindLabel, type ChangeKind } from '@/lib/colors'
import { changeKind } from '@/lib/changes'
import Icon from '@/components/Icon.vue'
import Spinner from '@/components/Spinner.vue'

const app = useAppStore()
const mapStore = useMapStore()
const rules = useRulesStore()
const drafts = useDraftsStore()

const el = ref<HTMLDivElement | null>(null)
let map: maplibregl.Map | null = null
let styleReady = false
let draw: PolygonDraw | null = null
const drawState = ref<DrawState>({ count: 0, canFinish: false })

maplibregl.setWorkerUrl(workerUrl)

const VIEW_KEY = 'ore.view'

function initialView(): { center: [number, number]; zoom: number } {
  try {
    const v = JSON.parse(localStorage.getItem(VIEW_KEY) ?? 'null') as { c: [number, number]; z: number } | null
    if (v && Array.isArray(v.c)) return { center: v.c, zoom: v.z }
  } catch {}
  return { center: [14.42, 50.08], zoom: 12 }
}

function syncFeatures() {
  if (!map || !styleReady) return
  setData(map, SRC.osm, featuresToGeoJSON(mapStore.features))
  syncHighlight()
}
function syncHighlight() {
  if (!map || !styleReady) return
  const keys = new Set(mapStore.highlighted.map(refString))
  setData(map, SRC.highlight, keys.size ? featuresToGeoJSON(mapStore.features.filter((f) => keys.has(refString(f.ref)))) : empty)
}
function syncSelection() {
  if (!map || !styleReady) return
  const f = mapStore.selectedFeature
  setData(map, SRC.selection, f && !drafts.reviewing ? featuresToGeoJSON([f]) : empty)
}
function syncIssues() {
  if (!map || !styleReady) return
  setData(map, SRC.issues, issuesFC.value)
  setVisible(map, ISSUE_LAYERS, !drafts.reviewing)
}
function syncArea() {
  if (!map || !styleReady) return
  const draftScope = drafts.reviewing ? asGeometry(drafts.current?.draft.scope) : null
  const g = draftScope ?? mapStore.polygon
  setData(map, SRC.area, g ? { type: 'FeatureCollection', features: [{ type: 'Feature', properties: { g: 'polygon' }, geometry: g }] } : areaToGeoJSON(mapStore.area))
}
function syncChanges() {
  if (!map || !styleReady) return
  const c = drafts.reviewing ? drafts.currentChange : null
  setData(map, SRC.changes, drafts.reviewing ? changesToGeoJSON(drafts.changes, c?.id ?? null) : empty)
  setData(map, SRC.before, beforeToGeoJSON(c, drafts.showBefore))
  const hideCurrent = drafts.reviewing && drafts.showBefore
  for (const id of ['ore-changes-fill', 'ore-changes-line', 'ore-changes-point', 'ore-changes-halo']) {
    if (!map.getLayer(id)) continue
    const base = id.endsWith('point') ? ['==', ['get', 'g'], 'point'] : id.endsWith('fill') ? ['==', ['get', 'g'], 'polygon'] : ['in', ['get', 'g'], ['literal', ['line', 'polygon']]]
    const parts: unknown[] = ['all', base]
    if (id.endsWith('halo')) parts.push(['boolean', ['get', 'current'], false])
    if (hideCurrent) parts.push(['!', ['boolean', ['get', 'current'], false]])
    map.setFilter(id, parts as maplibregl.FilterSpecification)
  }
}
function syncContext() {
  if (!map || !styleReady) return
  setData(map, SRC.context, drafts.reviewing && drafts.context ? featuresToGeoJSON(drafts.context.features) : empty)
}
function syncImagery() {
  if (!map || !styleReady) return
  setImagery(map, mapStore.activeImagery)
}
function syncAll() {
  syncArea()
  syncFeatures()
  syncSelection()
  syncIssues()
  syncChanges()
  syncContext()
  syncImagery()
}

const issuesFC = computed(() => issuesToGeoJSON(rules.shownIssues))

watch(() => mapStore.features, syncFeatures)
watch(() => mapStore.highlighted, syncHighlight, { deep: true })
watch(() => [mapStore.selectedFeature, drafts.reviewing], syncSelection)
watch(issuesFC, syncIssues)
watch(() => drafts.reviewing, syncIssues)
watch(() => [mapStore.area, mapStore.polygon, drafts.reviewing, drafts.current?.draft.scope], syncArea)
watch(
  () => mapStore.drawing,
  (on) => {
    hover.value = null
    if (on) draw?.start()
    else draw?.stop()
  },
)
watch(
  () => drafts.reviewing,
  () => (hover.value = null),
)
watch(() => [drafts.current, drafts.index, drafts.reviewing, drafts.showBefore], syncChanges)
watch(() => [drafts.context, drafts.reviewing], syncContext)
watch(() => mapStore.activeImagery, syncImagery)

watch(
  () => app.isDark,
  (dark) => {
    if (!map) return
    styleReady = false
    map.setStyle(basemapUrl(dark))
  },
)

let moveTimer: ReturnType<typeof setTimeout> | null = null

function reportView() {
  if (!map) return
  const b = map.getBounds()
  mapStore.updateView({ west: b.getWest(), south: b.getSouth(), east: b.getEast(), north: b.getNorth() }, map.getZoom())
  try {
    const c = map.getCenter()
    localStorage.setItem(VIEW_KEY, JSON.stringify({ c: [c.lng, c.lat], z: map.getZoom() }))
  } catch {}
}

function onMoveEnd() {
  reportView()
  if (moveTimer) clearTimeout(moveTimer)
  moveTimer = setTimeout(() => {
    void mapStore.loadFeatures()
    rules.onViewChanged()
  }, 300)
}

function interactiveLayers(): string[] {
  const ids = drafts.reviewing
    ? [...INTERACTIVE.changes, ...INTERACTIVE.context, ...INTERACTIVE.osm]
    : [...INTERACTIVE.issues, ...INTERACTIVE.clusters, ...INTERACTIVE.osm]
  return ids.filter((id) => map?.getLayer(id))
}

function topFeature(e: MapMouseEvent): MapGeoJSONFeature | null {
  if (!map) return null
  const pad = 4
  const box: [maplibregl.PointLike, maplibregl.PointLike] = [
    [e.point.x - pad, e.point.y - pad],
    [e.point.x + pad, e.point.y + pad],
  ]
  const hits = map.queryRenderedFeatures(box, { layers: interactiveLayers() })
  const rank = (f: MapGeoJSONFeature) => (f.layer.type === 'circle' ? 0 : f.layer.type === 'line' ? 1 : 2)
  return hits.sort((a, b) => rank(a) - rank(b))[0] ?? null
}

async function onClick(e: MapMouseEvent) {
  if (mapStore.drawing || drawJustEnded()) return
  const f = topFeature(e)
  if (!f || !map) {
    if (!drafts.reviewing) void mapStore.select(null)
    return
  }
  const layer = f.layer.id
  const props = f.properties as Record<string, unknown>
  if (layer === 'ore-issues-cluster') {
    const src = map.getSource(SRC.issues) as GeoJSONSource
    const zoom = await src.getClusterExpansionZoom(props.cluster_id as number)
    const g = f.geometry
    if (g.type === 'Point') map.easeTo({ center: g.coordinates as [number, number], zoom: zoom + 0.3 })
    return
  }
  if (layer.startsWith('ore-changes')) {
    drafts.goTo(props.i as number)
    return
  }
  if (drafts.reviewing) return
  const ref = parseRef(String(props.key ?? ''))
  if (ref) void mapStore.select(ref)
}

const hover = ref<{ x: number; y: number; text: string; sub?: string; color?: string } | null>(null)

function onMouseMove(e: MapMouseEvent) {
  if (!map || mapStore.drawing) return
  const f = topFeature(e)
  map.getCanvas().style.cursor = f ? 'pointer' : ''
  if (!f) {
    hover.value = null
    return
  }
  const props = f.properties as Record<string, unknown>
  const layer = f.layer.id
  let text = ''
  let sub: string | undefined
  let color: string | undefined
  if (layer === 'ore-issues-cluster') {
    text = `${props.point_count} issues`
    sub = 'Click to zoom in'
  } else if (layer === 'ore-issues-point') {
    const rule = rules.ruleById.get(String(props.ruleId))
    text = rule?.title ?? String(props.ruleId)
    sub = String(props.key)
    color = String(props.color)
  } else if (layer.startsWith('ore-changes')) {
    const c = drafts.changes[props.i as number]
    if (c) {
      const kind = changeKind(c) as ChangeKind
      text = featureTitle(c.ref, c.newTags)
      sub = `${changeKindLabel[kind]} · ${refString(c.ref)}`
      color = changeKindColor[kind]
    }
  } else if (layer.startsWith('ore-context')) {
    text = 'Context'
    sub = String(props.key)
  } else {
    const ref = parseRef(String(props.key ?? ''))
    const feat = ref ? mapStore.features.find((x) => x.ref.type === ref.type && x.ref.id === ref.id) : undefined
    text = feat ? featureTitle(feat.ref, feat.tags) : String(props.key)
    sub = String(props.key)
  }
  hover.value = { x: e.point.x, y: e.point.y, text, sub, color }
}

const hoverStyle = computed(() => {
  const h = hover.value
  const box = el.value
  if (!h || !box) return {}
  const flipX = h.x + 14 + 260 > box.clientWidth
  const flipY = h.y + 10 + 56 > box.clientHeight
  return {
    left: `${flipX ? h.x - 10 : h.x + 14}px`,
    top: `${flipY ? h.y - 8 : h.y + 10}px`,
    transform: `translate(${flipX ? '-100%' : '0'}, ${flipY ? '-100%' : '0'})`,
  }
})

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) return
  const v = initialView()
  map = new maplibregl.Map({
    container: el.value,
    style: basemapUrl(app.isDark),
    center: v.center,
    zoom: v.zoom,
    attributionControl: { compact: true },
    maxPitch: 0,
    dragRotate: false,
    pitchWithRotate: false,
    fadeDuration: 100,
  })
  map.touchZoomRotate.disableRotation()
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right')
  map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left')
  map.on('style.load', () => {
    if (!map) return
    installLayers(map, app.isDark)
    styleReady = true
    syncAll()
    draw?.redraw()
  })
  map.on('moveend', onMoveEnd)
  map.on('click', (e) => void onClick(e))
  map.on('mousemove', onMouseMove)
  map.on('mouseout', () => (hover.value = null))
  map.once('load', () => {
    reportView()
    onMoveEnd()
  })
  draw = new PolygonDraw(map, {
    finish: (p) => mapStore.setPolygon(p),
    cancel: () => (mapStore.drawing = false),
    change: (s) => (drawState.value = s),
  })
  if (mapStore.drawing) draw.start()
  mapStore.attach(map)
  if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__oreMap = map
  resizeObserver = new ResizeObserver(() => map?.resize())
  resizeObserver.observe(el.value)
})

onBeforeUnmount(() => {
  draw?.stop()
  draw = null
  resizeObserver?.disconnect()
  mapStore.detach()
  map?.remove()
  map = null
})

const zoomHint = computed(() => {
  if (drafts.reviewing) return null
  if (rules.needsZoom) return 'Pick an area or zoom in to load issues'
  if (mapStore.zoom < FEATURE_MIN_ZOOM) return null
  if (mapStore.featuresTruncated) return 'Too many objects here; showing the first 5,000'
  return null
})
const rulesLoading = computed(() => rules.loading.size > 0)
const kinds: ChangeKind[] = ['tags', 'geometry', 'created', 'deleted']
const currentHasGeometryChange = computed(() => {
  const c = drafts.currentChange
  return !!c && (!!c.newGeom || c.action !== 'modify')
})
</script>

<template>
  <div class="relative h-full w-full overflow-hidden">
    <div ref="el" class="absolute inset-0" />

    <div
      v-if="hover"
      class="pointer-events-none absolute z-10 max-w-64 rounded-md border border-line bg-surface/95 px-2 py-1 text-[12px] shadow-panel backdrop-blur"
      :style="hoverStyle"
    >
      <div class="flex items-center gap-1.5 font-medium">
        <span v-if="hover.color" class="size-2 shrink-0 rounded-full" :style="{ background: hover.color }" />
        <span class="truncate">{{ hover.text }}</span>
      </div>
      <div v-if="hover.sub" class="font-mono text-[10.5px] text-fg-3">{{ hover.sub }}</div>
    </div>

    <div class="pointer-events-none absolute top-3 left-1/2 z-10 flex w-max max-w-[calc(100%-24px)] -translate-x-1/2 flex-col items-center gap-2">
      <div
        v-if="mapStore.drawing"
        class="pointer-events-auto flex flex-wrap items-center justify-center gap-x-3 gap-y-1.5 rounded-lg border border-accent/50 bg-surface/95 px-3 py-2 text-[12px] text-fg-2 shadow-pop backdrop-blur"
        data-testid="draw-banner"
      >
        <span class="flex items-center gap-1.5 font-medium text-fg"><Icon name="edit" :size="13" class="text-accent" /> Drawing polygon</span>
        <span>
          {{ drawState.count ? `${drawState.count} point${drawState.count === 1 ? '' : 's'}` : 'Click the map to add points' }}<template
            v-if="drawState.count"
          > · double-click or click the first point to finish</template>
        </span>
        <span class="flex items-center gap-1">
          <button class="btn btn-sm" :disabled="!drawState.count" title="Remove last point (Backspace)" @click="draw?.undo()">
            <Icon name="undo" :size="12" /> Undo
          </button>
          <button class="btn btn-primary btn-sm" :disabled="!drawState.canFinish" title="Finish (Enter)" @click="draw?.finish()">Finish</button>
          <button class="btn btn-ghost btn-sm" title="Cancel (Esc)" @click="draw?.cancel()">Cancel <span class="kbd">Esc</span></button>
        </span>
      </div>
      <Transition name="fade">
        <div v-if="zoomHint" class="rounded-full border border-line bg-surface/95 px-3 py-1 text-[12px] text-fg-2 shadow-panel backdrop-blur">
          {{ zoomHint }}
        </div>
      </Transition>
      <Transition name="fade">
        <div
          v-if="(mapStore.featuresLoading || rulesLoading) && !drafts.reviewing"
          class="flex items-center gap-2 rounded-full border border-line bg-surface/95 px-3 py-1 text-[12px] text-fg-2 shadow-panel backdrop-blur"
        >
          <Spinner :size="12" /> {{ rulesLoading ? 'Running rules…' : 'Loading objects…' }}
        </div>
      </Transition>
    </div>

    <Transition name="fade">
      <div v-if="drafts.reviewing" class="absolute top-3 left-3 z-10 flex flex-col gap-2">
        <div class="flex items-center gap-3 rounded-lg border border-line bg-surface/95 px-3 py-1.5 text-[11.5px] text-fg-2 shadow-panel backdrop-blur">
          <span v-for="k in kinds" :key="k" class="flex items-center gap-1.5">
            <span class="h-[3px] w-3.5 rounded-full" :style="{ background: changeKindColor[k] }" />{{ changeKindLabel[k] }}
          </span>
          <span class="flex items-center gap-1.5"><span class="h-[3px] w-3.5 rounded-full bg-[#64748b] opacity-60" />Context</span>
        </div>
        <div class="flex w-fit items-center rounded-lg border border-line bg-surface/95 p-0.5 text-[12px] shadow-panel backdrop-blur">
          <button
            class="rounded-md px-2.5 py-1 font-medium transition-colors"
            :class="drafts.showBefore ? 'bg-surface-3 text-fg' : 'text-fg-3 hover:text-fg'"
            @click="drafts.showBefore = true"
          >
            Before
          </button>
          <button
            class="rounded-md px-2.5 py-1 font-medium transition-colors"
            :class="!drafts.showBefore ? 'bg-surface-3 text-fg' : 'text-fg-3 hover:text-fg'"
            @click="drafts.showBefore = false"
          >
            After
          </button>
          <span class="flex items-center gap-1 px-2 text-fg-3" :title="currentHasGeometryChange ? '' : 'Tag-only change: geometry is the same'">
            <span class="kbd">Space</span>
          </span>
        </div>
      </div>
    </Transition>

    <div
      v-if="mapStore.activeImagery && !drafts.reviewing"
      class="pointer-events-none absolute bottom-9 left-3 z-10 flex items-center gap-1.5 rounded-md bg-surface/90 px-2 py-0.5 text-[11px] text-fg-2 shadow-panel"
    >
      <Icon name="image" :size="12" /> {{ mapStore.activeImagery.name }}
    </div>
  </div>
</template>
