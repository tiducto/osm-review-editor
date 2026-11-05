import { describe, expect, it } from 'vitest'
import type { FieldDef, FieldTags, TagPatch } from './types'
import {
  accessOptions,
  addMulti,
  addressRows,
  checkText,
  comboTagValue,
  fieldFamily,
  fieldKeys,
  identifierLink,
  isFieldAllowed,
  isFieldPresent,
  isValidColour,
  isValidDate,
  colourHex,
  nextCheck,
  readAccess,
  readCheck,
  readCombo,
  readDirectional,
  readInput,
  readLocalized,
  readMulti,
  readNumber,
  readRadio,
  readRoadheight,
  readRoadspeed,
  readWikidata,
  readWikipedia,
  removeFieldPatch,
  removeMulti,
  renameLocalizedVariant,
  reverseOneway,
  stepNumber,
  SUPPORTED_FIELD_TYPES,
  toggleMulti,
  visibleFields,
  wikidataLink,
  wikipediaLink,
  writeAccess,
  writeAddress,
  writeCombo,
  writeDirectional,
  writeInput,
  writeLocalizedName,
  writeLocalizedVariant,
  writeNumber,
  writeRadio,
  writeRoadheight,
  writeRoadspeed,
  writeStructureType,
  writeWikidata,
  writeWikipedia,
} from './fields'

function f(partial: Partial<FieldDef> & { id: string; type: string }): FieldDef {
  return { label: partial.id, optionLabels: {}, terms: [], ...partial }
}

function apply(tags: FieldTags, patch: TagPatch): FieldTags {
  const out: FieldTags = { ...tags }
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) delete out[k]
    else out[k] = v
  }
  return out
}

describe('input fields', () => {
  const name = f({ id: 'ref', type: 'text', key: 'ref' })
  it('round trips text', () => {
    const tags = apply({}, writeInput(name, {}, '  12a '))
    expect(tags).toEqual({ ref: '12a' })
    expect(readInput(name, tags)).toEqual({ value: '12a', mixed: null })
    expect(writeInput(name, tags, readInput(name, tags).value)).toEqual({ ref: '12a' })
    expect(apply(tags, writeInput(name, tags, ''))).toEqual({})
  })
  it('handles mixed values', () => {
    const tags: FieldTags = { ref: ['1', '2'] }
    expect(readInput(name, tags)).toEqual({ value: '', mixed: ['1', '2'] })
    expect(writeInput(name, tags, '')).toEqual({})
    expect(writeInput(name, tags, '3')).toEqual({ ref: '3' })
  })
  it('writes multi-key fields to the existing key', () => {
    const web = f({ id: 'website', type: 'url', key: 'website', keys: ['website', 'contact:website'] })
    expect(writeInput(web, {}, 'https://a.cz')).toEqual({ website: 'https://a.cz' })
    expect(writeInput(web, { 'contact:website': 'x' }, 'https://a.cz')).toEqual({ 'contact:website': 'https://a.cz' })
    expect(readInput(web, { 'contact:website': 'x' }).value).toBe('x')
  })
  it('keeps textarea line breaks', () => {
    const d = f({ id: 'description', type: 'textarea', key: 'description' })
    expect(writeInput(d, {}, 'a\nb ')).toEqual({ description: 'a\nb' })
  })
  it('builds identifier links', () => {
    const fb = f({ id: 'fb', type: 'identifier', key: 'contact:facebook', pattern: '^.+$', urlFormat: 'https://facebook.com/{value}' })
    expect(identifierLink(fb, 'foo bar')).toBe('https://facebook.com/foo%20bar')
    expect(identifierLink(fb, 'https://x.cz/a')).toBe('https://x.cz/a')
  })
  it('validates dates and colours', () => {
    expect(isValidDate('2020')).toBe(true)
    expect(isValidDate('2020-02')).toBe(true)
    expect(isValidDate('2020-02-29')).toBe(true)
    expect(isValidDate('2021-02-29')).toBe(false)
    expect(isValidDate('2020-13')).toBe(false)
    expect(isValidDate('20.5.2020')).toBe(false)
    expect(isValidColour('red')).toBe(true)
    expect(colourHex('#ABC')).toBe('#aabbcc')
    expect(isValidColour('#12345')).toBe(false)
  })
})

