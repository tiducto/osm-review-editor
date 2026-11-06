import type { FeatureCollection } from 'geojson'
import type { ExpressionSpecification, GeoJSONSource, LayerSpecification, Map as MlMap } from 'maplibre-gl'
import type { ImagerySource } from '@/generated/models'
import { accent, contextColor, severityColor } from '@/lib/colors'
import { empty } from './data'
import { FONT, LAYER, OSM_ATTRIBUTION, SHADOW } from './osm/style'

export const SRC = {
  area: 'ore-area',
  context: 'ore-context',
  changes: 'ore-changes',
  before: 'ore-before',
  selection: 'ore-selection',
  issues: 'ore-issues',
  draw: 'ore-draw',
} as const

export type SourceId = (typeof SRC)[keyof typeof SRC]

const IMAGERY_SRC = 'ore-imagery'
const IMAGERY_LAYER = 'ore-imagery'

export const INTERACTIVE = {
  changes: ['ore-changes-fill', 'ore-changes-line', 'ore-changes-point'],
  context: ['ore-context-line', 'ore-context-point'],
  clusters: ['ore-issues-cluster'],
  issues: ['ore-issues-point'],
}

const isPoint: ExpressionSpecification = ['==', ['get', 'g'], 'point']
const isPolygon: ExpressionSpecification = ['==', ['get', 'g'], 'polygon']
const isLinear: ExpressionSpecification = ['in', ['get', 'g'], ['literal', ['line', 'polygon']]]
const isVertex: ExpressionSpecification = ['boolean', ['get', 'vertex'], false]

const OBJECT = '#334155'
const HALO = '#ffffff'

type Layer = LayerSpecification

function adder(map: MlMap, before?: string) {
  return (layer: Layer) => {
    if (!map.getLayer(layer.id)) map.addLayer(layer, before && map.getLayer(before) ? before : undefined)
  }
}

