import type { ExpressionSpecification, FilterSpecification, LayerSpecification, StyleSpecification } from 'maplibre-gl'
import { OUT } from './transform'

export const OSM_SRC = 'ore-osm'
export const BACKGROUND = '#f2f2f2'
export const SHADOW = '#f6634f'
export const HIGHLIGHT = '#7092ff'
export const FONT = ['Bold', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial']
const HALO = 'rgba(255,255,255,0.75)'
const TEXT = '#333'

export const TILE_MIN_ZOOM = 9
export const TILE_MAX_ZOOM = 16
export const DETAIL_MIN_ZOOM = 15
export const MAX_BAND_ZOOM = 20

export const LAYER = {
  background: 'osm-bg',
  firstData: 'osm-fill',
  highlightSlot: 'ore-highlight-slot',
} as const

export function lowZoomThreshold(lat: number): number {
  const a = Math.min(Math.abs(lat), 60) / 60
  return 18.5 - 1.5 * a - 1
}

function zoomSteps(fn: (zoom: number) => ExpressionSpecification | number, from = 8, to = 22): ExpressionSpecification {
  const out: unknown[] = ['step', ['zoom'], fn(from)]
  for (let z = from + 1; z <= to; z++) out.push(z, fn(z))
  return out as ExpressionSpecification
}

export function dashMatch(dashes: Map<string, number[]>, prop: string): ExpressionSpecification {
  const m: unknown[] = ['match', ['get', prop]]
  for (const [k, v] of dashes) m.push(k, ['literal', v])
  m.push(['literal', [1, 0]])
  return m as ExpressionSpecification
}

export function dashExpr(dashes: Map<string, number[]>, prefix: string, lz: number): ExpressionSpecification {
  return ['step', ['zoom'], dashMatch(dashes, prefix + 'dl'), lz, dashMatch(dashes, prefix + 'd')] as unknown as ExpressionSpecification
}

export function parseDash(key: string): number[] {
  return key === 'solid' ? [1, 0] : key.split('_').map(Number)
}

const hovered: ExpressionSpecification = ['boolean', ['feature-state', 'hover'], false]
const highlighted: ExpressionSpecification = ['boolean', ['feature-state', 'hl'], false]
const shadowColor: ExpressionSpecification = ['case', hovered, SHADOW, highlighted, HIGHLIGHT, SHADOW]
const shadowOpacity: ExpressionSpecification = ['case', hovered, 0.4, highlighted, 0.6, 0]

export const LINE_GROUPS = ['lower', 'ground', 'upper'] as const
export const DASHED_LAYERS = LINE_GROUPS.flatMap((g) => [
  [`osm-${g}-casing`, '@c'],
  [`osm-${g}-stroke`, '@s'],
]) as [string, string][]

export const BAND_LAYERS = ['osm-band-low', ...Array.from({ length: MAX_BAND_ZOOM - DETAIL_MIN_ZOOM + 1 }, (_, i) => `osm-band-${DETAIL_MIN_ZOOM + i}`)].flatMap((id) => [id, `${id}-pattern`])
export const INTERACTIVE_OSM = ['osm-pin', 'osm-addr', 'osm-vertex', 'osm-point-dot', 'osm-line-hit', 'osm-area-hit', 'osm-fill', 'osm-fill-pattern', ...BAND_LAYERS]
export const AUTO_HIDE: Record<'buildings' | 'points' | 'addresses', { layers: string[]; hide: ExpressionSpecification }[]> = {
  buildings: [
    {
      layers: ['osm-fill', 'osm-fill-pattern', ...BAND_LAYERS, 'osm-area-shadow', 'osm-area-stroke', 'osm-area-hit', 'osm-area-icon', 'osm-area-label'],
      hide: ['!', ['boolean', ['get', '@bld'], false]],
    },
    { layers: ['osm-vertex', 'osm-vertex-dot', 'osm-vertex-icon', 'osm-vertex-shadow'], hide: ['!', ['boolean', ['get', '@bonly'], false]] },
  ],
  points: [{ layers: ['osm-pin', 'osm-pin-label', 'osm-pin-shadow', 'osm-point-dot'], hide: ['==', ['get', '@addr'], true] }],
  addresses: [{ layers: ['osm-addr', 'osm-addr-shadow'], hide: ['!=', ['get', '@addr'], true] }],
}

export const HOVER_LAYERS = [OUT.area, OUT.line, OUT.point, OUT.vertex]

function lineGroup(group: string, filter: ExpressionSpecification, lz: number, dashes: Map<string, number[]>): LayerSpecification[] {
  return (['@c', '@s'] as const).map((p) => ({
    id: `osm-${group}-${p === '@c' ? 'casing' : 'stroke'}`,
    type: 'line',
    source: OSM_SRC,
    'source-layer': OUT.line,
    filter: ['all', filter, ['==', ['get', p + 'on'], true]] as FilterSpecification,
    layout: {
      'line-cap': ['get', p + 'cap'] as ExpressionSpecification,
      'line-join': 'round',
      'line-sort-key': ['get', '@sort'],
    },
    paint: {
      'line-color': ['get', p + 'c'],
      'line-opacity': ['get', p + 'o'],
      'line-width': ['step', ['zoom'], ['get', p + 'wl'], lz, ['get', p + 'w']],
      'line-dasharray': dashExpr(dashes, p, lz) as unknown as number[],
    },
  }))
}

function bandLayers(): LayerSpecification[] {
  const out: LayerSpecification[] = []
  const add = (id: string, filter: ExpressionSpecification, min: number, max: number) => {
    out.push(
      {
        id: `${id}`,
        type: 'fill',
        source: OSM_SRC,
        'source-layer': OUT.band,
        minzoom: min,
        maxzoom: max,
        filter: ['all', filter, ['==', ['get', '@pat'], '']] as FilterSpecification,
        layout: { 'fill-sort-key': ['get', '@sort'] },
        paint: { 'fill-color': ['get', '@fc'], 'fill-antialias': false },
      },
      {
        id: `${id}-pattern`,
        type: 'fill',
        source: OSM_SRC,
        'source-layer': OUT.band,
        minzoom: min,
        maxzoom: max,
        filter: ['all', filter, ['!=', ['get', '@pat'], '']] as FilterSpecification,
        layout: { 'fill-sort-key': ['get', '@sort'] },
        paint: { 'fill-pattern': ['get', '@pat'], 'fill-antialias': false },
      },
    )
  }
  add('osm-band-low', ['<', ['get', '@bz'], DETAIL_MIN_ZOOM], 0, DETAIL_MIN_ZOOM)
  for (let bz = DETAIL_MIN_ZOOM; bz <= MAX_BAND_ZOOM; bz++) {
    add(`osm-band-${bz}`, ['==', ['get', '@bz'], bz], bz, bz === MAX_BAND_ZOOM ? 24 : bz + 1)
  }
  return out
}

export function osmLayers(lat: number, dashes: Map<string, number[]>): LayerSpecification[] {
  const lz = lowZoomThreshold(lat)
  const fullFill = zoomSteps((z) => ['case', ['>=', ['get', '@fz'], z], 1, 0])
  const detail = DETAIL_MIN_ZOOM
  return [
    {
      id: 'osm-fill',
      type: 'fill',
      source: OSM_SRC,
      'source-layer': OUT.area,
      filter: ['==', ['get', '@pat'], ''],
      layout: { 'fill-sort-key': ['get', '@sort'] },
      paint: { 'fill-color': ['get', '@fc'], 'fill-opacity': fullFill, 'fill-antialias': false },
    },
    {
      id: 'osm-fill-pattern',
      type: 'fill',
      source: OSM_SRC,
      'source-layer': OUT.area,
      filter: ['!=', ['get', '@pat'], ''],
      layout: { 'fill-sort-key': ['get', '@sort'] },
      paint: { 'fill-pattern': ['get', '@pat'], 'fill-opacity': fullFill, 'fill-antialias': false },
    },
    ...bandLayers(),
    {
      id: 'osm-area-shadow',
      type: 'line',
      source: OSM_SRC,
      'source-layer': OUT.area,
      layout: { 'line-join': 'round' },
      paint: { 'line-color': shadowColor, 'line-width': 16, 'line-opacity': shadowOpacity },
    },
    {
      id: 'osm-area-stroke',
      type: 'line',
      source: OSM_SRC,
      'source-layer': OUT.area,
      layout: { 'line-sort-key': ['case', ['get', '@bld'], 1, 0] },
      paint: { 'line-color': ['get', '@sc'], 'line-width': 1 },
    },
    {
      id: 'osm-area-hit',
      type: 'line',
      source: OSM_SRC,
      'source-layer': OUT.area,
      paint: { 'line-color': '#000', 'line-width': 10, 'line-opacity': 0 },
    },
    {
      id: 'osm-line-shadow',
      type: 'line',
      source: OSM_SRC,
      'source-layer': OUT.line,
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': shadowColor, 'line-width': ['step', ['zoom'], 12, lz, 16], 'line-opacity': shadowOpacity },
    },
    ...lineGroup('lower', ['<', ['get', '@sort'], 0], lz, dashes),
    ...lineGroup('ground', ['all', ['>=', ['get', '@sort'], 0], ['<', ['get', '@sort'], 100]], lz, dashes),
    ...lineGroup('upper', ['>=', ['get', '@sort'], 100], lz, dashes),
    {
      id: 'osm-line-hit',
      type: 'line',
      source: OSM_SRC,
      'source-layer': OUT.line,
      paint: { 'line-color': '#000', 'line-width': 12, 'line-opacity': 0 },
    },
    {
      id: 'osm-oneway',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.line,
      minzoom: detail,
      filter: ['!=', ['get', '@ow'], 0],
      layout: {
        'symbol-placement': 'line',
        'symbol-spacing': 35,
        'icon-image': ['get', '@owimg'],
        'icon-rotate': ['case', ['<', ['get', '@ow'], 0], 180, 0],
        'icon-size': 1.2,
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
        'icon-rotation-alignment': 'map',
        'symbol-sort-key': ['get', '@sort'],
      },
    },
    {
      id: 'osm-line-label',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.line,
      minzoom: 13,
      filter: ['!=', ['get', '@name'], ''],
      layout: {
        'symbol-placement': 'line-center',
        'text-field': ['get', '@name'],
        'text-font': FONT,
        'text-size': 12,
        'text-max-angle': 35,
        'text-padding': 2,
        'text-keep-upright': true,
        'symbol-sort-key': ['-', 0, ['get', '@sort']],
      },
      paint: { 'text-color': TEXT, 'text-halo-color': HALO, 'text-halo-width': 2 },
    },
    {
      id: 'osm-vertex-shadow',
      type: 'circle',
      source: OSM_SRC,
      'source-layer': OUT.vertex,
      minzoom: detail,
      paint: { 'circle-radius': ['case', ['get', '@hasIcon'], 11, 7], 'circle-color': shadowColor, 'circle-opacity': ['case', hovered, 0.5, highlighted, 0.6, 0] },
    },
    {
      id: 'osm-vertex',
      type: 'circle',
      source: OSM_SRC,
      'source-layer': OUT.vertex,
      minzoom: detail,
      layout: { 'circle-sort-key': ['case', ['get', '@tagged'], 1, 0] },
      paint: {
        'circle-radius': [
          'step', ['zoom'],
          ['+', 2, ['case', ['get', '@loose'], 1.5, 0]],
          16,
          ['case', ['get', '@hasIcon'], 7.5, ['+', 3, ['case', ['get', '@loose'], 1.5, 0]]],
        ],
        'circle-color': ['case', ['get', '@shared'], '#bbb', '#fff'],
        'circle-stroke-color': '#666',
        'circle-stroke-width': 1,
        'circle-pitch-alignment': 'map',
      },
    },
    {
      id: 'osm-vertex-dot',
      type: 'circle',
      source: OSM_SRC,
      'source-layer': OUT.vertex,
      minzoom: detail,
      filter: ['all', ['==', ['get', '@tagged'], true], ['==', ['get', '@hasIcon'], false]],
      paint: { 'circle-radius': ['step', ['zoom'], 1, 16, 1.5], 'circle-color': '#000' },
    },
    {
      id: 'osm-vertex-icon',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.vertex,
      minzoom: 16,
      filter: ['==', ['get', '@hasIcon'], true],
      layout: { 'icon-image': ['get', '@icon'], 'icon-allow-overlap': true },
    },
    {
      id: 'osm-area-icon',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.label,
      filter: ['==', ['get', '@hasIcon'], true],
      layout: {
        'icon-image': ['get', '@icon'],
        'icon-allow-overlap': true,
        'icon-size': zoomSteps((z) => ['case', ['>=', ['get', '@w0'], 20 / 2 ** z], 1, 0]),
        'symbol-sort-key': ['get', '@sort'],
      },
    },
    {
      id: 'osm-area-label',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.label,
      minzoom: 13,
      filter: ['!=', ['get', '@name'], ''],
      layout: {
        'text-field': ['get', '@name'],
        'text-font': FONT,
        'text-size': zoomSteps((z) => ['case', ['>=', ['get', '@w0'], ['/', ['get', '@lw'], 2 ** z]], 12, 0]),
        'text-anchor': ['case', ['get', '@hasIcon'], 'top', 'center'],
        'text-offset': ['case', ['get', '@hasIcon'], ['literal', [0, 0.85]], ['literal', [0, 0]]],
        'text-padding': 6,
        'symbol-sort-key': ['get', '@sort'],
      },
      paint: { 'text-color': TEXT, 'text-halo-color': HALO, 'text-halo-width': 2 },
    },
    {
      id: 'osm-point-dot',
      type: 'circle',
      source: OSM_SRC,
      'source-layer': OUT.point,
      maxzoom: detail,
      paint: { 'circle-radius': 2.5, 'circle-color': '#fff', 'circle-stroke-color': '#444', 'circle-stroke-width': 1 },
    },
    {
      id: 'osm-pin-shadow',
      type: 'circle',
      source: OSM_SRC,
      'source-layer': OUT.point,
      minzoom: detail,
      filter: ['==', ['get', '@addr'], false],
      paint: { 'circle-radius': 14, 'circle-translate': [0, -13], 'circle-color': shadowColor, 'circle-opacity': ['case', hovered, 0.5, highlighted, 0.6, 0] },
    },
    {
      id: 'osm-addr-shadow',
      type: 'circle',
      source: OSM_SRC,
      'source-layer': OUT.point,
      minzoom: detail,
      filter: ['==', ['get', '@addr'], true],
      paint: { 'circle-radius': 10, 'circle-color': shadowColor, 'circle-opacity': ['case', hovered, 0.5, highlighted, 0.6, 0] },
    },
    {
      id: 'osm-addr',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.point,
      minzoom: detail,
      filter: ['==', ['get', '@addr'], true],
      layout: {
        'icon-image': 'addr-shield',
        'icon-text-fit': 'both',
        'icon-text-fit-padding': [2, 4, 1, 4],
        'text-field': ['get', '@hn'],
        'text-font': FONT,
        'text-size': 10,
        'icon-allow-overlap': true,
        'text-allow-overlap': true,
        'icon-ignore-placement': true,
        'text-ignore-placement': true,
      },
      paint: { 'text-color': TEXT },
    },
    {
      id: 'osm-pin',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.point,
      minzoom: detail,
      filter: ['==', ['get', '@addr'], false],
      layout: {
        'icon-image': ['get', '@pin'],
        'icon-anchor': 'top-left',
        'icon-offset': [-9.5, -25],
        'icon-allow-overlap': true,
        'symbol-z-order': 'viewport-y',
      },
    },
    {
      id: 'osm-pin-label',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.point,
      minzoom: detail,
      filter: ['all', ['==', ['get', '@addr'], false], ['!=', ['get', '@name'], '']],
      layout: {
        'text-field': ['get', '@name'],
        'text-font': FONT,
        'text-size': 10,
        'text-anchor': 'left',
        'text-offset': [1.1, -1.3],
        'text-padding': 4,
        'text-max-width': 12,
      },
      paint: { 'text-color': TEXT, 'text-halo-color': HALO, 'text-halo-width': 2 },
    },
    {
      id: 'osm-place',
      type: 'symbol',
      source: OSM_SRC,
      'source-layer': OUT.place,
      filter: ['!=', ['get', '@name'], ''],
      layout: {
        'text-field': ['get', '@name'],
        'text-font': FONT,
        'text-size': ['match', ['get', '@rank'], 4, 16, 3, 14, 12],
        'symbol-sort-key': ['-', 0, ['get', '@rank']],
        'text-padding': 8,
      },
      paint: { 'text-color': '#555', 'text-halo-color': HALO, 'text-halo-width': 2 },
    },
    {
      id: LAYER.highlightSlot,
      type: 'background',
      layout: { visibility: 'none' },
      paint: { 'background-opacity': 0 },
    },
  ]
}

export function baseStyle(tiles: string, lat: number, dashes: Map<string, number[]>): StyleSpecification {
  return {
    version: 8,
    sources: {
      [OSM_SRC]: {
        type: 'vector',
        tiles: [tiles],
        minzoom: TILE_MIN_ZOOM,
        maxzoom: TILE_MAX_ZOOM,
        attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
      },
    },
    layers: [{ id: LAYER.background, type: 'background', paint: { 'background-color': BACKGROUND } }, ...osmLayers(lat, dashes)],
  }
}