describe('number fields', () => {
  const layer = f({ id: 'layer', type: 'integer', key: 'layer', minValue: -5, maxValue: 5 })
  const width = f({ id: 'width', type: 'number', key: 'width' })
  it('localizes and parses', () => {
    expect(readNumber(width, { width: '3.5' }, 'cs').value).toBe('3,5')
    expect(readNumber(width, { width: '3.5' }, 'en').value).toBe('3.5')
    expect(writeNumber(width, {}, '3,5')).toEqual({ width: '3.5' })
    const tags = { width: '12.25' }
    expect(writeNumber(width, tags, readNumber(width, tags, 'cs').value)).toEqual({ width: '12.25' })
    expect(writeNumber(width, {}, 'abc')).toEqual({ width: 'abc' })
  })
  it('steps and clamps', () => {
    expect(stepNumber(layer, {}, 1)).toEqual({ layer: '1' })
    expect(stepNumber(layer, { layer: '-1' }, -1)).toEqual({ layer: '-2' })
    expect(stepNumber(layer, { layer: '5' }, 1)).toEqual({ layer: '5' })
    expect(writeNumber(layer, {}, '9')).toEqual({ layer: '5' })
    expect(stepNumber(f({ id: 'x', type: 'number', key: 'x', increment: 0.5 }), { x: '1' }, 1)).toEqual({ x: '1.5' })
    expect(stepNumber(layer, { layer: ['1', '2'] }, 1)).toEqual({})
  })
})

describe('combo fields', () => {
  const surface = f({
    id: 'surface',
    type: 'combo',
    key: 'surface',
    options: ['asphalt', 'paving_stones'],
    optionLabels: { asphalt: { title: 'Asfalt' }, paving_stones: { title: 'Dlažba' } },
  })
  it('round trips option titles', () => {
    const tags = apply({}, writeCombo(surface, {}, 'dlažba'))
    expect(tags).toEqual({ surface: 'paving_stones' })
    const st = readCombo(surface, tags)
    expect(st.display).toBe('Dlažba')
    expect(writeCombo(surface, tags, st.display)).toEqual({ surface: 'paving_stones' })
  })
  it('normalizes custom values', () => {
    expect(writeCombo(surface, {}, 'Fine Gravel')).toEqual({ surface: 'fine_gravel' })
    const cs = f({ id: 'n', type: 'combo', key: 'n', snake_case: false, caseSensitive: true })
    expect(writeCombo(cs, {}, 'Foo Bar')).toEqual({ n: 'Foo Bar' })
    expect(comboTagValue(f({ id: 't', type: 'combo', key: 'type' }), 'associatedStreet')).toBe('associatedStreet')
  })
  it('treats empty typeCombo as yes', () => {
    const bridge = f({ id: 'bridge', type: 'typeCombo', key: 'bridge' })
    expect(writeCombo(bridge, {}, '')).toEqual({ bridge: 'yes' })
    expect(readCombo(bridge, { bridge: 'yes' }).display).toBe('')
  })
  it('does not override mixed with blank', () => {
    expect(writeCombo(surface, { surface: ['asphalt', 'gravel'] }, '')).toEqual({})
    expect(readCombo(surface, { surface: ['asphalt', 'gravel'] }).mixed).toEqual(['Asfalt', 'gravel'])
  })
})

