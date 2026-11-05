import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { PresetCatalog, type RawSchema, type UserPresetData } from './catalog'

const dist = resolve(import.meta.dirname, '../../../node_modules/@openstreetmap/id-tagging-schema/dist') + '/'
const json = (f: string) => JSON.parse(readFileSync(dist + f, 'utf8'))

export const RAW: RawSchema = {
  presets: json('presets.min.json'),
  fields: json('fields.min.json'),
  categories: json('preset_categories.min.json'),
  defaults: json('preset_defaults.min.json'),
  translations: { en: json('translations/en.min.json'), cs: json('translations/cs.min.json') },
}

const sidewalkCz: UserPresetData = {
  id: 'custom/sidewalk-cz',
  name: 'Chodník',
  names: { cs: 'Chodník', en: 'Chodník' },
  geometry: ['line'],
  tags: { footway: 'sidewalk' },
  addTags: { highway: 'footway', footway: 'sidewalk', surface: 'paving_stones' },
  removeTags: { highway: 'footway', footway: 'sidewalk', surface: 'paving_stones' },
  fields: ['name', 'surface', 'width', 'lit'],
  moreFields: [],
  icon: 'temaki-pedestrian',
  terms: [],
  aliases: [],
  matchScore: 1,
  searchable: true,
  extends: 'highway/footway/sidewalk',
  apply: 'merge',
  file: 'presets/sidewalk-cz.json',
}

describe('PresetCatalog', () => {
  const en = new PresetCatalog(RAW, [], 'en')
  const cs = new PresetCatalog(RAW, [sidewalkCz], 'cs')

  it('matches like iD, with localized names', () => {
    expect(en.match({ highway: 'residential', name: 'Hlavní' }, 'line').id).toBe('highway/residential')
    expect(en.match({ highway: 'footway', footway: 'crossing' }, 'line').id).toBe('highway/footway/crossing')
    expect(en.match({ highway: 'crossing', crossing: 'uncontrolled' }, 'vertex').id).toBe('highway/crossing/uncontrolled')
    expect(en.match({}, 'line').id).toBe('line')
    expect(en.match({ 'addr:housenumber': '12' }, 'point').id).toBe('address')
    expect(cs.match({ highway: 'footway' }, 'line').name).toBe('Chodník / stezka pro pěší')
    expect(en.match({ highway: 'footway' }, 'line').name).toBe('Foot Path')
  })

  it('prefers a user preset over the iD preset it overlays', () => {
    expect(cs.match({ highway: 'footway', footway: 'sidewalk' }, 'line').id).toBe('custom/sidewalk-cz')
    expect(en.match({ highway: 'footway', footway: 'sidewalk' }, 'line').id).toBe('highway/footway/sidewalk')
  })

  it('resolves fields with inheritance, translations and region filtering', () => {
    const road = en.fieldsOf(en.preset('highway/residential')!)
    expect(road.map((f) => f.id)).toEqual(['name', 'oneway', 'maxspeed', 'lanes', 'surface', 'structure', 'tunnel/name', 'access'])
    const crossing = cs.fieldsOf(cs.preset('highway/footway/crossing')!)
    expect(crossing.map((f) => f.id)).toContain('crossing')
    expect(crossing.map((f) => f.id)).not.toContain('crossing/markings-BG')
    const zebra = en.fieldsOf(en.preset('highway/footway/crossing/zebra')!)
    expect(zebra.length).toBeGreaterThan(0)
    const surface = cs.field('surface')!
    expect(surface.label).toBe('Povrch')
    expect(surface.optionLabels.asphalt.title).toBe('Asfalt')
    const access = cs.field('access')!
    expect(access.keys).toContain('foot')
    expect(access.optionLabels.designated.description).toBeTruthy()
    expect(en.universalFields().map((f) => f.id)).toContain('name')
  })

  it('searches names, terms and aliases without diacritics, cs falls back to English names', () => {
    expect(cs.search('chodnik', 'line')[0].id).toBe('custom/sidewalk-cz')
    expect(cs.search('prechod', 'line').map((p) => p.id)).toContain('highway/footway/crossing')
    expect(cs.search('crossing', 'vertex').map((p) => p.id)).toContain('highway/crossing/uncontrolled')
    expect(en.search('crossing', 'line').map((p) => p.id).slice(0, 5)).toContain('highway/footway/crossing')
    expect(en.search('highway=footway', 'line').map((p) => p.id)).toContain('highway/footway')
    expect(en.search('residential road', 'line')[0].id).toBe('highway/residential')
  })

  it('changes presets with iD semantics', () => {
    const footway = en.preset('highway/footway')!
    const crossing = en.preset('highway/footway/crossing')!
    const out = en.changePreset({ highway: 'footway', surface: 'asphalt', name: 'X', incline: 'up' }, 'line', footway, crossing)
    expect(out).toEqual({ highway: 'footway', footway: 'crossing', surface: 'asphalt', name: 'X', incline: 'up' })
    const back = en.changePreset(out, 'line', crossing, en.preset('highway/cycleway')!)
    expect(back.highway).toBe('cycleway')
    expect(back.footway).toBeUndefined()
    expect(back.surface).toBe('asphalt')
    const park = en.changePreset({ building: 'yes' }, 'area', en.preset('building')!, en.preset('leisure/park')!)
    expect(park).toEqual({ leisure: 'park' })
    const user = cs.changePreset({ highway: 'footway' }, 'line', cs.preset('highway/footway')!, cs.preset('custom/sidewalk-cz')!)
    expect(user).toEqual({ highway: 'footway', footway: 'sidewalk', surface: 'paving_stones' })
  })

  it('knows area keys like iD', () => {
    expect(en.isArea({ building: 'yes' })).toBe(true)
    expect(en.isArea({ highway: 'footway' })).toBe(false)
    expect(en.isArea({ highway: 'pedestrian', area: 'yes' })).toBe(true)
    expect(en.isArea({ barrier: 'fence' })).toBe(false)
  })

  it('lists categories and defaults per geometry', () => {
    const cats = cs.categories('line')
    expect(cats.find((c) => c.id === 'category-road_minor')?.members).toContain('highway/residential')
    expect(cs.defaults('vertex').map((p) => p.id)).toContain('highway/crossing/uncontrolled')
  })
})
