import type { ExpressionSpecification, LayerSpecification, StyleSpecification } from 'maplibre-gl'

export const BACKGROUND = '#f2f2f2'
const FONT = ['Noto Sans Bold']
const HALO = 'rgba(255,255,255,0.7)'
const empty = { type: 'geojson' as const, data: { type: 'FeatureCollection' as const, features: [] } }

export function lowZoomThreshold(lat: number): number {
  const a = Math.min(Math.abs(lat), 60) / 60
  return 18.5 - 1.5 * a - 1
}

function zoomStops(fn: (z: number) => ExpressionSpecification | number, from = 10, to = 22, step = 0.5): ExpressionSpecification {
  const out: unknown[] = ['interpolate', ['linear'], ['zoom']]
  for (let z = from; z <= to; z += step) out.push(z, fn(z))
  return out as ExpressionSpecification
}

export function dashExpr(dashes: Map<string, number[]>, prop: string): ExpressionSpecification {
  const m: unknown[] = ['match', ['get', prop]]
  for (const [k, v] of dashes) m.push(k, ['literal', v])
  m.push(['literal', [1, 0]])
  return m as ExpressionSpecification
}

function lineGroup(id: string, filter: ExpressionSpecification, lz: number, dashes: Map<string, number[]>): LayerSpecification[] {
  const layers: LayerSpecification[] = []
  for (const p of ['c', 's']) {
    layers.push({
      id: `${id}-${p === 'c' ? 'casing' : 'stroke'}`,
      type: 'line',
      source: 'lines',
      filter: ['all', filter, ['==', ['get', p + 'on'], true]],
      layout: {
        'line-cap': ['get', p + 'cap'] as ExpressionSpecification,
        'line-join': 'round',
        'line-sort-key': ['get', 'sort'],
      },
      paint: {
        'line-color': ['get', p + 'c'],
        'line-opacity': ['get', p + 'o'],
        'line-width': ['step', ['zoom'], ['get', p + 'wl'], lz, ['get', p + 'w']],
        'line-dasharray': ['step', ['zoom'], dashExpr(dashes, p + 'dl'), lz, dashExpr(dashes, p + 'd')] as unknown as ExpressionSpecification,
      },
    })
  }
  return layers
}

export const LINE_LAYER_IDS = ['lower', 'ground', 'upper'].flatMap((g) => [`${g}-casing`, `${g}-stroke`])