export function installLayers(map: MlMap): void {
  for (const id of Object.values(SRC)) {
    if (map.getSource(id)) continue
    if (id === SRC.issues) {
      map.addSource(id, {
        type: 'geojson',
        data: empty,
        cluster: true,
        clusterRadius: 44,
        clusterMaxZoom: 15,
        clusterProperties: { maxSev: ['max', ['get', 'sev']] },
      })
    } else {
      map.addSource(id, { type: 'geojson', data: empty })
    }
  }

  const under = adder(map, 'osm-line-shadow')
  under({
    id: 'ore-selection-shadow',
    type: 'line',
    source: SRC.selection,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': SHADOW, 'line-width': 16, 'line-opacity': 0.7 },
  })

  const add = adder(map)
  add({ id: 'ore-area-fill', type: 'fill', source: SRC.area, paint: { 'fill-color': '#475569', 'fill-opacity': 0.03 } })
  add({
    id: 'ore-area-line',
    type: 'line',
    source: SRC.area,
    paint: { 'line-color': '#475569', 'line-width': 1.5, 'line-dasharray': [3, 2], 'line-opacity': 0.7 },
  })

  add({
    id: 'ore-selection-point',
    type: 'circle',
    source: SRC.selection,
    filter: ['all', isPoint, ['!', isVertex]],
    paint: { 'circle-radius': 14, 'circle-translate': [0, -13], 'circle-color': SHADOW, 'circle-opacity': 0.7 },
  })
  add({
    id: 'ore-selection-vertex-shadow',
    type: 'circle',
    source: SRC.selection,
    filter: ['all', isPoint, isVertex],
    paint: { 'circle-radius': 9, 'circle-color': SHADOW, 'circle-opacity': 0.7 },
  })
  add({
    id: 'ore-selection-vertices',
    type: 'circle',
    source: SRC.selection,
    filter: ['==', ['get', 'g'], 'nodes'],
    paint: { 'circle-radius': 3.5, 'circle-color': '#fff', 'circle-stroke-color': '#666', 'circle-stroke-width': 1 },
  })

  add({
    id: 'ore-context-fill',
    type: 'fill',
    source: SRC.context,
    filter: isPolygon,
    paint: { 'fill-color': contextColor, 'fill-opacity': 0.18 },
  })
  add({
    id: 'ore-context-line',
    type: 'line',
    source: SRC.context,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': contextColor, 'line-width': 6, 'line-opacity': 0.45 },
  })
  add({
    id: 'ore-context-point',
    type: 'circle',
    source: SRC.context,
    filter: isPoint,
    paint: { 'circle-radius': 8, 'circle-color': contextColor, 'circle-opacity': 0.35, 'circle-stroke-color': contextColor, 'circle-stroke-width': 2 },
  })

  const current: ExpressionSpecification = ['boolean', ['get', 'current'], false]
  const doneOpacity: ExpressionSpecification = ['case', ['boolean', ['get', 'done'], false], 0.45, 1]
  add({
    id: 'ore-changes-fill',
    type: 'fill',
    source: SRC.changes,
    filter: isPolygon,
    paint: { 'fill-color': ['get', 'color'], 'fill-opacity': ['case', current, 0.28, 0.14] },
  })
  add({
    id: 'ore-changes-halo',
    type: 'line',
    source: SRC.changes,
    filter: ['all', isLinear, current],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': accent, 'line-width': 11, 'line-opacity': 0.28, 'line-blur': 1 },
  })
  add({
    id: 'ore-changes-line',
    type: 'line',
    source: SRC.changes,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': ['get', 'color'], 'line-width': ['case', current, 5, 3], 'line-opacity': doneOpacity },
  })
  add({
    id: 'ore-changes-point',
    type: 'circle',
    source: SRC.changes,
    filter: isPoint,
    paint: {
      'circle-radius': ['case', current, 9, 6],
      'circle-color': ['get', 'color'],
      'circle-opacity': doneOpacity,
      'circle-stroke-color': ['case', current, accent, HALO],
      'circle-stroke-width': ['case', current, 3, 2],
    },
  })

  const ghost: ExpressionSpecification = ['==', ['get', 'mode'], 'ghost']
  const solid: ExpressionSpecification = ['==', ['get', 'mode'], 'solid']
  add({
    id: 'ore-before-ghost',
    type: 'line',
    source: SRC.before,
    filter: ['all', isLinear, ghost],
    paint: { 'line-color': OBJECT, 'line-width': 2, 'line-dasharray': [2, 2], 'line-opacity': 0.8 },
  })
  add({
    id: 'ore-before-solid-fill',
    type: 'fill',
    source: SRC.before,
    filter: ['all', isPolygon, solid],
    paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.28 },
  })
  add({
    id: 'ore-before-solid',
    type: 'line',
    source: SRC.before,
    filter: ['all', isLinear, solid],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': ['get', 'color'], 'line-width': 5 },
  })
  add({
    id: 'ore-before-vertex',
    type: 'circle',
    source: SRC.before,
    filter: ['==', ['get', 'g'], 'vx'],
    paint: { 'circle-radius': 3.5, 'circle-color': '#ffffff', 'circle-stroke-color': ['get', 'color'], 'circle-stroke-width': 1.5 },
  })
  add({
    id: 'ore-before-point',
    type: 'circle',
    source: SRC.before,
    filter: isPoint,
    paint: {
      'circle-radius': 7,
      'circle-color': ['case', solid, ['get', 'color'], 'rgba(0,0,0,0)'],
      'circle-stroke-color': ['case', solid, accent, OBJECT],
      'circle-stroke-width': 2,
    },
  })

  const sevColor: ExpressionSpecification = [
    'match',
    ['get', 'maxSev'],
    2, severityColor.error,
    1, severityColor.warning,
    severityColor.info,
  ]
  add({
    id: 'ore-issues-cluster',
    type: 'circle',
    source: SRC.issues,
    filter: ['has', 'point_count'],
    paint: {
      'circle-color': '#ffffff',
      'circle-opacity': 0.92,
      'circle-radius': ['step', ['get', 'point_count'], 13, 10, 16, 50, 20, 200, 25, 1000, 30],
      'circle-stroke-color': sevColor,
      'circle-stroke-width': 3,
    },
  })
  add({
    id: 'ore-issues-count',
    type: 'symbol',
    source: SRC.issues,
    filter: ['has', 'point_count'],
    layout: {
      'text-field': ['get', 'point_count_abbreviated'],
      'text-font': FONT,
      'text-size': 12,
      'text-allow-overlap': true,
    },
    paint: { 'text-color': '#0f172a' },
  })
  add({
    id: 'ore-issues-point',
    type: 'circle',
    source: SRC.issues,
    filter: ['!', ['has', 'point_count']],
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 4, 16, 6.5, 19, 8],
      'circle-color': ['get', 'color'],
      'circle-stroke-color': HALO,
      'circle-stroke-width': 2,
    },
  })

  installDrawLayers(map)
}

