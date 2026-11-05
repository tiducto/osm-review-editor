import { beforeAll, describe, expect, it } from 'vitest'
import { fixtureAdapter, primeId } from '@/test/idSetup'
import { lassoNodes } from './lasso'
import { bandPolygons, BAND_PX } from './band'
import type { IdNode, IdWay, LngLatTuple } from './types'

beforeAll(primeId)

describe('lasso', () => {
  it('selects the loaded nodes inside the lasso and nothing outside', () => {
    const a = fixtureAdapter()
    const g = a.graph()
    const building = a
      .allEntities()
      .find((e): e is IdWay => e.type === 'way' && !!e.tags.building && e.isClosed() && e.nodes.length >= 5 && e.nodes.every((n) => a.isLoaded((g.entity(n) as IdNode).loc)))!
    const nodes = building.nodes.map((id) => g.entity(id) as IdNode)
    const xs = nodes.map((n) => n.loc[0])
    const ys = nodes.map((n) => n.loc[1])
    const pad = 0.000005
    const lasso: LngLatTuple[] = [
      [Math.min(...xs) - pad, Math.min(...ys) - pad],
      [Math.max(...xs) + pad, Math.min(...ys) - pad],
      [Math.max(...xs) + pad, Math.max(...ys) + pad],
      [Math.min(...xs) - pad, Math.max(...ys) + pad],
    ]
    const ids = lassoNodes(a, lasso)
    for (const n of new Set(building.nodes)) expect(ids).toContain(n)
    for (const id of ids) {
      const loc = (g.entity(id) as IdNode).loc
      expect(loc[0]).toBeGreaterThanOrEqual(lasso[0][0])
      expect(loc[0]).toBeLessThanOrEqual(lasso[1][0])
    }
    expect(lassoNodes(a, lasso.slice(0, 2))).toEqual([])
  })

  it('ignores nodes outside the loaded area', () => {
    const a = fixtureAdapter()
    const far: LngLatTuple[] = [
      [17.85, 49.93],
      [17.877, 49.93],
      [17.877, 49.95],
      [17.85, 49.95],
    ]
    const g = a.graph()
    const ids = lassoNodes(a, far)
    expect(ids.every((id) => a.isLoaded((g.entity(id) as IdNode).loc))).toBe(true)
  })
})

describe('band', () => {
  function square(sizePx: number, zoom: number) {
    const scale = 512 * 2 ** zoom
    const lon = (x: number) => (x / scale) * 360 - 180
    const lat = (y: number) => (180 / Math.PI) * Math.atan(Math.sinh(Math.PI - (2 * Math.PI * y) / scale))
    const x0 = scale * 0.549
    const y0 = scale * 0.347
    return [
      [
        [lon(x0), lat(y0)],
        [lon(x0 + sizePx), lat(y0)],
        [lon(x0 + sizePx), lat(y0 + sizePx)],
        [lon(x0), lat(y0 + sizePx)],
        [lon(x0), lat(y0)],
      ],
    ]
  }

  it('is the polygon minus its 30 px inset, as one polygon with a hole', () => {
    const band = bandPolygons([square(200, 18)], 18)
    expect(band).toHaveLength(1)
    expect(band[0]).toHaveLength(2)
    const inner = band[0][1]
    const scale = 512 * 2 ** 18
    const xs = inner.map((p) => ((p[0] + 180) / 360) * scale)
    expect(Math.max(...xs) - Math.min(...xs)).toBeCloseTo(200 - 2 * BAND_PX, 0)
  })

  it('fills small polygons completely and keeps concave corners clean', () => {
    expect(bandPolygons([square(40, 18)], 18)[0]).toHaveLength(1)
    const scale = 512 * 2 ** 18
    const lon = (x: number) => (x / scale) * 360 - 180
    const lat = (y: number) => (180 / Math.PI) * Math.atan(Math.sinh(Math.PI - (2 * Math.PI * y) / scale))
    const x0 = scale * 0.549
    const y0 = scale * 0.347
    const l = [
      [0, 0],
      [300, 0],
      [300, 100],
      [100, 100],
      [100, 300],
      [0, 300],
      [0, 0],
    ].map(([x, y]) => [lon(x0 + x), lat(y0 + y)])
    const band = bandPolygons([[l]], 18)
    const pts = band.flat(2).map((p) => [((p[0] + 180) / 360) * scale - x0, ((Math.PI - Math.asinh(Math.tan((p[1] * Math.PI) / 180))) * scale) / (2 * Math.PI) - y0])
    expect(pts.length).toBeGreaterThan(8)
    for (const [x, y] of pts) {
      expect(x).toBeGreaterThan(-0.5)
      expect(x).toBeLessThan(300.5)
      expect(y).toBeGreaterThan(-0.5)
      expect(y).toBeLessThan(300.5)
      expect(x > 100.5 && y > 100.5).toBe(false)
    }
  })
})