describe('multi combos', () => {
  const payment = f({ id: 'payment_multi', type: 'multiCombo', key: 'payment:', options: ['cash', 'cards'], optionLabels: { cash: { title: 'Hotovost' } } })
  it('multiCombo writes prefixed keys', () => {
    let tags: FieldTags = {}
    tags = apply(tags, addMulti(payment, tags, 'Hotovost'))
    expect(tags).toEqual({ 'payment:cash': 'yes' })
    const st = readMulti(payment, tags)
    expect(st.items.map((i) => [i.key, i.title, i.state])).toEqual([['payment:cash', 'Hotovost', 'yes']])
    expect(st.available.map((o) => o.value)).toEqual(['cards'])
    expect(toggleMulti(payment, tags, st.items[0])).toEqual({ 'payment:cash': 'no' })
    expect(apply(tags, removeMulti(payment, tags, st.items[0]))).toEqual({})
    expect(addMulti(payment, { 'payment:cash': 'only' }, 'cash')).toEqual({})
    expect(isFieldPresent(payment, tags)).toBe(true)
    expect(fieldKeys(payment, { 'payment:cash': 'yes', name: 'x' })).toEqual(['payment:cash'])
  })
  it('semiCombo joins values', () => {
    const cuisine = f({ id: 'cuisine', type: 'semiCombo', key: 'cuisine', options: ['pizza', 'burger'], optionLabels: { pizza: { title: 'Pizza' } } })
    let tags: FieldTags = { cuisine: 'pizza' }
    tags = apply(tags, addMulti(cuisine, tags, 'burger, kebab'))
    expect(tags).toEqual({ cuisine: 'pizza;burger;kebab' })
    const st = readMulti(cuisine, tags)
    expect(st.items.map((i) => i.value)).toEqual(['pizza', 'burger', 'kebab'])
    expect(apply(tags, removeMulti(cuisine, tags, st.items[1]))).toEqual({ cuisine: 'pizza;kebab' })
    const mixed = readMulti(cuisine, { cuisine: ['pizza;burger', 'pizza'] })
    expect(mixed.items.map((i) => [i.value, i.mixed])).toEqual([
      ['pizza', false],
      ['burger', true],
    ])
  })
  it('manyCombo sets option keys', () => {
    const gender = f({ id: 'gender', type: 'manyCombo', keys: ['male', 'female', 'unisex'], options: ['male', 'female', 'unisex'], optionLabels: { female: { title: 'Ženy' } } })
    let tags: FieldTags = {}
    tags = apply(tags, addMulti(gender, tags, 'ženy'))
    expect(tags).toEqual({ female: 'yes' })
    expect(readMulti(gender, tags).items.map((i) => i.key)).toEqual(['female'])
    expect(addMulti(gender, tags, 'other')).toEqual({})
    expect(fieldFamily(gender)).toBe('multi')
  })
})

describe('check fields', () => {
  const fee = f({ id: 'fee', type: 'check', key: 'fee' })
  it('cycles unknown, yes, no', () => {
    let tags: FieldTags = {}
    tags = apply(tags, nextCheck(fee, tags))
    expect(tags).toEqual({ fee: 'yes' })
    tags = apply(tags, nextCheck(fee, tags))
    expect(tags).toEqual({ fee: 'no' })
    tags = apply(tags, nextCheck(fee, tags))
    expect(tags).toEqual({})
    expect(checkText(fee, readCheck(fee, { fee: 'yes' }), 'cs')).toBe('Ano')
    expect(checkText(fee, readCheck(fee, {}), 'en')).toBe('Unknown')
    expect(nextCheck(fee, { fee: ['yes', 'no'] })).toEqual({ fee: 'yes' })
  })
  it('defaultCheck toggles yes and unset', () => {
    const d = f({ id: 'd', type: 'defaultCheck', key: 'bicycle_road' })
    expect(nextCheck(d, {})).toEqual({ bicycle_road: 'yes' })
    expect(nextCheck(d, { bicycle_road: 'yes' })).toEqual({ bicycle_road: undefined })
  })
  it('onewayCheck reverses and skips special states', () => {
    const o = f({ id: 'oneway', type: 'onewayCheck', key: 'oneway', options: ['undefined', 'yes', 'no', 'reversible', 'alternating'] })
    expect(nextCheck(o, { oneway: 'no' })).toEqual({ oneway: undefined })
    expect(nextCheck(o, { oneway: '-1' })).toEqual({ oneway: 'no' })
    expect(reverseOneway(o, { oneway: 'yes' })).toEqual({ oneway: '-1' })
    expect(reverseOneway(o, { oneway: '-1' })).toEqual({ oneway: 'yes' })
    expect(reverseOneway(o, { oneway: 'no' })).toEqual({})
    expect(checkText(o, readCheck(o, { oneway: '-1' }), 'en')).toBe('Yes, reverse direction')
    expect(checkText(o, readCheck(o, { oneway: 'reversible' }), 'cs')).toBe('Reverzibilní')
    expect(nextCheck(o, { oneway: 'reversible' })).toEqual({ oneway: undefined })
  })
})