export function buildStyle(lat: number, dashes: Map<string, number[]>, imagery?: string): StyleSpecification {
  const lz = lowZoomThreshold(lat)
  const sources: StyleSpecification['sources'] = {
    areas: { ...empty },
    lines: { ...empty },
    oneways: { ...empty },
    vertices: { ...empty },
    points: { ...empty },
    arealabels: { ...empty },
  }
  const layers: LayerSpecification[] = [{ id: 'bg', type: 'background', paint: { 'background-color': BACKGROUND } }]
  if (imagery) {
    sources.imagery = { type: 'raster', tiles: [imagery], tileSize: 256, maxzoom: 20 }
    layers.push({ id: 'imagery', type: 'raster', source: 'imagery' })
  }
  layers.push(
    {
      id: 'area-fill',
      type: 'fill',
      source: 'areas',
      layout: { 'fill-sort-key': ['get', 'sort'] },
      paint: {
        'fill-color': ['get', 'fc'],
        'fill-opacity': zoomStops((z) => ['case', ['<=', ['get', 'r0'], 30 / 2 ** z], 1, 0]),
      },
    },
    {
      id: 'area-band',
      type: 'line',
      source: 'areas',
      layout: { 'line-sort-key': ['get', 'sort'], 'line-join': 'bevel' },
      paint: {
        'line-color': ['get', 'fc'],
        'line-width': 30,
        'line-offset': 15,
        'line-opacity': zoomStops((z) => ['case', ['<=', ['get', 'r0'], 30 / 2 ** z], 0, 1]),
      },
    },
    {
      id: 'area-stroke',
      type: 'line',
      source: 'areas',
      layout: { 'line-sort-key': ['case', ['get', 'building'], 1, 0] },
      paint: { 'line-color': ['get', 'sc'], 'line-width': 1 },
    },
    ...lineGroup('lower', ['<', ['get', 'sort'], 0], lz, dashes),
    ...lineGroup('ground', ['all', ['>=', ['get', 'sort'], 0], ['<', ['get', 'sort'], 100]], lz, dashes),
    ...lineGroup('upper', ['>=', ['get', 'sort'], 100], lz, dashes),
    {
      id: 'oneway',
      type: 'symbol',
      source: 'oneways',
      layout: {
        'symbol-placement': 'line',
        'symbol-spacing': 35,
        'icon-image': ['get', 'img'],
        'icon-size': 1.2,
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
        'icon-rotation-alignment': 'map',
        'symbol-sort-key': ['get', 'sort'],
      },
    },
    {
      id: 'line-label',
      type: 'symbol',
      source: 'lines',
      filter: ['!=', ['get', 'name'], ''],
      layout: {
        'symbol-placement': 'line-center',
        'text-field': ['get', 'name'],
        'text-font': FONT,
        'text-size': 12,
        'text-max-angle': 45,
        'text-padding': 4,
      },
      paint: { 'text-color': '#333', 'text-halo-color': HALO, 'text-halo-width': 2.5 },
    },
    {
      id: 'vertex',
      type: 'circle',
      source: 'vertices',
      layout: { 'circle-sort-key': ['-', 0, ['get', 'lat']] },
      paint: {
        'circle-radius': [
          'step', ['zoom'],
          ['+', 2, ['case', ['get', 'loose'], 1.5, 0]],
          16,
          ['case', ['get', 'hasIcon'], 7.5, ['+', 3, ['case', ['get', 'loose'], 1.5, 0]]],
        ],
        'circle-color': ['case', ['get', 'shared'], '#bbb', '#fff'],
        'circle-stroke-color': '#666',
        'circle-stroke-width': 1,
        'circle-pitch-alignment': 'map',
      },
    },
    {
      id: 'vertex-dot',
      type: 'circle',
      source: 'vertices',
      filter: ['==', ['get', 'tagged'], true],
      paint: {
        'circle-radius': ['step', ['zoom'], 1, 16, ['case', ['get', 'hasIcon'], 0, 1.5]],
        'circle-color': '#000',
      },
    },
    {
      id: 'vertex-icon',
      type: 'symbol',
      source: 'vertices',
      minzoom: 16,
      filter: ['==', ['get', 'hasIcon'], true],
      layout: { 'icon-image': ['get', 'icon'], 'icon-allow-overlap': true, 'icon-ignore-placement': true },
    },
    {
      id: 'area-icon',
      type: 'symbol',
      source: 'arealabels',
      filter: ['==', ['get', 'hasIcon'], true],
      layout: {
        'icon-image': ['get', 'icon'],
        'icon-allow-overlap': true,
        'icon-size': zoomStops((z) => ['case', ['>=', ['get', 'w0'], 20 / 2 ** z], 1, 0]),
        'symbol-sort-key': ['get', 'sort'],
      },
    },
    {
      id: 'area-label',
      type: 'symbol',
      source: 'arealabels',
      filter: ['!=', ['get', 'name'], ''],
      layout: {
        'text-field': ['get', 'name'],
        'text-font': FONT,
        'text-size': zoomStops((z) => ['case', ['>=', ['get', 'w0'], ['/', ['get', 'lw'], 2 ** z]], 12, 0]),
        'text-anchor': ['case', ['get', 'hasIcon'], 'top', 'center'],
        'text-offset': ['case', ['get', 'hasIcon'], ['literal', [0, 0.85]], ['literal', [0, 0]]],
        'text-padding': 2,
      },
      paint: {
        'text-color': '#333',
        'text-halo-color': HALO,
        'text-halo-width': 2.5,
      },
    },
    {
      id: 'addr',
      type: 'symbol',
      source: 'points',
      filter: ['==', ['get', 'addr'], true],
      layout: {
        'icon-image': 'addr-shield',
        'icon-text-fit': 'both',
        'icon-text-fit-padding': [2, 4, 1, 4],
        'text-field': ['get', 'hn'],
        'text-font': FONT,
        'text-size': 10,
        'icon-allow-overlap': true,
        'text-allow-overlap': true,
        'icon-ignore-placement': true,
        'text-ignore-placement': true,
        'symbol-sort-key': ['-', 0, ['get', 'lat']],
      },
      paint: { 'text-color': '#333' },
    },
    {
      id: 'pin',
      type: 'symbol',
      source: 'points',
      filter: ['==', ['get', 'addr'], false],
      layout: {
        'icon-image': ['get', 'pin'],
        'icon-anchor': 'top-left',
        'icon-offset': [-9.5, -25],
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
        'symbol-sort-key': ['-', 0, ['get', 'lat']],
        'symbol-z-order': 'source',
      },
    },
    {
      id: 'pin-label',
      type: 'symbol',
      source: 'points',
      filter: ['all', ['==', ['get', 'addr'], false], ['!=', ['get', 'name'], '']],
      layout: {
        'text-field': ['get', 'name'],
        'text-font': FONT,
        'text-size': 10,
        'text-anchor': 'left',
        'text-offset': [1.5, -1.2],
        'text-padding': 2,
      },
      paint: { 'text-color': '#333', 'text-halo-color': HALO, 'text-halo-width': 2.5 },
    },
  )
  return {
    version: 8,
    glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    sources,
    layers,
  }
}
