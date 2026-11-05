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
import { useLayoutStore } from './layout'

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

  function cameraPadding(extra = 64): FitPadding {
    const inset = useLayoutStore().insets
    return { top: extra, bottom: extra + inset.bottom, left: extra + inset.left, right: extra + inset.right }
  }

  function centerOffset(): [number, number] {
    const inset = useLayoutStore().insets
    return [(inset.left - inset.right) / 2, -inset.bottom / 2]
  }

  function visibleContains(lng: number, lat: number): boolean {
    if (!map) return false
    const p = map.project([lng, lat])
    const inset = useLayoutStore().insets
    const c = map.getContainer()
    return p.x >= inset.left && p.x <= c.clientWidth - inset.right && p.y >= 0 && p.y <= c.clientHeight - inset.bottom
  }

  function fitBBox(b: BBox, maxZoom = 18, animate = true) {
    if (!map) return
    const degenerate = b.west === b.east && b.south === b.north
    if (degenerate) {
      map.flyTo({ center: [b.west, b.south], zoom: Math.max(map.getZoom(), Math.min(maxZoom, 18)), offset: centerOffset(), animate })
      return
    }
    map.fitBounds(
      [
        [b.west, b.south],
        [b.east, b.north],
      ],
      { padding: cameraPadding(), maxZoom, animate, duration: animate ? 900 : 0 },
    )
  }

  function flyTo(p: LngLat, z?: number) {
    map?.flyTo({ center: [p.lng, p.lat], zoom: z ?? Math.max(map.getZoom(), 17), offset: centerOffset(), duration: 900 })
  }

  function easeTo(p: LngLat, opts: { zoom?: number; duration?: number } = {}) {
    map?.easeTo({ center: [p.lng, p.lat], zoom: opts.zoom, offset: centerOffset(), duration: opts.duration ?? 500 })
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
    attach, detach, instance, cameraPadding, centerOffset, visibleContains, fitBBox, flyTo, easeTo, updateView,
    select, patchSelectedTags, setArea,
  }
})