describe('radio fields', () => {
  it('key radio', () => {
    const r = f({ id: 'blind', type: 'radio', key: 'blind', options: ['yes', 'limited', 'no'] })
    const tags = apply({}, writeRadio(r, {}, 'limited'))
    expect(readRadio(r, tags).selected).toBe('limited')
    expect(writeRadio(r, tags, readRadio(r, tags).selected)).toEqual({ blind: 'limited' })
    expect(readRadio(r, { blind: 'other' }).options.map((o) => o.value)).toContain('other')
  })
  it('keys radio sets one key to yes', () => {
    const r = f({ id: 'k', type: 'radio', keys: ['a', 'b'], options: ['a', 'b'] })
    expect(writeRadio(r, { a: 'yes' }, 'b')).toEqual({ a: undefined, b: 'yes' })
    expect(readRadio(r, { b: 'yes' }).selected).toBe('b')
  })
  it('structureRadio manages layer', () => {
    const s = f({ id: 'structure', type: 'structureRadio', keys: ['bridge', 'tunnel', 'embankment', 'cutting', 'ford'], options: ['bridge', 'tunnel', 'embankment', 'cutting', 'ford'] })
    expect(writeRadio(s, {}, 'bridge')).toEqual({ bridge: 'yes', layer: '1' })
    expect(writeRadio(s, { layer: '2' }, 'bridge')).toEqual({ bridge: 'yes', layer: '2' })
    expect(writeRadio(s, { bridge: 'yes', layer: '1' }, 'tunnel')).toEqual({ bridge: undefined, tunnel: 'yes', layer: '-1' })
    expect(writeRadio(s, { waterway: 'stream' }, 'tunnel')).toEqual({ tunnel: 'culvert', layer: '-1' })
    expect(writeRadio(s, { bridge: 'yes', layer: '1' }, 'embankment')).toEqual({ bridge: undefined, embankment: 'yes', layer: undefined })
    expect(writeStructureType({ tunnel: 'yes', layer: '-1' }, 'tunnel', null, 'building_passage')).toEqual({ tunnel: 'building_passage', layer: undefined })
    expect(writeStructureType({ bridge: 'yes' }, 'bridge', null, '')).toEqual({ bridge: 'yes', layer: '1' })
    expect(fieldKeys(s, { bridge: 'yes', layer: '1' })).toContain('layer')
    expect(removeFieldPatch(s, { bridge: 'yes', layer: '1', highway: 'x' })).toEqual({ bridge: undefined, layer: undefined })
  })
})

describe('access', () => {
  const access = f({
    id: 'access',
    type: 'access',
    keys: ['access', 'foot', 'motor_vehicle', 'bicycle', 'horse'],
    types: { access: 'Všem', foot: 'Pěší' },
    optionLabels: { designated: { title: 'Vyhrazeno' }, no: { title: 'Zakázán' } },
  })
  it('reads implied values and round trips', () => {
    const rows = readAccess(access, { highway: 'footway' }, 'cs')
    expect(rows[1]).toMatchObject({ key: 'foot', label: 'Pěší', value: '', placeholder: 'Vyhrazeno' })
    const tags = apply({}, writeAccess(access, {}, 'foot', 'Zakázán'))
    expect(tags).toEqual({ foot: 'no' })
    expect(writeAccess(access, tags, 'foot', readAccess(access, tags, 'cs')[1].display)).toEqual({ foot: 'no' })
    expect(accessOptions(access, 'access').map((o) => o.value)).not.toContain('designated')
    expect(accessOptions(access, 'bicycle').map((o) => o.value)).toContain('dismount')
  })
})