export function installDrawLayers(map: MlMap): void {
  const add = adder(map)
  add({
    id: 'ore-draw-fill',
    type: 'fill',
    source: SRC.draw,
    filter: isPolygon,
    paint: { 'fill-color': accent, 'fill-opacity': 0.12 },
  })
  const preview: ExpressionSpecification = ['boolean', ['get', 'preview'], false]
  add({
    id: 'ore-draw-line',
    type: 'line',
    source: SRC.draw,
    filter: ['all', ['==', ['get', 'g'], 'line'], ['!', preview]],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': accent, 'line-width': 2.5 },
  })
  add({
    id: 'ore-draw-preview',
    type: 'line',
    source: SRC.draw,
    filter: ['all', ['==', ['get', 'g'], 'line'], preview],
    paint: { 'line-color': accent, 'line-width': 2, 'line-dasharray': [2, 2], 'line-opacity': 0.8 },
  })
  add({
    id: 'ore-draw-point',
    type: 'circle',
    source: SRC.draw,
    filter: isPoint,
    paint: {
      'circle-radius': ['case', ['boolean', ['get', 'first'], false], 7, 4.5],
      'circle-color': '#ffffff',
      'circle-stroke-color': accent,
      'circle-stroke-width': ['case', ['boolean', ['get', 'first'], false], 3, 2],
    },
  })
}

export function setData(map: MlMap, id: SourceId, data: FeatureCollection): void {
  const src = map.getSource(id) as GeoJSONSource | undefined
  src?.setData(data)
}

export function setVisible(map: MlMap, layerIds: string[], visible: boolean): void {
  for (const id of layerIds) {
    if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none')
  }
}

export const ISSUE_LAYERS = ['ore-issues-cluster', 'ore-issues-count', 'ore-issues-point']

const OVERLAY_PREFIX = 'ore-overlay-'

function rasterSource(src: ImagerySource) {
  return {
    type: 'raster' as const,
    tiles: [src.url],
    tileSize: 256,
    maxzoom: src.maxZoom || 22,
    attribution: /^©\s*OpenStreetMap contributors$/i.test(src.attribution.trim()) ? OSM_ATTRIBUTION : src.attribution,
  }
}

function overlayLayerIds(map: MlMap): string[] {
  return map.getStyle().layers.filter((l) => l.id.startsWith(OVERLAY_PREFIX)).map((l) => l.id)
}

export function imageryKey(src: ImagerySource | null): string {
  return src ? `${src.id}|${src.url}|${src.maxZoom}` : ''
}

export function setImagery(map: MlMap, src: ImagerySource | null): void {
  if (map.getLayer(IMAGERY_LAYER)) map.removeLayer(IMAGERY_LAYER)
  if (map.getSource(IMAGERY_SRC)) map.removeSource(IMAGERY_SRC)
  setVisible(map, [LAYER.background], !src)
  if (!src) return
  map.addSource(IMAGERY_SRC, rasterSource(src))
  const before = overlayLayerIds(map)[0] ?? LAYER.firstData
  map.addLayer({ id: IMAGERY_LAYER, type: 'raster', source: IMAGERY_SRC, paint: { 'raster-fade-duration': 150 } }, before)
}

export function setOverlays(map: MlMap, list: ImagerySource[]): void {
  const want = new Map(list.map((s) => [OVERLAY_PREFIX + s.id, s]))
  for (const id of overlayLayerIds(map)) {
    const s = want.get(id)
    const src = map.getSource(id) as { tiles?: string[] } | undefined
    if (s && src?.tiles?.[0] === s.url) continue
    map.removeLayer(id)
    if (map.getSource(id)) map.removeSource(id)
  }
  for (const [id, s] of want) {
    if (map.getLayer(id)) continue
    map.addSource(id, rasterSource(s))
    map.addLayer({ id, type: 'raster', source: id, paint: { 'raster-fade-duration': 150 } }, LAYER.firstData)
  }
}
