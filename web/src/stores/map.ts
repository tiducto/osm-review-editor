import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import type { Map as MlMap } from 'maplibre-gl'
import type { Polygon } from 'geojson'
import type { Area, BBox, ImagerySource, LngLat, MapFeature, OsmRef } from '@/generated/models'
import { api } from '@/api/client'
import { useAppStore } from './app'
import { refString, sameRef } from '@/lib/osm'
import { asGeometry, geometryBBox } from '@/lib/geo'
import { t } from '@/i18n'
import { readHiddenFeatures, writeHiddenFeatures, type FeatureGroup } from '@/map/osm/features'

const IMAGERY_KEY = 'ore.imagery'

let map: MlMap | null = null

export interface DashState {
  dashes: Map<string, number[]>
  lowZoom: number
  add: (keys: string[]) => void
}

let dashState: DashState | null = null

export function setDashState(s: DashState | null) {
  dashState = s
}

export function getDashState(): DashState | null {
  return dashState
}

export interface FitPadding {
  top: number
  bottom: number
  left: number
  right: number
}

export const useMapStore = defineStore('map', () => {
  const app = useAppStore()

  const ready = ref(false)
  const bbox = ref<BBox | null>(null)
  const zoom = ref(0)

  const tilesVersion = ref(app.mirror?.openedAt ?? String(Date.now()))

  const selected = ref<OsmRef | null>(null)
  const selectedFeature = shallowRef<MapFeature | null>(null)
  const selectedLoading = ref(false)
  const highlighted = ref<OsmRef[]>([])

  const area = shallowRef<Area | null>(null)
  const areaLoading = ref(false)
  const polygon = shallowRef<Polygon | null>(null)
  const drawing = ref(false)
  const scopeName = computed(() => area.value?.name ?? (polygon.value ? t('area.drawnPolygon') : null))

  const extraImagery = ref<ImagerySource[]>([])
  const imageryId = ref<string | null>(readImagery())
  const allImagery = computed(() => [...app.imagery, ...extraImagery.value.filter((x) => !app.imagery.some((i) => i.id === x.id))])
  const activeImagery = computed(() => allImagery.value.find((i) => i.id === imageryId.value) ?? null)

  function readImagery(): string | null {
    try {
      return localStorage.getItem(IMAGERY_KEY)
    } catch {
      return null
    }
  }

  function setImagery(id: string | null) {
    imageryId.value = id
    try {
      if (id) localStorage.setItem(IMAGERY_KEY, id)
      else localStorage.removeItem(IMAGERY_KEY)
    } catch {}
  }

  function addImagery(src: ImagerySource) {
    if (!allImagery.value.some((i) => i.id === src.id)) extraImagery.value.push(src)
    setImagery(src.id)
  }

  function attach(m: MlMap) {
    map = m
    ready.value = true
  }
  function detach() {
    map = null
    ready.value = false
  }
  function instance(): MlMap | null {
    return map
  }

  const insets = ref<FitPadding>({ top: 0, bottom: 0, left: 0, right: 0 })
  function setInsets(next: FitPadding) {
    const cur = insets.value
    if (cur.top === next.top && cur.bottom === next.bottom && cur.left === next.left && cur.right === next.right) return
    insets.value = { ...next }
  }
  function fitPadding(): FitPadding {
    const i = insets.value
    return { top: i.top + 64, bottom: i.bottom + 64, left: i.left + 64, right: i.right + 64 }
  }
  function visibleOffset(): [number, number] {
    const i = insets.value
    return [(i.left - i.right) / 2, (i.top - i.bottom) / 2]
  }

  const hiddenFeatures = ref<FeatureGroup[]>(readHiddenFeatures())
  function setFeatureVisible(group: FeatureGroup, visible: boolean) {
    const next = hiddenFeatures.value.filter((g) => g !== group)
    if (!visible) next.push(group)
    hiddenFeatures.value = next
    writeHiddenFeatures(next)
  }
  function setHiddenFeatures(groups: FeatureGroup[]) {
    hiddenFeatures.value = [...new Set(groups)]
    writeHiddenFeatures(hiddenFeatures.value)
  }

  function visibleContains(lng: number, lat: number): boolean {
    if (!map) return false
    const p = map.project([lng, lat])
    const i = insets.value
    const c = map.getContainer()
    return p.x >= i.left && p.x <= c.clientWidth - i.right && p.y >= i.top && p.y <= c.clientHeight - i.bottom
  }

  function fitBBox(b: BBox, maxZoom = 18, animate = true) {
    if (!map) return
    const degenerate = b.west === b.east && b.south === b.north
    if (degenerate) {
      map.flyTo({ center: [b.west, b.south], zoom: Math.max(map.getZoom(), Math.min(maxZoom, 18)), offset: visibleOffset(), animate })
      return
    }
    map.fitBounds(
      [
        [b.west, b.south],
        [b.east, b.north],
      ],
      { padding: fitPadding(), maxZoom, animate, duration: animate ? 900 : 0 },
    )
  }

  function flyTo(p: LngLat, z?: number) {
    map?.flyTo({ center: [p.lng, p.lat], zoom: z ?? Math.max(map.getZoom(), 17), offset: visibleOffset(), duration: 900 })
  }

  function easeTo(p: LngLat, opts: { zoom?: number; duration?: number } = {}) {
    map?.easeTo({ center: [p.lng, p.lat], zoom: opts.zoom, offset: visibleOffset(), duration: opts.duration ?? 500 })
  }

  function updateView(b: BBox, z: number) {
    bbox.value = b
    zoom.value = z
  }

  function refreshTiles() {
    tilesVersion.value = app.mirror?.openedAt ?? String(Date.now())
  }

  async function select(ref: OsmRef | null, opts: { fly?: boolean } = {}) {
    selected.value = ref
    if (!ref) {
      selectedFeature.value = null
      return
    }
    if (!sameRef(selectedFeature.value?.ref ?? null, ref)) selectedFeature.value = null
    selectedLoading.value = true
    try {
      const f = await api.object(ref)
      if (sameRef(selected.value, ref)) selectedFeature.value = f
    } catch (e) {
      if (sameRef(selected.value, ref)) app.fail(e, t('app.loadingObject', { ref: refString(ref) }))
    } finally {
      selectedLoading.value = false
    }
    if (opts.fly && selectedFeature.value) {
      const b = geometryBBox(asGeometry(selectedFeature.value.geometry))
      if (b) fitBBox(b, 18)
    }
  }

  function patchSelectedTags(tags: Record<string, string>) {
    if (selectedFeature.value) selectedFeature.value = { ...selectedFeature.value, tags }
  }

  function setPolygon(p: Polygon | null) {
    drawing.value = false
    polygon.value = p
    if (p) area.value = null
  }

  async function setArea(name: string | null, fly = true) {
    polygon.value = null
    if (!name) {
      area.value = null
      return
    }
    areaLoading.value = true
    try {
      const a = await api.area(name)
      area.value = a
      if (fly) fitBBox(a.bbox, 16)
    } catch (e) {
      app.fail(e, t('area.load', { name }))
    } finally {
      areaLoading.value = false
    }
  }

  return {
    ready, bbox, zoom, tilesVersion, refreshTiles,
    selected, selectedFeature, selectedLoading, highlighted,
    area, areaLoading, polygon, drawing, scopeName, setPolygon,
    imageryId, allImagery, activeImagery, setImagery, addImagery,
    attach, detach, instance, insets, setInsets, fitPadding, visibleOffset, visibleContains, fitBBox, flyTo, easeTo, updateView,
    hiddenFeatures, setFeatureVisible, setHiddenFeatures,
    select, patchSelectedTags, setArea,
  }
})