describe('address', () => {
  const addr = f({
    id: 'address',
    type: 'address',
    key: 'addr',
    keys: ['addr:city', 'addr:street', 'addr:housenumber', 'addr:conscriptionnumber', 'addr:postcode', 'addr:suburb'],
    labels: { conscriptionnumber: 'Číslo popisné' },
    placeholders: { street: 'Ulice', city: 'Město', postcode: 'PSČ' },
  })
  it('uses the CZ format', () => {
    const rows = addressRows(addr, { 'addr:suburb': 'X' }, 'cs')
    expect(rows[0].map((s) => s.id)).toEqual(['street', 'housenumber'])
    expect(rows.flat().map((s) => s.id)).toContain('conscriptionnumber')
    expect(rows.flat().map((s) => s.id)).toContain('suburb')
    expect(rows[0][0].placeholder).toBe('Ulice')
    const tags = apply({}, writeAddress({}, 'addr:street', ' Hlavní '))
    expect(tags).toEqual({ 'addr:street': 'Hlavní' })
    expect(isFieldPresent(addr, tags)).toBe(true)
  })
})

describe('localized', () => {
  const name = f({ id: 'name', type: 'localized', key: 'name' })
  it('reads and writes variants', () => {
    let tags: FieldTags = { name: 'Praha', 'name:en': 'Prague', 'name:left': 'x' }
    const st = readLocalized(name, tags)
    expect(st.name.value).toBe('Praha')
    expect(st.variants.map((v) => v.lang)).toEqual(['en'])
    tags = apply(tags, writeLocalizedVariant(name, tags, 'de', 'Prag'))
    expect(tags['name:de']).toBe('Prag')
    expect(renameLocalizedVariant(name, tags, 'de', 'pl')).toEqual({ 'name:de': undefined, 'name:pl': 'Prag' })
    expect(writeLocalizedName(name, { name: ['a', 'b'] }, '')).toEqual({})
    expect(isFieldPresent(name, { 'name:cs': 'X' })).toBe(true)
  })
})

describe('wiki fields', () => {
  const wd = f({ id: 'wikidata', type: 'wikidata', key: 'wikidata', keys: ['wikidata', 'wikipedia'] })
  const wp = f({ id: 'wikipedia', type: 'wikipedia', key: 'wikipedia' })
  it('wikidata', () => {
    expect(writeWikidata(wd, {}, 'q42')).toEqual({ wikidata: 'Q42' })
    expect(writeWikidata(wd, {}, 'https://www.wikidata.org/wiki/Q1085')).toEqual({ wikidata: 'Q1085' })
    expect(readWikidata(wd, { wikidata: 'Q1085' }).value).toBe('Q1085')
    expect(wikidataLink('Q1085')).toBe('https://www.wikidata.org/wiki/Q1085')
    expect(removeFieldPatch(wd, { wikidata: 'Q1', wikipedia: 'cs:X' })).toEqual({ wikidata: undefined, wikipedia: undefined })
  })
  it('wikipedia', () => {
    const tags = apply({}, writeWikipedia(wp, {}, 'cs', 'Karlův most'))
    expect(tags).toEqual({ wikipedia: 'cs:Karlův most' })
    const st = readWikipedia(wp, tags, 'en')
    expect(st).toEqual({ lang: 'cs', title: 'Karlův most', mixed: null })
    expect(writeWikipedia(wp, tags, st.lang, st.title)).toEqual({ wikipedia: 'cs:Karlův most' })
    expect(writeWikipedia(wp, {}, 'cs', 'https://en.wikipedia.org/wiki/Charles_Bridge')).toEqual({ wikipedia: 'en:Charles Bridge' })
    expect(wikipediaLink(st)).toBe('https://cs.wikipedia.org/wiki/Karl%C5%AFv_most')
    expect(isFieldAllowed(wp, {}, ['point'], true)).toBe(false)
  })
})

