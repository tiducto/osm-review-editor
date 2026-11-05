import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { gunzipSync } from 'node:zlib'
import { beforeAll, describe, expect, it } from 'vitest'
import { mapCssChecks, mapCssParse } from 'osm-review-shared'
import { IdAdapter } from '../id/adapter'
import { fixtureView, primeId, readRepoFile } from '@/test/idSetup'
import type { IdWay } from '../types'
import { GraphMapCssHost, halfWidth, parseLength } from './mapcssHost'

const here = import.meta.dirname
const expected = JSON.parse(readFileSync(resolve(here, 'fixtures/host-parity.json'), 'utf8')) as {
  keys: string
  ways: Record<string, { island: number; odd: string }>
}

let adapter: IdAdapter

beforeAll(async () => {
  await primeId()
  adapter = new IdAdapter(fixtureView)
  adapter.loadXml(gunzipSync(readFileSync(resolve(here, 'fixtures/katerinky-highways.osm.gz'))).toString('utf8'), [[17.885, 49.932], [17.915, 49.945]])
})

function host() {
  return new GraphMapCssHost({ graph: adapter.graph(), intersects: (b) => adapter.intersects(b) })
}

describe('GraphMapCssHost', () => {
  it('gives the same network_island_size and odd_segment as the GOL host on the fixture', () => {
    const ids = Object.keys(expected.ways)
    expect(ids.length).toBeGreaterThan(900)
    const h = host()
    const islands: Record<string, number> = {}
    const odd: Record<string, string> = {}
    for (const id of ids) {
      const w = adapter.graph().entity(id) as IdWay
      islands[id] = h.islandSize(w)
      odd[id] = h.oddSegment(w, expected.keys)
    }
    expect(islands).toEqual(Object.fromEntries(ids.map((id) => [id, expected.ways[id].island])))
    expect(odd).toEqual(Object.fromEntries(ids.map((id) => [id, expected.ways[id].odd])))
    expect(Object.values(odd).filter((v) => v).length).toBeGreaterThan(5)
  })

  it('runs the street-consistency check file like the server', () => {
    const sheet = mapCssParse(readRepoFile('user-rules.example/checks/street-consistency.validator.mapcss'), 'checks/street-consistency.validator.mapcss').stylesheet
    const h = host()
    const oddId = Object.entries(expected.ways).find(([, v]) => v.odd === 'lit')![0]
    const way = adapter.graph().entity(oddId)
    const checks = mapCssChecks(sheet, h.element(way), h) as { message: string; severity: string }[]
    expect(checks.map((c) => c.message)).toContain(`Differs from both neighbouring segments of ${way.tags.name}: lit`)
  })

  it('exposes parents, children, closed and lengths', () => {
    const h = host()
    const way = adapter.allEntities().find((e): e is IdWay => e.type === 'way' && e.nodes.length > 3 && !e.isClosed())!
    const el = h.element(way)
    expect(el.closed).toBe(false)
    expect(el.children().length).toBe(way.nodes.length)
    expect(el.children()[1].element.parents().map((p) => p.element.eid)).toContain(way.id)
    expect(h.call('waylength', el, []) as number).toBeGreaterThan(1)
    expect(h.pseudoClass('connection', el.children()[0].element)).toBeTypeOf('boolean')
  })

  it('parses widths like the server', () => {
    expect(parseLength('3,5 m')).toBe(3.5)
    expect(parseLength("10'")).toBeCloseTo(3.048)
    expect(parseLength('abc')).toBeNull()
    expect(halfWidth({ highway: 'primary', lanes: '2' })).toBe(3.25)
    expect(halfWidth({ highway: 'residential' })).toBe(2.75)
    expect(halfWidth({ highway: 'residential', width: '7' })).toBe(3.5)
    expect(halfWidth({ building: 'yes' })).toBeNull()
  })
})
