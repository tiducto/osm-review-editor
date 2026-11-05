import type { FeatureCollection } from 'geojson'
import type { ExpressionSpecification, GeoJSONSource, Map as MlMap } from 'maplibre-gl'
import type { ImagerySource } from '@/generated/models'
import { accent, contextColor, severityColor } from '@/lib/colors'
import { empty } from './data'

export const SRC = {
  area: 'ore-area',
  osm: 'ore-osm',
  context: 'ore-context',
  changes: 'ore-changes',
  before: 'ore-before',
  highlight: 'ore-highlight',
  selection: 'ore-selection',
  issues: 'ore-issues',
} as const

export type SourceId = (typeof SRC)[keyof typeof SRC]

const IMAGERY_SRC = 'ore-imagery'
const IMAGERY_LAYER = 'ore-imagery'
const FIRST_LAYER = 'ore-area-fill'

export const INTERACTIVE = {
  osm: ['ore-osm-fill', 'ore-osm-line', 'ore-osm-point'],
  changes: ['ore-changes-fill', 'ore-changes-line', 'ore-changes-point'],
  context: ['ore-context-line', 'ore-context-point'],
  clusters: ['ore-issues-cluster'],
  issues: ['ore-issues-point'],
}

const isPoint: ExpressionSpecification = ['==', ['get', 'g'], 'point']
const isPolygon: ExpressionSpecification = ['==', ['get', 'g'], 'polygon']
const isLinear: ExpressionSpecification = ['in', ['get', 'g'], ['literal', ['line', 'polygon']]]

function theme(dark: boolean) {
  return {
    object: dark ? '#a5b4c8' : '#334155',
    objectFill: dark ? '#94a3b8' : '#64748b',
    pointFill: dark ? '#1e293b' : '#ffffff',
    halo: dark ? '#0b1120' : '#ffffff',
    area: dark ? '#94a3b8' : '#475569',
    text: dark ? '#0b1120' : '#ffffff',
  }
}

export function installLayers(map: MlMap, dark: boolean): void {
  const t = theme(dark)
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

  const add = (layer: Parameters<MlMap['addLayer']>[0]) => {
    if (!map.getLayer(layer.id)) map.addLayer(layer)
  }

  add({ id: 'ore-area-fill', type: 'fill', source: SRC.area, paint: { 'fill-color': t.area, 'fill-opacity': 0.03 } })
  add({
    id: 'ore-area-line',
    type: 'line',
    source: SRC.area,
    paint: { 'line-color': t.area, 'line-width': 1.5, 'line-dasharray': [3, 2], 'line-opacity': 0.7 },
  })

  add({
    id: 'ore-osm-fill',
    type: 'fill',
    source: SRC.osm,
    filter: isPolygon,
    paint: { 'fill-color': t.objectFill, 'fill-opacity': 0.1 },
  })
  add({
    id: 'ore-osm-line',
    type: 'line',
    source: SRC.osm,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': t.object,
      'line-width': ['interpolate', ['linear'], ['zoom'], 16, 1.2, 19, 2.6],
      'line-opacity': 0.75,
    },
  })
  add({
    id: 'ore-osm-point',
    type: 'circle',
    source: SRC.osm,
    filter: isPoint,
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 16, 3, 19, 5.5],
      'circle-color': t.pointFill,
      'circle-stroke-color': t.object,
      'circle-stroke-width': 1.5,
    },
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
      'circle-stroke-color': ['case', current, accent, t.halo],
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
    paint: { 'line-color': t.object, 'line-width': 2, 'line-dasharray': [2, 2], 'line-opacity': 0.8 },
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
    id: 'ore-before-point',
    type: 'circle',
    source: SRC.before,
    filter: isPoint,
    paint: {
      'circle-radius': 7,
      'circle-color': ['case', solid, ['get', 'color'], 'rgba(0,0,0,0)'],
      'circle-stroke-color': ['case', solid, accent, t.object],
      'circle-stroke-width': 2,
    },
  })

  add({
    id: 'ore-highlight-line',
    type: 'line',
    source: SRC.highlight,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': accent, 'line-width': 4, 'line-opacity': 0.55 },
  })
  add({
    id: 'ore-highlight-point',
    type: 'circle',
    source: SRC.highlight,
    filter: isPoint,
    paint: { 'circle-radius': 7, 'circle-color': accent, 'circle-opacity': 0.35, 'circle-stroke-color': accent, 'circle-stroke-width': 2 },
  })

  add({
    id: 'ore-selection-fill',
    type: 'fill',
    source: SRC.selection,
    filter: isPolygon,
    paint: { 'fill-color': accent, 'fill-opacity': 0.16 },
  })
  add({
    id: 'ore-selection-glow',
    type: 'line',
    source: SRC.selection,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': accent, 'line-width': 10, 'line-opacity': 0.25, 'line-blur': 2 },
  })
  add({
    id: 'ore-selection-line',
    type: 'line',
    source: SRC.selection,
    filter: isLinear,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': accent, 'line-width': 3.5 },
  })
  add({
    id: 'ore-selection-point',
    type: 'circle',
    source: SRC.selection,
    filter: isPoint,
    paint: { 'circle-radius': 8, 'circle-color': t.pointFill, 'circle-stroke-color': accent, 'circle-stroke-width': 3.5 },
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
      'circle-color': dark ? '#1e293b' : '#ffffff',
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
      'text-font': ['Noto Sans Bold'],
      'text-size': 12,
      'text-allow-overlap': true,
    },
    paint: { 'text-color': dark ? '#e2e8f0' : '#0f172a' },
  })
  add({
    id: 'ore-issues-point',
    type: 'circle',
    source: SRC.issues,
    filter: ['!', ['has', 'point_count']],
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 4, 16, 6.5, 19, 8],
      'circle-color': ['get', 'color'],
      'circle-stroke-color': t.halo,
      'circle-stroke-width': 2,
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
export const OSM_LAYERS = ['ore-osm-fill', 'ore-osm-line', 'ore-osm-point']

export function setImagery(map: MlMap, src: ImagerySource | null): void {
  if (map.getLayer(IMAGERY_LAYER)) map.removeLayer(IMAGERY_LAYER)
  if (map.getSource(IMAGERY_SRC)) map.removeSource(IMAGERY_SRC)
  if (!src) return
  map.addSource(IMAGERY_SRC, {
    type: 'raster',
    tiles: [src.url],
    tileSize: 256,
    maxzoom: src.maxZoom || 22,
    attribution: src.attribution,
  })
  map.addLayer({ id: IMAGERY_LAYER, type: 'raster', source: IMAGERY_SRC, paint: { 'raster-fade-duration': 150 } }, map.getLayer(FIRST_LAYER) ? FIRST_LAYER : undefined)
}
