import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { gunzipSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { PbfReader } from 'pbf'
import { VectorTile } from '@mapbox/vector-tile'
import { fromVectorTileJs, type VectorTileFeatureLike, type VectorTileLayerLike } from '@maplibre/vt-pbf'
import { featureFilter, type FilterSpecification } from '@maplibre/maplibre-gl-style-spec'
import { mapCssMapLibreFilter, mapCssParse, mapCssRules, mapCssStyles } from 'osm-review-shared'
import { rawLayers, tagsOf, TILE_PROPS, transform, type Props } from './transform'
import type { PresetMatcher } from './presets'

const OPTIONS = { tagPrefix: '', typeProperty: TILE_PROPS.type, closedProperty: TILE_PROPS.closed }
const ROOT = fileURLToPath(new URL('../../../../', import.meta.url))
const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url))

const noPresets = { match: () => null } as unknown as PresetMatcher

interface Src {
  layer: string
  type: 1 | 2 | 3
  id: number
  props: Props
  geom: [number, number][][]
}

class SrcLayer implements VectorTileLayerLike {
  version = 2
  extent = 4096
  features: VectorTileFeatureLike[] = []
  constructor(public name: string) {}
  get length() {
    return this.features.length
  }
  feature(i: number) {
    return this.features[i]
  }
}

function encode(list: Src[]): ArrayBuffer {
  const layers: Record<string, SrcLayer> = {}
  for (const s of list) {
    const l = (layers[s.layer] ??= new SrcLayer(s.layer))
    const geometry = s.geom.map((ring) => ring.map(([x, y]) => ({ x, y })))
    l.features.push({ type: s.type, id: s.id, extent: 4096, properties: s.props, loadGeometry: () => geometry } as unknown as VectorTileFeatureLike)
  }
  const bytes = fromVectorTileJs({ layers })
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}

function decode(buf: ArrayBuffer | Uint8Array): VectorTile {
  return new VectorTile(new PbfReader(buf instanceof Uint8Array ? buf : new Uint8Array(buf)))
}

function reencode(layers: Record<string, VectorTileLayerLike>): VectorTile {
  const bytes = fromVectorTileJs({ layers })
  return decode(bytes)
}

interface Checked {
  rules: number
  features: number
  matches: number
}

function compare(tile: VectorTile, sheetSource: string, file: string, layerNames: string[]): Checked {
  const parsed = mapCssParse(sheetSource, file)
  expect(parsed.ok, JSON.stringify(parsed.diagnostics)).toBe(true)
  const sheet = parsed.stylesheet
  const filters: { index: number; test: ReturnType<typeof featureFilter> }[] = []
  const styleRules = (mapCssRules(sheet) as { index: number; hasStyle: boolean }[]).filter((r) => r.hasStyle).map((r) => r.index)
  for (const i of styleRules) {
    const f = mapCssMapLibreFilter(sheet, i, OPTIONS) as FilterSpecification | null
    if (f === null || f === undefined) continue
    filters.push({ index: i, test: featureFilter(f, 'filter') })
  }
  let features = 0
  let matches = 0
  for (const name of layerNames) {
    const layer = tile.layers[name]
    if (!layer) continue
    for (let i = 0; i < layer.length; i++) {
      const vf = layer.feature(i)
      const props = vf.properties as Props
      if (props[TILE_PROPS.id] === undefined) continue
      features++
      const element = {
        type: String(props[TILE_PROPS.type]),
        id: Number(props[TILE_PROPS.id]),
        tags: tagsOf(props),
        closed: props[TILE_PROPS.closed] === true,
      }
      const styled = mapCssStyles(sheet, element, null) as { rules: number[] }
      const feature = { type: vf.type, id: vf.id, properties: props, geometry: [] }
      for (const { index, test } of filters) {
        const selected = test.filter({ zoom: 16 }, feature as never)
        const evaluated = styled.rules.includes(index)
        if (selected) matches++
        expect(selected, `${file} rule ${index} on ${element.type} ${element.id} ${JSON.stringify(element.tags)} closed=${element.closed}`).toBe(evaluated)
      }
    }
  }
  return { rules: filters.length, features, matches }
}

const SYNTHETIC = `
way[highway][!surface] { color: red; }
way[highway=residential][sidewalk=both] { color: green; }
way[highway=footway][footway=sidewalk][!surface] { color: blue; }
way:closed[highway] { color: purple; }
way!:closed[highway] { color: orange; }
area[building] { fill-color: #f00; }
area[landuse=~/^(grass|meadow)$/] { fill-color: #0f0; }
node[highway=crossing][!crossing] { symbol-size: 6; }
node[amenity^=bench] { symbol-size: 4; }
*[name$=ova] { text: name; }
way[lanes>=2] { width: 4; }
way[maxspeed<50] { width: 2; }
way[oneway?] { color: black; }
way[oneway?!] { color: gray; }
relation[type=multipolygon] { fill-color: #00f; }
way[highway=~/^(primary|secondary)$/i] { set .major; }
way.major[!sidewalk] { color: #123456; }
way[name*=Ost] { color: #654321; }
way[surface!=asphalt][highway] { width: 3; }
`

