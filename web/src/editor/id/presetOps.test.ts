import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import * as iD from '@openstreetmap/id/modules/index'
import { IdAdapter, ID_SCHEMA_FILES, initIdData, primeIdData } from './adapter'
import { changePreset, mergeUserPresets, patchTags } from './presetOps'
import { PresetCatalog } from '@/presets/catalog'
import { RAW } from '@/presets/catalog.test'
import type { ViewState } from '../types'

const root = resolve(import.meta.dirname, '../../../../node_modules') + '/'
const json = (path: string) => JSON.parse(readFileSync(root + path, 'utf8'))
const fixture = readFileSync(resolve(import.meta.dirname, 'fixtures/katerinky.osm'), 'utf8')
const view: ViewState = { center: [17.8805, 49.9405], zoom: 17, width: 1200, height: 800, bounds: [[17.876, 49.938], [17.885, 49.943]] }

beforeAll(async () => {
  const schema = '@openstreetmap/id-tagging-schema/dist/'
  const files: Record<string, unknown> = {
    languages: json('@openstreetmap/id/dist/data/languages.min.json'),
    locale_general_en: json('@openstreetmap/id/dist/locales/en.min.json'),
  }
  for (const [key, file] of Object.entries(ID_SCHEMA_FILES)) files[key] = json(schema + file.replace(/\.json$/, '.min.json'))
  primeIdData(files)
  await initIdData()
})

function fresh(): IdAdapter {
  const a = new IdAdapter(view)
  a.loadXml(fixture, [[17.878, 49.939], [17.883, 49.942]])
  return a
}

const user = {
  id: 'custom/obytna-zona',
  name: 'Obytná zóna',
  geometry: ['line' as const],
  tags: { highway: 'living_street' },
  addTags: { highway: 'living_street', maxspeed: '20', surface: 'paving_stones' },
  removeTags: { highway: 'living_street', maxspeed: '20', surface: 'paving_stones' },
  fields: ['name', 'surface'],
  moreFields: [],
  icon: null,
  terms: [],
  aliases: [],
  matchScore: 1.5,
  searchable: true,
  extends: 'highway/living_street',
  apply: 'merge' as const,
  file: 'presets/obytna-zona.json',
}

describe('preset operations in edit mode', () => {
  const cat = new PresetCatalog(RAW, [user], 'cs')

  it('changes the preset of one way with iD semantics as one undoable step', () => {
    const a = fresh()
    const road = a.allEntities().find((e) => e.type === 'way' && e.tags.highway === 'residential' && e.tags.name) as any
    const before = { ...road.tags }
    const n = changePreset(a, {
      ids: [road.id],
      toId: 'highway/living_street',
      annotation: 'Changed the preset',
      useIdAction: true,
      computeTags: (_id, tags, g) => cat.changePreset(tags, g, cat.match(tags, g), cat.preset('highway/living_street')!),
    })
    expect(n).toBe(1)
    const after = a.graph().entity(road.id).tags
    expect(after.highway).toBe('living_street')
    expect(after.name).toBe(before.name)
    expect(a.undoLabel()).toBe('Changed the preset')
    const viaCatalog = cat.changePreset(before, 'line', cat.match(before, 'line'), cat.preset('highway/living_street')!)
    expect(viaCatalog).toEqual(after)
    a.undo()
    expect(a.graph().entity(road.id).tags).toEqual(before)
  })

  it('applies a user preset to a multi-selection in one step, through iD after merging it', () => {
    const a = fresh()
    mergeUserPresets([user])
    expect(iD.presetManager.item('custom/obytna-zona')).toBeTruthy()
    const roads = a.allEntities().filter((e) => e.type === 'way' && e.tags.highway === 'residential').slice(0, 3)
    const n = changePreset(a, {
      ids: roads.map((r) => r.id),
      toId: 'custom/obytna-zona',
      annotation: 'Applied',
      useIdAction: true,
      computeTags: () => ({}),
    })
    expect(n).toBe(3)
    for (const r of roads) {
      const tags = a.graph().entity(r.id).tags
      expect(tags.highway).toBe('living_street')
      expect(tags.maxspeed).toBe('20')
      expect(tags.surface).toBe('paving_stones')
      expect(iD.presetManager.match(a.graph().entity(r.id), a.graph()).id).toBe('custom/obytna-zona')
    }
    expect(a.editCount()).toBe(3)
    a.undo()
    expect(a.editCount()).toBe(0)
  })

  it('patches fields on several features as one step and coalesces single edits', () => {
    const a = fresh()
    const ways = a.allEntities().filter((e) => e.type === 'way' && e.tags.highway === 'residential').slice(0, 2)
    patchTags(a, ways.map((w) => w.id), { surface: 'asphalt', lit: undefined }, 'Changed tags')
    for (const w of ways) expect(a.graph().entity(w.id).tags.surface).toBe('asphalt')
    a.undo()
    expect(a.editCount()).toBe(0)
    patchTags(a, [ways[0].id], { surface: 'sett' }, 'x')
    patchTags(a, [ways[0].id], { surface: 'asphalt' }, 'x')
    a.undo()
    expect(a.editCount()).toBe(0)
  })

  it('draws a point and a line born with preset tags', () => {
    const a = fresh()
    a.drawTags = cat.setTags(cat.preset('amenity/bench')!, {}, 'point')
    const p = a.addPoint({ kind: 'none', loc: [17.88, 49.94] })
    expect(a.graph().entity(p).tags).toEqual({ amenity: 'bench' })
    a.drawTags = cat.setTags(cat.preset('highway/footway')!, {}, 'line')
    const w = a.startWay('line', { kind: 'none', loc: [17.8801, 49.9401] })
    a.extendWay({ kind: 'none', loc: [17.8803, 49.9402] })
    expect(a.finishWay()).toBe(w)
    expect(a.graph().entity(w).tags).toEqual({ highway: 'footway' })
    a.drawTags = cat.setTags(cat.preset('building')!, {}, 'area')
    const b = a.startWay('area', { kind: 'none', loc: [17.881, 49.941] })
    expect(a.graph().entity(b).tags).toEqual({ building: 'yes' })
  })
})
