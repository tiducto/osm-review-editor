<script setup lang="ts">
import { useI18n } from '@/i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as maplibregl from 'maplibre-gl'
import type { MapGeoJSONFeature, MapMouseEvent, GeoJSONSource, VectorTileSource } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import type { OsmRef, OsmType } from '@/generated/models'
import { setDashState, useMapStore } from '@/stores/map'
import { useRulesStore } from '@/stores/rules'
import { useDraftsStore } from '@/stores/drafts'
import { INTERACTIVE, ISSUE_LAYERS, SRC, installLayers, setData, setImagery, setVisible } from './layers'
import { areaToGeoJSON, beforeToGeoJSON, changesToGeoJSON, empty, featuresToGeoJSON, issuesToGeoJSON, selectionToGeoJSON } from './data'
import { asGeometry } from '@/lib/geo'
import { PolygonDraw, drawJustEnded, type DrawState } from './draw'
import { applyFeatureFilters } from './osm/features'
import { changeKindColor, changeKindLabel, type ChangeKind } from '@/lib/colors'
import { baseStyle, DASHED_LAYERS, dashExpr, HOVER_LAYERS, INTERACTIVE_OSM, lowZoomThreshold, OSM_SRC, parseDash } from './osm/style'
import { clearTileCache, gateTiles, registerTileProtocol, tileUrl } from './osm/protocol'
import { addOneway, addPatterns, addShield } from './osm/icons'
import { PATTERN_IMAGES } from './osm/patterns'
import { knownDashes, OUT, type Props } from './osm/transform'
import Icon from '@/components/Icon.vue'
import Spinner from '@/components/Spinner.vue'
import EditToolbar from '@/components/editor/EditToolbar.vue'
import EditMenu from '@/components/editor/EditMenu.vue'
import { useEditorStore } from '@/stores/editor'

const mapStore = useMapStore()
const { t, tn } = useI18n()
const rules = useRulesStore()
const drafts = useDraftsStore()
const editor = useEditorStore()

const el = ref<HTMLDivElement | null>(null)
let map: maplibregl.Map | null = null
let styleReady = false
let draw: PolygonDraw | null = null
const drawState = ref<DrawState>({ count: 0, canFinish: false })

maplibregl.setWorkerUrl(workerUrl)

const VIEW_KEY = 'ore.view'
const TYPE_CODE: Record<OsmType, number> = { node: 0, way: 1, relation: 2 }

function initialView(): { center: [number, number]; zoom: number } {
  try {
    const v = JSON.parse(localStorage.getItem(VIEW_KEY) ?? 'null') as { c: [number, number]; z: number } | null
    if (v && Array.isArray(v.c)) return { center: v.c, zoom: v.z }
  } catch {}
  return { center: [14.42, 50.08], zoom: 12 }
}

const dashes = new Map(knownDashes().map((k) => [k, parseDash(k)]))
let lowZoom = 16
let dashTimer: ReturnType<typeof setTimeout> | null = null

function onDashes(keys: string[]) {
  let added = false
  for (const k of keys) {
    if (dashes.has(k)) continue
    dashes.set(k, parseDash(k))
    added = true
  }
  if (!added || dashTimer) return
  dashTimer = setTimeout(() => {
    dashTimer = null
    if (!map || !styleReady) return
    for (const [id, p] of DASHED_LAYERS) {
      for (const layer of [id, `edit-${id}`]) {
        if (map.getLayer(layer)) map.setPaintProperty(layer, 'line-dasharray', dashExpr(dashes, p, lowZoom))
      }
    }
  }, 50)
}

function featureId(r: OsmRef): number {
  return r.id * 4 + TYPE_CODE[r.type]
}

let highlightedIds: number[] = []
function syncHighlight() {
  if (!map || !styleReady) return
  for (const id of highlightedIds) for (const sourceLayer of HOVER_LAYERS) map.setFeatureState({ source: OSM_SRC, sourceLayer, id }, { hl: false })
  highlightedIds = mapStore.highlighted.map(featureId)
  for (const id of highlightedIds) for (const sourceLayer of HOVER_LAYERS) map.setFeatureState({ source: OSM_SRC, sourceLayer, id }, { hl: true })
}

function isVertexInTiles(r: OsmRef): boolean {
  if (!map || r.type !== 'node') return false
  return map.querySourceFeatures(OSM_SRC, { sourceLayer: OUT.vertex, filter: ['==', ['get', '@id'], r.id] }).length > 0
}

function syncSelection() {
  if (!map || !styleReady) return
  const f = mapStore.selectedFeature
  setData(map, SRC.selection, f && !drafts.reviewing ? selectionToGeoJSON(f, isVertexInTiles(f.ref)) : empty)
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
  syncHighlight()
  syncSelection()
  syncIssues()
  syncChanges()
  syncContext()
  syncImagery()
}