describe('directionalCombo', () => {
  const sidewalk = f({ id: 'sidewalk', type: 'directionalCombo', key: 'sidewalk:both', keys: ['sidewalk:left', 'sidewalk:right'], options: ['yes', 'no', 'separate'] })
  it('reads the reference key', () => {
    expect(readDirectional(sidewalk, { sidewalk: 'both' })).toEqual({ 'sidewalk:left': 'yes', 'sidewalk:right': 'yes' })
    expect(readDirectional(sidewalk, { sidewalk: 'left' })).toEqual({ 'sidewalk:left': 'yes', 'sidewalk:right': 'no' })
    expect(readDirectional(sidewalk, { 'sidewalk:both': 'separate' })).toEqual({ 'sidewalk:left': 'separate', 'sidewalk:right': 'separate' })
    expect(readDirectional(sidewalk, { sidewalk: 'no', 'sidewalk:right': 'yes' })).toEqual({ 'sidewalk:left': 'no', 'sidewalk:right': ['no', 'yes'] })
  })
  it('collapses equal sides', () => {
    let tags: FieldTags = { 'sidewalk:left': 'yes' }
    tags = apply(tags, writeDirectional(sidewalk, tags, 'sidewalk:right', 'yes'))
    expect(tags).toEqual({ 'sidewalk:both': 'yes' })
    tags = apply(tags, writeDirectional(sidewalk, tags, 'sidewalk:left', 'no'))
    expect(tags).toEqual({ 'sidewalk:left': 'no', 'sidewalk:right': 'yes' })
    let t2: FieldTags = { sidewalk: 'left' }
    t2 = apply(t2, writeDirectional(sidewalk, t2, 'sidewalk:right', 'yes'))
    expect(t2).toEqual({ 'sidewalk:both': 'yes' })
    const read = readDirectional(sidewalk, tags)
    expect(apply(tags, writeDirectional(sidewalk, tags, 'sidewalk:left', read['sidewalk:left'] as string))).toEqual(tags)
  })
  it('works with key without :both', () => {
    const cw = f({ id: 'cycleway', type: 'directionalCombo', key: 'cycleway', keys: ['cycleway:left', 'cycleway:right'] })
    let tags: FieldTags = { cycleway: 'lane' }
    expect(readDirectional(cw, tags)).toEqual({ 'cycleway:left': 'lane', 'cycleway:right': 'lane' })
    tags = apply(tags, writeDirectional(cw, tags, 'cycleway:left', 'no'))
    expect(tags).toEqual({ 'cycleway:left': 'no', 'cycleway:right': 'lane' })
  })
})

describe('roadspeed and roadheight', () => {
  const ms = f({ id: 'maxspeed', type: 'roadspeed', key: 'maxspeed' })
  const mh = f({ id: 'maxheight', type: 'roadheight', key: 'maxheight' })
  it('roadspeed', () => {
    expect(writeRoadspeed(ms, {}, '50', 'km/h')).toEqual({ maxspeed: '50' })
    expect(writeRoadspeed(ms, {}, '30', 'mph')).toEqual({ maxspeed: '30 mph' })
    expect(writeRoadspeed(ms, {}, 'walk', 'km/h')).toEqual({ maxspeed: 'walk' })
    const st = readRoadspeed(ms, { maxspeed: '30 mph' }, 'cs')
    expect(st).toMatchObject({ value: '30', unit: 'mph' })
    expect(writeRoadspeed(ms, { maxspeed: '30 mph' }, st.value, st.unit)).toEqual({ maxspeed: '30 mph' })
    expect(readRoadspeed(ms, { maxspeed: 'none' }, 'cs').special).toBe('none')
    expect(writeRoadspeed(ms, { maxspeed: ['50', '30'] }, '', 'km/h')).toEqual({})
  })
  it('roadheight', () => {
    expect(writeRoadheight(mh, {}, '3,5', '', 'm')).toEqual({ maxheight: '3.5' })
    expect(writeRoadheight(mh, {}, '12', '6', 'ft')).toEqual({ maxheight: `12'6"` })
    const st = readRoadheight(mh, { maxheight: `12'6"` }, 'en')
    expect(st).toMatchObject({ primary: '12', secondary: '6', unit: 'ft' })
    expect(writeRoadheight(mh, {}, st.primary, st.secondary, st.unit)).toEqual({ maxheight: `12'6"` })
    const m = readRoadheight(mh, { maxheight: '3.5' }, 'cs')
    expect(m.primary).toBe('3,5')
    expect(writeRoadheight(mh, {}, m.primary, m.secondary, m.unit)).toEqual({ maxheight: '3.5' })
    expect(writeRoadheight(mh, {}, 'below_default', '', 'm')).toEqual({ maxheight: 'below_default' })
  })
})

