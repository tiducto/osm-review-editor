import { describe, expect, it } from 'vitest'
import { DATA_FADE_FROM, DATA_MIN_ZOOM, fadeIn, osmLayers, OSM_SRC } from './style'

describe('fadeIn', () => {
  it('fades constants and data expressions in below the data zoom', () => {
    expect(fadeIn(undefined)).toEqual(['interpolate', ['linear'], ['zoom'], DATA_FADE_FROM, 0, DATA_MIN_ZOOM, 1])
    expect(fadeIn(['get', 'o'])).toEqual(['interpolate', ['linear'], ['zoom'], DATA_FADE_FROM, 0, DATA_MIN_ZOOM, ['get', 'o']])
  })

  it('keeps zoom steps above the threshold as near-steps', () => {
    const out = fadeIn(['step', ['zoom'], 'a', 15, 'b', 17, 'c', 18, 'd']) as unknown[]
    expect(out.slice(0, 7)).toEqual(['interpolate', ['linear'], ['zoom'], DATA_FADE_FROM, 0, DATA_MIN_ZOOM, 'b'])
    expect(out.slice(7)).toEqual([16.999, 'b', 17, 'c', 17.999, 'c', 18, 'd'])
  })

  it('leaves zoom interpolations alone', () => {
    expect(fadeIn(['interpolate', ['linear'], ['zoom'], 10, 0, 12, 1])).toBeNull()
  })
})

describe('osmLayers', () => {
  it('draws no base OSM layer below the fade start', () => {
    const layers = osmLayers(50, new Map())
    const osm = layers.filter((l) => 'source' in l && l.source === OSM_SRC)
    expect(osm.length).toBeGreaterThan(10)
    for (const l of osm) expect(l.minzoom ?? 0, l.id).toBeGreaterThanOrEqual(DATA_FADE_FROM)
  })
})
