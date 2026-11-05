import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import type { Map as MlMap } from 'maplibre-gl'
import type { Polygon } from 'geojson'
import type { Area, BBox, ImagerySource, LngLat, MapFeature, OsmRef } from '@/generated/models'
import { api, isAbort } from '@/api/client'
import { useAppStore } from './app'
import { refString, sameRef } from '@/lib/osm'
import { asGeometry, geometryBBox } from '@/lib/geo'

export const FEATURE_MIN_ZOOM = 16

const IMAGERY_KEY = 'ore.imagery'

let map: MlMap | null = null

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

  const features = shallowRef<MapFeature[]>([])
  const featuresTruncated = ref(false)
  const featuresLoading = ref(false)
  let featuresAbort: AbortController | null = null

  const selected = ref<OsmRef | null>(null)
  const selectedFeature = shallowRef<MapFeature | null>(null)
  const originTags = shallowRef<Record<string, string> | null>(null)
  const selectedLoading = ref(false)
  const highlighted = ref<OsmRef[]>([])

  const area = shallowRef<Area | null>(null)
  const areaLoading = ref(false)
  const polygon = shallowRef<Polygon | null>(null)
  const drawing = ref(false)
  const scopeName = computed(() => area.value?.name ?? (polygon.value ? 'Drawn polygon' : null))

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

  const padding: FitPadding = { top: 64, bottom: 64, left: 64, right: 64 }
  function setBottomInset(px: number) {
    padding.bottom = Math.max(64, Math.round(px) + 24)
  }

  function fitBBox(b: BBox, maxZoom = 18, animate = true) {
    if (!map) return
    const degenerate = b.west === b.east && b.south === b.north
    if (degenerate) {
      map.flyTo({ center: [b.west, b.south], zoom: Math.max(map.getZoom(), Math.min(maxZoom, 18)), animate })
      return
    }
    map.fitBounds(
      [
        [b.west, b.south],
        [b.east, b.north],
      ],
      { padding, maxZoom, animate, duration: animate ? 900 : 0 },
    )
  }

  function flyTo(p: LngLat, z?: number) {
    map?.flyTo({ center: [p.lng, p.lat], zoom: z ?? Math.max(map.getZoom(), 17), duration: 900 })
  }

  function updateView(b: BBox, z: number) {
    bbox.value = b
    zoom.value = z
  }

  async function loadFeatures() {
    featuresAbort?.abort()
    const b = bbox.value
    if (!b || zoom.value < FEATURE_MIN_ZOOM) {
      if (features.value.length) features.value = []
      featuresTruncated.value = false
      featuresLoading.value = false
      return
    }
    const ctrl = new AbortController()
    featuresAbort = ctrl
    featuresLoading.value = true
    try {
      const fc = await api.features(b, 5000, ctrl.signal)
      features.value = fc.features
      featuresTruncated.value = fc.truncated
    } catch (e) {
      if (!isAbort(e)) console.warn('features', e)
    } finally {
      if (featuresAbort === ctrl) featuresLoading.value = false
    }
  }

  async function select(ref: OsmRef | null, opts: { fly?: boolean } = {}) {
    selected.value = ref
    originTags.value = null
    if (!ref) {
      selectedFeature.value = null
      return
    }
    const local = features.value.find((f) => sameRef(f.ref, ref))
    selectedFeature.value = local ?? null
    originTags.value = local?.tags ?? null
    if (!local) {
      selectedLoading.value = true
      try {
        const f = await api.object(ref)
        if (sameRef(selected.value, ref)) {
          selectedFeature.value = f
          originTags.value = f.tags
        }
      } catch (e) {
        if (sameRef(selected.value, ref)) app.fail(e, `Loading ${refString(ref)}`)
      } finally {
        selectedLoading.value = false
      }
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
      app.fail(e, `Area ${name}`)
    } finally {
      areaLoading.value = false
    }
  }

  return {
    ready, bbox, zoom, features, featuresTruncated, featuresLoading,
    selected, selectedFeature, originTags, selectedLoading, highlighted,
    area, areaLoading, polygon, drawing, scopeName, setPolygon,
    imageryId, allImagery, activeImagery, setImagery, addImagery,
    attach, detach, instance, setBottomInset, fitBBox, flyTo, updateView, loadFeatures,
    select, patchSelectedTags, setArea,
  }
})