const issuesFC = computed(() => issuesToGeoJSON(rules.shownIssues))

watch(() => mapStore.highlighted, syncHighlight, { deep: true })
watch(() => [mapStore.selectedFeature, drafts.reviewing], syncSelection)
watch(issuesFC, syncIssues)
watch(() => drafts.reviewing, syncIssues)
watch(() => [mapStore.area, mapStore.polygon, drafts.reviewing, drafts.current?.draft.scope], syncArea)
watch(
  () => mapStore.drawing,
  (on) => {
    clearHover()
    if (on) draw?.start()
    else draw?.stop()
  },
)
watch(
  () => drafts.reviewing,
  () => clearHover(),
)
watch(
  () => mapStore.drawing,
  () => cancelPopup(),
)
watch(
  () => editor.active,
  () => clearHover(),
)
watch(
  () => mapStore.hiddenFeatures,
  (list) => {
    if (map && styleReady) applyFeatureFilters(map, list)
  },
)

watch(() => [drafts.current, drafts.index, drafts.reviewing, drafts.showBefore], syncChanges)
watch(() => [drafts.context, drafts.reviewing], syncContext)
watch(() => mapStore.activeImagery, syncImagery)
watch(
  () => mapStore.tilesVersion,
  (v) => {
    clearTileCache()
    const src = map?.getSource(OSM_SRC) as VectorTileSource | undefined
    src?.setTiles([tileUrl(v)])
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
  moveTimer = setTimeout(() => rules.onViewChanged(), 300)
}

function interactiveLayers(): string[] {
  const ids = drafts.reviewing
    ? [...INTERACTIVE.changes, ...INTERACTIVE.context, ...INTERACTIVE_OSM]
    : [...INTERACTIVE.issues, ...INTERACTIVE.clusters, ...INTERACTIVE_OSM]
  return ids.filter((id) => map?.getLayer(id))
}

function osmRef(f: MapGeoJSONFeature): OsmRef | null {
  if (f.source !== OSM_SRC) return null
  const p = f.properties as Props
  const id = Number(p['@id'])
  const type = p['@type'] as OsmType | undefined
  return id && type ? { type, id } : null
}

function topFeature(e: MapMouseEvent): MapGeoJSONFeature | null {
  if (!map) return null
  const pad = 4
  const box: [maplibregl.PointLike, maplibregl.PointLike] = [
    [e.point.x - pad, e.point.y - pad],
    [e.point.x + pad, e.point.y + pad],
  ]
  const hits = map.queryRenderedFeatures(box, { layers: interactiveLayers() }).filter((f) => f.source !== OSM_SRC || osmRef(f))
  const rank = (f: MapGeoJSONFeature) => (f.source !== OSM_SRC ? -1 : f.layer.type === 'circle' || f.layer.type === 'symbol' ? 0 : f.layer.type === 'line' ? 1 : 2)
  return hits.sort((a, b) => rank(a) - rank(b))[0] ?? null
}

async function onClick(e: MapMouseEvent) {
  if (editor.active || mapStore.drawing || drawJustEnded()) return
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
  const r = f.source === OSM_SRC ? osmRef(f) : null
  if (r) {
    void mapStore.select(r)
    return
  }
  if (layer === 'ore-issues-point') {
    const key = String(props.key ?? '')
    const m = /^([nwr])(\d+)$/.exec(key)
    if (m) void mapStore.select({ type: m[1] === 'n' ? 'node' : m[1] === 'w' ? 'way' : 'relation', id: Number(m[2]) })
  }
}

const hover = ref<{ x: number; y: number; text: string; sub?: string; color?: string } | null>(null)
let hoverState: { sourceLayer: string; id: number } | null = null

function setHoverState(next: { sourceLayer: string; id: number } | null) {
  if (!map) return
  if (hoverState && (!next || next.id !== hoverState.id || next.sourceLayer !== hoverState.sourceLayer)) {
    map.setFeatureState({ source: OSM_SRC, ...hoverState }, { hover: false })
  }
  hoverState = next
  if (next) map.setFeatureState({ source: OSM_SRC, ...next }, { hover: true })
}

const HOVER_DELAY = 800
let hoverTimer: ReturnType<typeof setTimeout> | null = null

function cancelPopup() {
  if (hoverTimer) clearTimeout(hoverTimer)
  hoverTimer = null
  hover.value = null
}

function clearHover() {
  cancelPopup()
  setHoverState(null)
}

function issuePopup(f: MapGeoJSONFeature, x: number, y: number) {
  const props = f.properties as Record<string, unknown>
  const rule = rules.ruleById.get(String(props.ruleId))
  return { x, y, text: rule?.title ?? String(props.ruleId), sub: String(props.key), color: String(props.color) }
}

function onMouseMove(e: MapMouseEvent) {
  if (!map) return
  cancelPopup()
  if (mapStore.drawing) return
  if (editor.active) {
    setHoverState(null)
    return
  }
  const f = topFeature(e)
  map.getCanvas().style.cursor = f ? 'pointer' : ''
  if (!f) {
    setHoverState(null)
    return
  }
  if (f.source === OSM_SRC) {
    const sourceLayer = f.sourceLayer === OUT.band ? OUT.area : (f.sourceLayer ?? OUT.area)
    setHoverState(typeof f.id === 'number' ? { sourceLayer, id: f.id } : null)
    return
  }
  setHoverState(null)
  if (f.layer.id !== 'ore-issues-point' || drafts.reviewing || (e.originalEvent as MouseEvent).buttons) return
  const { x, y } = e.point
  hoverTimer = setTimeout(() => {
    hoverTimer = null
    if (!map || map.isMoving() || mapStore.drawing || editor.active || drafts.reviewing) return
    hover.value = issuePopup(f, x, y)
  }, HOVER_DELAY)
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
  lowZoom = lowZoomThreshold(v.center[1])
  map = new maplibregl.Map({
    container: el.value,
    style: baseStyle(tileUrl(mapStore.tilesVersion), v.center[1], dashes),
    center: v.center,
    zoom: v.zoom,
    maxZoom: 22,
    attributionControl: { compact: true },
    maxPitch: 0,
    dragRotate: false,
    pitchWithRotate: false,
    fadeDuration: 100,
    trackResize: false,
  })
  registerTileProtocol(map, onDashes)
  setDashState({ dashes, lowZoom, add: onDashes })
  map.touchZoomRotate.disableRotation()
  map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left')
  map.on('style.load', () => {
    if (!map) return
    addShield(map)
    addOneway(map)
    gateTiles(addPatterns(map, PATTERN_IMAGES))
    installLayers(map)
    applyFeatureFilters(map, mapStore.hiddenFeatures)
    styleReady = true
    syncAll()
    draw?.redraw()
  })
  map.on('moveend', onMoveEnd)
  map.on('click', (e) => void onClick(e))
  map.on('mousemove', onMouseMove)
  map.on('mouseout', clearHover)
  map.on('movestart', cancelPopup)
  map.on('mousedown', cancelPopup)
  map.on('sourcedata', (e) => {
    if (e.sourceId === OSM_SRC && e.isSourceLoaded && mapStore.selectedFeature?.ref.type === 'node') syncSelection()
  })
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
  ;(window as unknown as Record<string, unknown>).__oreMap = map
  resizeObserver = new ResizeObserver(() => {
    if (!map) return
    const c = map.getContainer()
    const canvas = map.getCanvas()
    if (c.clientWidth === canvas.clientWidth && c.clientHeight === canvas.clientHeight) return
    map.resize()
    if (styleReady) map.redraw()
  })
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
  if (rules.needsZoom) return t('shell.map.needsZoom')
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
  <div
    class="ore-map relative h-full w-full overflow-hidden"
    :style="{ '--ore-l': `${mapStore.insets.left}px`, '--ore-r': `${mapStore.insets.right}px`, '--ore-b': `${mapStore.insets.bottom}px`, '--ore-t': `${mapStore.insets.top}px` }"
  >
    <div ref="el" class="absolute inset-0" data-testid="map" />

    <EditMenu v-if="editor.menu" />
    <div
      v-if="hover"
      data-testid="map-popup"
      class="pointer-events-none absolute z-10 max-w-64 rounded-md border border-line bg-surface/95 px-2 py-1 text-[12px] shadow-panel backdrop-blur"
      :style="hoverStyle"
    >
      <div class="flex items-center gap-1.5 font-bold">
        <span v-if="hover.color" class="size-2 shrink-0 rounded-full" :style="{ background: hover.color }" />
        <span class="truncate">{{ hover.text }}</span>
      </div>
      <div v-if="hover.sub" class="font-mono text-[10.5px] text-fg-3">{{ hover.sub }}</div>
    </div>

    <div class="ore-safe pointer-events-none absolute">
    <div class="pointer-events-none absolute top-3 left-1/2 z-10 flex w-max max-w-[calc(100%-24px)] -translate-x-1/2 flex-col items-center gap-2">
      <EditToolbar v-if="!drafts.reviewing && !mapStore.drawing" />
      <div
        v-if="mapStore.drawing"
        class="pointer-events-auto flex flex-wrap items-center justify-center gap-x-3 gap-y-1.5 rounded-md bg-surface px-3 py-2 text-[12px] text-fg-2 shadow-pop"
        data-testid="draw-banner"
      >
        <span class="flex items-center gap-1.5 font-bold text-fg"><Icon name="edit" :size="14" class="text-accent" /> {{ t('shell.map.drawingPolygon') }}</span>
        <span>
          {{ drawState.count ? tn('shell.map.points', drawState.count) + t('shell.map.finishHint') : t('shell.map.clickToAdd') }}
        </span>
        <span class="flex items-center gap-1">
          <button class="btn btn-sm" :disabled="!drawState.count" :title="t('shell.map.removeLast')" @click="draw?.undo()">
            <Icon name="undo" :size="12" /> {{ t('common.undo') }}
          </button>
          <button class="btn btn-primary btn-sm" :disabled="!drawState.canFinish" :title="t('shell.map.finishTitle')" @click="draw?.finish()">{{ t('shell.map.finish') }}</button>
          <button class="btn btn-ghost btn-sm" :title="t('shell.map.cancelTitle')" @click="draw?.cancel()">{{ t('common.cancel') }} <span class="kbd">Esc</span></button>
        </span>
      </div>
      <Transition name="fade">
        <div v-if="zoomHint" class="rounded-md bg-[var(--tooltip-bg)] px-3 py-1 text-[12px] font-bold text-white shadow-panel">
          {{ zoomHint }}
        </div>
      </Transition>
      <Transition name="fade">
        <div
          v-if="rulesLoading && !drafts.reviewing"
          class="flex items-center gap-2 rounded-md bg-[var(--tooltip-bg)] px-3 py-1 text-[12px] font-bold text-white shadow-panel"
        >
          <Spinner :size="12" /> {{ t('shell.map.runningRules') }}
        </div>
      </Transition>
    </div>

    <Transition name="fade">
      <div v-if="drafts.reviewing" class="pointer-events-auto absolute top-3 left-3 z-10 flex max-w-[calc(100%-24px)] flex-col gap-2">
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-line-strong bg-surface px-3 py-1.5 text-[11.5px] text-fg-2 shadow-panel">
          <span v-for="k in kinds" :key="k" class="flex items-center gap-1.5">
            <span class="h-[3px] w-3.5 rounded-full" :style="{ background: changeKindColor[k] }" />{{ changeKindLabel[k] }}
          </span>
          <span class="flex items-center gap-1.5"><span class="h-[3px] w-3.5 rounded-full bg-[#64748b] opacity-60" />{{ t('shell.map.context') }}</span>
        </div>
        <div class="seg w-fit items-center text-[12px] shadow-panel">
          <button
            class="seg-btn h-7 px-2.5"
            :class="drafts.showBefore && 'active'"
            @click="drafts.showBefore = true"
          >
            {{ t('shell.map.before') }}
          </button>
          <button
            class="seg-btn h-7 px-2.5"
            :class="!drafts.showBefore && 'active'"
            @click="drafts.showBefore = false"
          >
            {{ t('shell.map.after') }}
          </button>
          <span class="flex items-center gap-1 border-l border-line-strong px-2 text-fg-3" :title="currentHasGeometryChange ? '' : t('shell.map.tagOnly')">
            <span class="kbd">Space</span>
          </span>
        </div>
      </div>
    </Transition>

    <div
      v-if="mapStore.activeImagery && !drafts.reviewing"
      class="pointer-events-none absolute bottom-9 left-3 z-10 flex items-center gap-1.5 rounded-sm bg-black/50 px-2 py-0.5 text-[11px] text-white"
    >
      <Icon name="image" :size="12" /> {{ mapStore.activeImagery.name }}
    </div>
    </div>
  </div>
</template>

<style>
.ore-map .ore-safe {
  left: var(--ore-l);
  right: var(--ore-r);
  top: var(--ore-t);
  bottom: var(--ore-b);
}
.ore-map .ore-safe,
.ore-map .maplibregl-ctrl-bottom-left,
.ore-map .maplibregl-ctrl-bottom-right,
.ore-map .maplibregl-ctrl-top-left,
.ore-map .maplibregl-ctrl-top-right {
  transition-property: left, right, top, bottom;
  transition-duration: 200ms;
  transition-timing-function: cubic-bezier(0.4, 0, 0.2, 1);
}
.ore-map .maplibregl-ctrl-bottom-left {
  left: var(--ore-l);
  bottom: var(--ore-b);
}
.ore-map .maplibregl-ctrl-bottom-right {
  right: var(--ore-r);
  bottom: var(--ore-b);
}
.ore-map .maplibregl-ctrl-top-left {
  left: var(--ore-l);
  top: var(--ore-t);
}
.ore-map .maplibregl-ctrl-top-right {
  right: var(--ore-r);
  top: var(--ore-t);
}
</style>