const synthetic: Src[] = [
  { layer: 'line', type: 2, id: 4 * 10 + 1, props: { '@id': 10, '@type': 'way', highway: 'residential', sidewalk: 'both', name: 'Ostrožná' }, geom: [[[0, 0], [100, 100]]] },
  { layer: 'line', type: 2, id: 4 * 11 + 1, props: { '@id': 11, '@type': 'way', highway: 'footway', footway: 'sidewalk' }, geom: [[[0, 0], [50, 100]]] },
  { layer: 'line', type: 2, id: 4 * 12 + 1, props: { '@id': 12, '@type': 'way', '@closed': true, highway: 'service', surface: 'asphalt', oneway: 'yes' }, geom: [[[0, 0], [100, 0], [100, 100], [0, 0]]] },
  { layer: 'line', type: 2, id: 4 * 13 + 1, props: { '@id': 13, '@type': 'way', highway: 'primary', lanes: '2', maxspeed: '40', oneway: 'no', name: 'Opava' }, geom: [[[10, 10], [200, 10]]] },
  { layer: 'line', type: 2, id: 4 * 14 + 1, props: { '@id': 14, '@type': 'way', highway: 'Secondary', sidewalk: 'no', surface: 'gravel' }, geom: [[[10, 20], [200, 20]]] },
  { layer: 'area', type: 3, id: 4 * 20 + 1, props: { '@id': 20, '@type': 'way', building: 'yes', '@fz': 18 }, geom: [[[0, 0], [100, 0], [100, 100], [0, 100], [0, 0]]] },
  { layer: 'area', type: 3, id: 4 * 21 + 1, props: { '@id': 21, '@type': 'way', landuse: 'grass', highway: 'pedestrian', '@fz': 18 }, geom: [[[0, 0], [100, 0], [100, 100], [0, 100], [0, 0]]] },
  { layer: 'area', type: 3, id: 4 * 22 + 2, props: { '@id': 22, '@type': 'relation', type: 'multipolygon', landuse: 'meadow', '@fz': 18 }, geom: [[[0, 0], [100, 0], [100, 100], [0, 100], [0, 0]]] },
  { layer: 'point', type: 1, id: 4 * 30, props: { '@id': 30, '@type': 'node', amenity: 'bench', name: 'Opava' }, geom: [[[5, 5]]] },
  { layer: 'vertex', type: 1, id: 4 * 31, props: { '@id': 31, '@type': 'node', highway: 'crossing', '@junction': true, '@end': false, '@bonly': false }, geom: [[[6, 6]]] },
  { layer: 'vertex', type: 1, id: 4 * 32, props: { '@id': 32, '@type': 'node', highway: 'crossing', crossing: 'marked', '@junction': false, '@end': false, '@bonly': false }, geom: [[[7, 7]]] },
  { layer: 'vertex', type: 1, props: { '@junction': true, '@end': false, '@bonly': false }, id: 0, geom: [[[8, 8]]] },
]

const HIGHLIGHT_DIR = `${ROOT}user-rules.example/highlights/`
const exampleHighlights = readdirSync(HIGHLIGHT_DIR)
  .filter((n) => n.endsWith('.mapcss'))
  .map((n) => ({ file: `highlights/${n}`, source: readFileSync(HIGHLIGHT_DIR + n, 'utf8') }))

const LAYERS = ['area', 'line', 'point', 'vertex']

describe('tile property convention', () => {
  it('emits $type, $id and $closed on every OSM feature of the styled and raw tiles', () => {
    const src = decode(encode(synthetic))
    for (const tile of [reencode(transform(src, noPresets, 16).layers), reencode(rawLayers(src))]) {
      const line = tile.layers.line
      const byId = new Map<number, Props>()
      for (let i = 0; i < line.length; i++) {
        const p = line.feature(i).properties as Props
        byId.set(Number(p.$id), p)
      }
      expect(byId.get(12)).toMatchObject({ $type: 'way', $closed: true })
      expect(byId.get(10)).toMatchObject({ $type: 'way', $closed: false })
      expect(tile.layers.area.feature(0).properties).toMatchObject({ $type: 'way', $closed: true, $id: 20 })
      expect(tile.layers.vertex.feature(0).properties).toMatchObject({ $type: 'node', $closed: false, $id: 31 })
    }
  })

  it('compiled filters select exactly what the MapCSS engine evaluates on synthetic tile data', () => {
    const src = decode(encode(synthetic))
    for (const tile of [reencode(transform(src, noPresets, 16).layers), reencode(rawLayers(src))]) {
      const r = compare(tile, SYNTHETIC, 'synthetic.mapcss', LAYERS)
      expect(r.rules).toBeGreaterThan(14)
      expect(r.features).toBe(11)
      expect(r.matches).toBeGreaterThan(15)
      for (const h of exampleHighlights) compare(tile, h.source, h.file, LAYERS)
    }
  })

  const fixtures = existsSync(FIXTURES) ? readdirSync(FIXTURES).filter((n) => n.endsWith('.mvt.gz')) : []
  it.each(fixtures)('compiled filters agree with the engine on the real tile %s', (name) => {
    const z = Number(/-(\d+)-\d+-\d+\.mvt\.gz$/.exec(name)?.[1] ?? 16)
    const src = decode(gunzipSync(readFileSync(FIXTURES + name)))
    const styled = reencode(transform(src, noPresets, z).layers)
    const raw = reencode(rawLayers(src))
    let matches = 0
    for (const tile of [styled, raw]) {
      for (const h of [...exampleHighlights, { file: 'synthetic.mapcss', source: SYNTHETIC }]) {
        const r = compare(tile, h.source, h.file, LAYERS)
        expect(r.features).toBeGreaterThan(100)
        matches += r.matches
      }
    }
    expect(matches).toBeGreaterThan(50)
  })
})