describe('visibility', () => {
  const name = f({ id: 'name', type: 'localized', key: 'name', universal: true, label: 'Name' })
  const level = f({ id: 'level', type: 'combo', key: 'level', prerequisiteTag: { keyNot: 'building' }, label: 'Level' })
  const hydro = f({ id: 'gen', type: 'radio', key: 'generator:method', prerequisiteTag: { key: 'generator:source', value: 'hydro' }, label: 'Method' })
  const vertexOnly = f({ id: 'v', type: 'check', key: 'v', geometry: ['vertex'], label: 'Vertex' })
  const fee = f({ id: 'fee', type: 'check', key: 'fee', label: 'Fee' })
  const access = f({ id: 'access', type: 'access', keys: ['access', 'foot'], label: 'Access' })
  it('prerequisiteTag', () => {
    expect(isFieldAllowed(level, {}, ['point'])).toBe(true)
    expect(isFieldAllowed(level, { building: 'yes' }, ['area'])).toBe(false)
    expect(isFieldAllowed(level, { building: 'yes', level: '1' }, ['area'])).toBe(true)
    expect(isFieldAllowed(hydro, { 'generator:source': 'hydro' }, ['point'])).toBe(true)
    expect(isFieldAllowed(hydro, { 'generator:source': 'wind' }, ['point'])).toBe(false)
    expect(isFieldAllowed(hydro, { 'generator:source': ['hydro', 'wind'] }, ['point'])).toBe(false)
    expect(isFieldAllowed(f({ id: 'x', type: 'check', key: 'x', prerequisiteTag: { key: 'oneway' } }), {}, ['line'])).toBe(false)
  })
  it('geometry', () => {
    expect(isFieldAllowed(vertexOnly, {}, ['vertex'])).toBe(true)
    expect(isFieldAllowed(vertexOnly, {}, ['vertex', 'point'])).toBe(false)
  })
  it('shows main fields, present more fields and user added ones', () => {
    const r1 = visibleFields({ fields: [fee, vertexOnly], moreFields: [name, level, access], tags: {}, geometries: ['point'] })
    expect(r1.shown.map((x) => x.id)).toEqual(['fee'])
    expect(r1.addable.map((x) => x.id)).toEqual(['access', 'level', 'name'])
    const r2 = visibleFields({ fields: [fee], moreFields: [name, level, access], tags: { name: 'X' }, geometries: ['point'], shownIds: ['access'], locale: 'cs' })
    expect(r2.shown.map((x) => x.id)).toEqual(['fee', 'access', 'name'])
    expect(r2.addable.map((x) => x.id)).toEqual(['level'])
    const r3 = visibleFields({ fields: [fee], moreFields: [level], tags: { building: 'yes' }, geometries: ['area'] })
    expect(r3.addable).toEqual([])
  })
  it('covers every supported type with a family', () => {
    for (const type of SUPPORTED_FIELD_TYPES) expect(fieldFamily(f({ id: type, type, key: 'k' }))).not.toBe('fallback')
    expect(fieldFamily(f({ id: 's', type: 'schedule', key: 'opening_hours' }))).toBe('input')
    expect(fieldFamily(f({ id: 'r', type: 'restrictions' }))).toBe('fallback')
  })
})
