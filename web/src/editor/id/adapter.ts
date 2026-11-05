import * as iD from '@openstreetmap/id/modules/index'
import { JXON } from '@openstreetmap/id/modules/util/jxon'
import generalEnUrl from '@openstreetmap/id/dist/locales/en.min.json?url'
import languagesUrl from '@openstreetmap/id/dist/data/languages.min.json?url'
import type {
  Bounds,
  EnteredMode,
  EntitySnapshot,
  IdEntity,
  IdGraph,
  IdNode,
  IdRemap,
  IdWay,
  LngLatTuple,
  OperationInfo,
  OsmChangeCounts,
  PresetInfo,
  RehydrateReport,
  SnapTarget,
  Tags,
  ViewState,
} from '../types'

export const ID_EDITOR_VERSION = '2.42.3'

type AnyFn = (...args: any[]) => any

export const ID_SCHEMA_FILES: Record<string, string> = {
  preset_presets: 'presets.json',
  preset_fields: 'fields.json',
  preset_categories: 'preset_categories.json',
  preset_defaults: 'preset_defaults.json',
  deprecated: 'deprecated.json',
  discarded: 'discarded.json',
  locale_tagging_en: 'translations/en.json',
}

const LOCALE_INDEX = { en: { rtl: false, pct: 1 } }

let dataReady: Promise<void> | null = null

let loadedCheck: (loc: LngLatTuple) => boolean = () => true

const osmServiceStub = {
  isDataLoaded: (loc: LngLatTuple) => loadedCheck(loc),
}

function polyfillIdle() {
  const w = globalThis as any
  w.requestIdleCallback ??= (cb: AnyFn) => setTimeout(() => cb({ didTimeout: false, timeRemaining: () => 50 }), 0)
  w.cancelIdleCallback ??= (h: number) => clearTimeout(h)
}

export function primeIdData(files: Record<string, unknown>): void {
  Object.assign(iD.fileFetcher.cache(), {
    locales: LOCALE_INDEX,
    locales_index_general: LOCALE_INDEX,
    locales_index_tagging: LOCALE_INDEX,
    ...files,
  })
}

export async function initIdData(): Promise<void> {
  polyfillIdle()
  iD.localizer.preferredLocaleCodes('en')
  await iD.localizer.ensureLoaded()
  await iD.presetManager.ensureLoaded()
  const registry = iD.services as Record<string, unknown>
  delete registry.nsi
  delete registry.maprules
  registry.osm = osmServiceStub
}

async function fetchJson(url: string): Promise<unknown> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`)
  return res.json()
}

export function loadIdData(schemaBase = '/api/presets/id-schema/'): Promise<void> {
  dataReady ??= (async () => {
    const keys = Object.keys(ID_SCHEMA_FILES)
    const [general, languages, ...schema] = await Promise.all([
      fetchJson(generalEnUrl),
      fetchJson(languagesUrl),
      ...keys.map((k) => fetchJson(schemaBase + ID_SCHEMA_FILES[k])),
    ])
    const files: Record<string, unknown> = { locale_general_en: general, languages }
    keys.forEach((k, i) => (files[k] = schema[i]))
    primeIdData(files)
    await initIdData()
  })().catch((e) => {
    dataReady = null
    throw e
  })
  return dataReady
}

function stub(): any {
  const fn = function () {}
  return new Proxy(fn, {
    get(_t, key) {
      if (key === Symbol.toPrimitive) return () => ''
      if (key === 'then') return undefined
      if (typeof key !== 'string') return undefined
      return stub()
    },
    apply() {
      return stub()
    },
  })
}

export function idText(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value !== 'function') return value == null ? '' : String(value)
  const out: string[] = []
  const sel: any = {
    append: () => sel,
    attr: () => sel,
    classed: () => sel,
    style: () => sel,
    html: (s: string) => (out.push(String(s).replace(/<[^>]*>/g, '')), sel),
    text: (s: string) => (out.push(String(s)), sel),
    call: (fn: AnyFn, ...rest: unknown[]) => (fn(sel, ...rest), sel),
  }
  ;(value as AnyFn)(sel)
  return out.join('').trim()
}

function noTransition(action: any) {
  if (typeof action !== 'function' || !action.transitionable) return action
  return (graph: any) => action(graph, 1)
}

const TYPES: Record<string, 'node' | 'way' | 'relation'> = { n: 'node', w: 'way', r: 'relation' }

export function osmIdOf(id: string): number {
  return Number(id.slice(1))
}

export function entityId(type: string, ref: string | number): string {
  return type[0] + String(ref)
}

function tagsOf(el: Element): Tags {
  const tags: Tags = {}
  for (const t of Array.from(el.children)) if (t.tagName === 'tag') tags[t.getAttribute('k')!] = t.getAttribute('v') ?? ''
  return tags
}

function elementToEntity(el: Element, versionFallback?: string): any {
  const id = entityId(el.tagName, el.getAttribute('id')!)
  const meta = {
    id,
    version: el.getAttribute('version') ?? versionFallback,
    visible: el.getAttribute('visible') !== 'false',
    user: el.getAttribute('user') ?? undefined,
    uid: el.getAttribute('uid') ?? undefined,
    changeset: el.getAttribute('changeset') ?? undefined,
    timestamp: el.getAttribute('timestamp') ?? undefined,
    tags: tagsOf(el),
  }
  if (el.tagName === 'node') {
    const lat = el.getAttribute('lat')
    const lon = el.getAttribute('lon')
    return new iD.osmNode({ ...meta, loc: lat != null && lon != null ? [Number(lon), Number(lat)] : undefined })
  }
  if (el.tagName === 'way') {
    const nodes = Array.from(el.children)
      .filter((c) => c.tagName === 'nd')
      .map((nd) => 'n' + nd.getAttribute('ref'))
    return new iD.osmWay({ ...meta, nodes })
  }
  const members = Array.from(el.children)
    .filter((c) => c.tagName === 'member')
    .map((m) => ({ id: entityId(m.getAttribute('type')!, m.getAttribute('ref')!), type: m.getAttribute('type'), role: m.getAttribute('role') ?? '' }))
  return new iD.osmRelation({ ...meta, members })
}

function parseXml(text: string): Document {
  const doc = new DOMParser().parseFromString(text, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) throw new Error('Invalid OSM XML')
  return doc
}

export function parseOsmXml(text: string): any[] {
  const root = parseXml(text).documentElement
  return Array.from(root.children)
    .filter((el) => el.tagName === 'node' || el.tagName === 'way' || el.tagName === 'relation')
    .map((el) => elementToEntity(el))
}

export interface ParsedOsmChange {
  create: any[]
  modify: any[]
  delete: any[]
}

export function parseOsmChange(text: string): ParsedOsmChange {
  const root = parseXml(text).documentElement
  const out: ParsedOsmChange = { create: [], modify: [], delete: [] }
  for (const block of Array.from(root.children)) {
    const list = out[block.tagName as keyof ParsedOsmChange]
    if (!list) continue
    for (const el of Array.from(block.children)) {
      if (el.tagName === 'node' || el.tagName === 'way' || el.tagName === 'relation') list.push(elementToEntity(el))
    }
  }
  return out
}

function idsInXml(text: string): string[] {
  const out: string[] = []
  const re = /<(node|way|relation)\b[^>]*\bid="(-?\d+)"/g
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) out.push(entityId(m[1], m[2]))
  const refs = /<(nd|member)\b[^>]*\bref="(-\d+)"[^>]*/g
  while ((m = refs.exec(text))) {
    if (m[1] === 'nd') out.push('n' + m[2])
    else {
      const type = /type="(\w+)"/.exec(m[0])?.[1]
      if (type) out.push(entityId(type, m[2]))
    }
  }
  return out
}

const OPERATIONS: [string, string][] = [
  ['continue', 'operationContinue'],
  ['split', 'operationSplit'],
  ['disconnect', 'operationDisconnect'],
  ['merge', 'operationMerge'],
  ['extract', 'operationExtract'],
  ['reverse', 'operationReverse'],
  ['orthogonalize', 'operationOrthogonalize'],
  ['circularize', 'operationCircularize'],
  ['straighten', 'operationStraighten'],
  ['move', 'operationMove'],
  ['rotate', 'operationRotate'],
  ['delete', 'operationDelete'],
]

export const OPERATION_KEYS: Record<string, string> = {
  continue: 'a',
  split: 'x',
  disconnect: 'd',
  merge: 'c',
  extract: 'e',
  reverse: 'v',
  orthogonalize: 'q',
  circularize: 'o',
  straighten: 's',
  move: 'm',
  rotate: 'r',
}

export interface DrawingState {
  wayId: string
  kind: 'line' | 'area'
  affix: 'prefix' | 'suffix'
  steps: number
}

interface DragState {
  id: string
  moved: boolean
  created: boolean
}

export interface IdIssueFix {
  id: string
  title: string
  disabled: string | null
}

export interface IdIssue {
  id: string
  type: string
  subtype: string | null
  severity: 'error' | 'warning' | 'suggestion'
  message: string
  entityIds: string[]
  loc: LngLatTuple | null
  fixes: IdIssueFix[]
}

const SKIPPED_VALIDATIONS = ['help_request', 'incompatible_source', 'maprules', 'osm_api_limits']

interface TransformState {
  kind: 'move' | 'rotate'
  ids: string[]
  originLoc: LngLatTuple
  pivot: [number, number]
  startAngle: number
  started: boolean
  cache: Record<string, unknown>
}

export class IdAdapter {
  readonly context: any
  readonly history: any
  readonly projection: any
  readonly validator: any
  selected: string[] = []
  private fixSelection: string[] | null = null
  drawing: DrawingState | null = null
  onEnter: (mode: EnteredMode) => void = () => {}
  private view: ViewState
  private loadedBounds: Bounds[] = []
  private drag: DragState | null = null
  private transform: TransformState | null = null
  private tagEdit: { id: string; graph: unknown } | null = null
  mouseLoc: LngLatTuple = [0, 0]

  constructor(view?: ViewState) {
    this.view = view ?? { center: [0, 0], zoom: 17, width: 1024, height: 768, bounds: [[-0.01, -0.01], [0.01, 0.01]] }
    this.projection = iD.geoRawMercator()
    const self = this
    const mapShim: Record<string, AnyFn> = {
      extent: () => iD.geoExtent(self.view.bounds[0], self.view.bounds[1]),
      zoom: () => self.view.zoom + 1,
      center: () => self.view.center,
      dimensions: () => [self.view.width, self.view.height],
      mouseCoordinates: () => self.mouseLoc,
      mouse: () => self.projection(self.mouseLoc),
      pan: () => mapProxy,
      editableDataEnabled: () => true,
      isInWideSelection: () => false,
    }
    const fallback = stub()
    const mapProxy: any = new Proxy(mapShim, {
      get: (obj, key) => (typeof key === 'string' && key in obj ? obj[key] : fallback[key as string]),
    })
    const connection = {
      isDataLoaded: (loc: LngLatTuple) => self.isLoaded(loc),
      maxWayNodes: () => 2000,
      loadTileAtLoc: () => undefined,
    }
    const target: Record<string, any> = {
      projection: this.projection,
      initialHashParams: {},
      graph: () => self.history.graph(),
      history: () => self.history,
      entity: (id: string) => self.history.graph().entity(id),
      hasEntity: (id: string) => self.history.graph().hasEntity(id),
      perform: (action: any, ...rest: any[]) => self.history.perform(noTransition(action), ...rest),
      replace: (action: any, ...rest: any[]) => self.history.replace(noTransition(action), ...rest),
      pop: (n?: number) => self.history.pop(n),
      undo: () => self.history.undo(),
      redo: () => self.history.redo(),
      selectedIDs: () => self.selected,
      enter: (mode: any) => self.handleEnter(mode),
      mode: () => {
        const ids = self.fixSelection ?? self.selected
        return ids.length ? { id: 'select', selectedIDs: () => ids } : { id: 'browse', selectedIDs: () => [] }
      },
      map: () => mapProxy,
      editable: () => true,
      inIntro: () => false,
      hasHiddenConnections: () => false,
      connection: () => connection,
      loadTileAtLoc: () => undefined,
      validator: () => self.validator,
      on: () => context,
    }
    const context: any = new Proxy(target, {
      get(obj, key) {
        if (typeof key !== 'string') return Reflect.get(obj, key)
        if (key in obj) return obj[key]
        return fallback[key]
      },
    })
    this.context = context
    this.history = iD.coreHistory(context)
    this.history.checkpoint('ore-base')
    this.validator = iD.coreValidator(context)
    this.validator.init()
    this.history.on('merge.validator', null)
    loadedCheck = (loc) => self.isLoaded(loc)
    this.setView(this.view)
  }

  validate(): Promise<void> {
    loadedCheck = (loc) => this.isLoaded(loc)
    return Promise.resolve(this.validator.validate()).catch(() => undefined)
  }

  private issueFixes(issue: any): any[] {
    this.fixSelection = issue.entityIds?.length ? [issue.entityIds[0]] : []
    try {
      return issue.fixes(this.context)
    } catch {
      return []
    } finally {
      this.fixSelection = null
    }
  }

  idIssues(): IdIssue[] {
    const g = this.history.graph()
    const out: IdIssue[] = []
    for (const issue of this.validator.getIssues({ what: 'edited', where: 'all', includeIgnored: false })) {
      if (SKIPPED_VALIDATIONS.includes(issue.type)) continue
      let loc: LngLatTuple | null = issue.loc ?? null
      if (!loc) {
        try {
          const ext = issue.extent(g)
          loc = ext ? (ext.center() as LngLatTuple) : null
        } catch {
          loc = null
        }
      }
      let message = ''
      try {
        message = idText(typeof issue.message === 'function' ? issue.message(this.context) : issue.message)
      } catch {
        message = issue.type
      }
      out.push({
        id: issue.id,
        type: issue.type,
        subtype: issue.subtype ?? null,
        severity: issue.severity,
        message: message || issue.type,
        entityIds: [...(issue.entityIds ?? [])],
        loc,
        fixes: this.issueFixes(issue).map((f: any, i: number) => ({ id: String(i), title: idText(f.title), disabled: f.disabledReason ? String(f.disabledReason) : null })),
      })
    }
    return out
  }

  applyIdFix(issueId: string, fixId: string): boolean {
    const issue = this.validator.getIssues({ what: 'edited', where: 'all', includeIgnored: true }).find((i: any) => i.id === issueId)
    if (!issue) return false
    const fix = this.issueFixes(issue)[Number(fixId)]
    if (!fix || fix.disabledReason || typeof fix.onClick !== 'function') return false
    this.tagEdit = null
    if (issue.entityIds?.length) this.selected = [issue.entityIds[0]]
    this.fixSelection = issue.entityIds?.length ? [issue.entityIds[0]] : null
    try {
      fix.onClick(this.context, () => undefined)
    } finally {
      this.fixSelection = null
    }
    this.pruneSelection()
    return true
  }

  applyTagEdit(id: string, edit: { set?: Record<string, string>; remove?: string[]; replaceAll?: Record<string, string> | null }, annotation: string): boolean {
    const e = this.history.graph().hasEntity(id)
    if (!e) return false
    const tags: Tags = { ...(edit.replaceAll ?? e.tags) }
    for (const k of edit.remove ?? []) delete tags[k]
    Object.assign(tags, edit.set ?? {})
    this.tagEdit = null
    this.history.perform(iD.actionChangeTags(id, tags), annotation)
    return true
  }

  deleteEntities(ids: string[], annotation: string): boolean {
    const g = this.history.graph()
    const present = ids.filter((id) => g.hasEntity(id))
    if (!present.length) return false
    this.tagEdit = null
    this.history.perform(iD.actionDeleteMultiple(present), annotation)
    this.pruneSelection()
    return true
  }

  touchedIds(): string[] {
    return this.history.difference().summary().filter((s: any) => s.changeType !== 'deleted').map((s: any) => s.entity.id)
  }

  private handleEnter(mode: any) {
    const id = mode?.id
    if (id === 'select' && typeof mode.selectedIDs === 'function') {
      this.selected = [...mode.selectedIDs()]
      this.onEnter({ id: 'select', ids: this.selected })
    } else if (id === 'move' || id === 'rotate') {
      this.onEnter({ id, ids: [...this.selected] })
    } else if (id === 'draw-line' || id === 'draw-area') {
      this.onEnter({ id: 'continue', ids: [...this.selected] })
    } else if (id === 'browse') {
      this.selected = []
      this.onEnter({ id: 'browse' })
    }
  }

  setView(view: ViewState) {
    this.view = view
    const p = this.projection
    p.scale(iD.geoZoomToScale(view.zoom + 1)).translate([0, 0])
    const c = p(view.center)
    p.translate([view.width / 2 - c[0], view.height / 2 - c[1]])
  }

  project(loc: LngLatTuple): [number, number] {
    return this.projection(loc)
  }

  unproject(px: [number, number]): LngLatTuple {
    return this.projection.invert(px)
  }

  graph(): IdGraph {
    return this.history.graph()
  }

  base(): IdGraph {
    return this.history.base()
  }

  onChange(fn: (difference: any) => void): () => void {
    const name = `change.ore${Math.random().toString(36).slice(2)}`
    this.history.on(name, fn)
    return () => this.history.on(name, null)
  }

  loadXml(text: string, bounds?: Bounds): number {
    return this.load(parseOsmXml(text), bounds)
  }

  load(entities: any[], bounds?: Bounds): number {
    if (bounds) this.loadedBounds.push(bounds)
    const base = this.history.base()
    const fresh = entities.filter((e) => !base.entities[e.id])
    if (fresh.length) this.history.merge(fresh)
    return fresh.length
  }

  markLoaded(bounds: Bounds) {
    this.loadedBounds.push(bounds)
  }

  isLoaded(loc: LngLatTuple): boolean {
    return this.loadedBounds.some(([[w, s], [e, n]]) => loc[0] >= w && loc[0] <= e && loc[1] >= s && loc[1] <= n)
  }

  loadedBaseIds(): string[] {
    const out: string[] = []
    const entities = this.history.base().entities
    for (const id in entities) if (entities[id]) out.push(id)
    return out
  }

  intersects(bounds: Bounds): IdEntity[] {
    return this.history.intersects(iD.geoExtent(bounds[0], bounds[1]))
  }

  allEntities(): IdEntity[] {
    const g = this.history.graph()
    const out: IdEntity[] = []
    for (const id in g.entities) {
      const e = g.entities[id]
      if (e) out.push(e)
    }
    return out
  }

  editCount(): number {
    return this.history.difference().summary().length
  }

  counts(): OsmChangeCounts {
    const c = this.history.changes()
    return { created: c.created.length, modified: c.modified.length, deleted: c.deleted.length }
  }

  hasChanges(): boolean {
    return this.history.hasChanges()
  }

  canUndo(): boolean {
    return !!this.history.undoAnnotation()
  }

  canRedo(): boolean {
    return !!this.history.redoAnnotation()
  }

  undoLabel(): string | null {
    return this.history.undoAnnotation() ?? null
  }

  redoLabel(): string | null {
    return this.history.redoAnnotation() ?? null
  }

  undo() {
    this.tagEdit = null
    if (this.drawing) {
      this.history.undo()
      this.drawing.steps--
      if (this.drawing.steps <= 0 || !this.history.graph().hasEntity(this.drawing.wayId)) this.drawing = null
    } else {
      this.history.undo()
    }
    this.pruneSelection()
  }

  redo() {
    this.tagEdit = null
    this.history.redo()
    this.pruneSelection()
  }

  private pruneSelection() {
    const g = this.history.graph()
    this.selected = this.selected.filter((id) => g.hasEntity(id))
  }

  osmChange(changesetId = '0'): string {
    const changeset = new iD.osmChangeset({ id: changesetId })
    return JXON.stringify(changeset.osmChangeJXON(this.history.changes()))
  }

  reserveIds(ids: Iterable<string>) {
    const next = iD.osmIdManager.next as Record<string, number>
    for (const id of ids) {
      const type = TYPES[id[0]]
      const n = osmIdOf(id)
      if (type && n < 0 && next[type] >= n) next[type] = n - 1
    }
  }

  reserveIdsFromXml(text: string) {
    this.reserveIds(idsInXml(text))
  }

  private rebuild(entities: { replace: any[]; remove: any[] }, annotation: string) {
    this.history.reset('ore-base')
    this.history.checkpoint('ore-base')
    this.drawing = null
    this.tagEdit = null
    if (!entities.replace.length && !entities.remove.length) return
    this.history.perform((graph: any) => {
      let g = graph
      for (const e of entities.replace) g = g.replace(e)
      for (const e of entities.remove) if (g.hasEntity(e.id)) g = g.remove(e)
      return g
    }, annotation)
  }

  applyOsmChange(text: string, annotation = 'Loaded the draft'): RehydrateReport {
    const doc = parseOsmChange(text)
    const base = this.history.base()
    const missing: string[] = []
    const stale: string[] = []
    const replace: any[] = []
    const remove: any[] = []
    this.reserveIdsFromXml(text)
    for (const e of doc.create) replace.push(e)
    for (const e of doc.modify) {
      const live = base.entities[e.id]
      if (!live) {
        missing.push(e.id)
        continue
      }
      if (e.version && live.version && e.version !== live.version) stale.push(e.id)
      replace.push(e)
    }
    const staleDeletes: any[] = []
    for (const e of doc.delete) {
      const live = base.entities[e.id]
      if (!live) {
        if (osmIdOf(e.id) > 0) missing.push(e.id)
        continue
      }
      if (e.version && live.version && e.version !== live.version) {
        stale.push(e.id)
        staleDeletes.push(live.update({ version: e.version }))
      }
      remove.push(live)
    }
    const known = (id: string) => replace.some((r) => r.id === id) || !!base.entities[id]
    for (const e of replace) {
      if (e.type === 'way') for (const n of e.nodes) if (!known(n)) missing.push(n)
    }
    if (staleDeletes.length) base.rebase(staleDeletes, [base], true)
    this.rebuild({ replace, remove }, annotation)
    return { created: doc.create.length, modified: doc.modify.length, deleted: doc.delete.length, missing: [...new Set(missing)], stale }
  }

  remapIds(remaps: IdRemap[], annotation = 'Saved to the draft') {
    const map = new Map(remaps.filter((r) => r.from !== r.to).map((r) => [r.from, r.to]))
    this.reserveIds(remaps.map((r) => r.to))
    if (!map.size) return
    const rename = (id: string) => map.get(id) ?? id
    const changes = this.history.changes()
    const replace = [...changes.created, ...changes.modified].map((e: any) => {
      const props: Record<string, unknown> = { id: rename(e.id) }
      if (e.type === 'way') props.nodes = e.nodes.map(rename)
      if (e.type === 'relation') props.members = e.members.map((m: any) => ({ ...m, id: rename(m.id) }))
      return e.update(props)
    })
    const sel = this.selected.map(rename)
    this.rebuild({ replace, remove: changes.deleted }, annotation)
    this.selected = sel.filter((id) => this.history.graph().hasEntity(id))
  }

  preset(entity: IdEntity): PresetInfo {
    const g = this.history.graph()
    const p = iD.presetManager.match(entity, g)
    let fields: string[] = []
    let moreFields: string[] = []
    try {
      fields = (p.fields?.() ?? []).map((f: any) => f.id)
      moreFields = (p.moreFields?.() ?? []).map((f: any) => f.id)
    } catch {
      fields = []
    }
    return { id: p.id, name: idText(p.name?.() ?? p.id), icon: p.icon ?? null, fields, moreFields }
  }

  private iconCache = new Map<string, { id: string; icon: string | null }>()

  presetIcon(entity: IdEntity): { id: string; icon: string | null } {
    const g = this.history.graph()
    const key = `${entity.type}|${(entity as any).geometry(g)}|${JSON.stringify(entity.tags)}`
    let hit = this.iconCache.get(key)
    if (!hit) {
      const p = iD.presetManager.match(entity, g)
      hit = { id: p?.id ?? '', icon: p?.icon ?? null }
      this.iconCache.set(key, hit)
    }
    return hit
  }

  snapshot(id: string): EntitySnapshot | null {
    const g = this.history.graph()
    const e = g.hasEntity(id)
    if (!e) return null
    return {
      id,
      kind: e.type,
      osmId: osmIdOf(id),
      isNew: e.isNew(),
      version: e.version ?? null,
      tags: { ...e.tags },
      baseTags: this.history.base().hasEntity(id) ? { ...this.history.base().entity(id).tags } : null,
      geometry: e.geometry(g),
      preset: this.preset(e),
      displayName: e.tags.name ?? null,
      nodeCount: e.type === 'way' ? e.nodes.length : null,
      memberCount: e.type === 'relation' ? e.members.length : null,
    }
  }

  setTags(id: string, tags: Tags) {
    const annotation = idText(iD.t('operations.change_tags.annotation'))
    const action = iD.actionChangeTags(id, tags)
    if (this.tagEdit?.id === id && this.tagEdit.graph === this.history.graph()) this.history.pop()
    this.history.perform(action, annotation)
    this.tagEdit = { id, graph: this.history.graph() }
  }

  operations(ids: string[] = this.selected): OperationInfo[] {
    if (!ids.length) return []
    const out: OperationInfo[] = []
    for (const [id, factory] of OPERATIONS) {
      let op: any
      try {
        op = (iD as Record<string, AnyFn>)[factory](this.context, ids)
        if (!op.available()) continue
        const disabled = op.disabled()
        out.push({
          id,
          title: idText(op.title),
          tooltip: idText(op.tooltip()),
          keys: (op.keys ?? []).map((k: unknown) => idText(k)),
          available: true,
          disabled: disabled || false,
        })
      } catch {
        continue
      }
    }
    return out
  }

  runOperation(id: string, ids: string[] = this.selected): { ok: boolean; reason: string | null } {
    const factory = OPERATIONS.find(([x]) => x === id)?.[1]
    if (!factory || !ids.length) return { ok: false, reason: null }
    const op = (iD as Record<string, AnyFn>)[factory](this.context, ids)
    if (!op.available()) return { ok: false, reason: null }
    const disabled = op.disabled()
    if (disabled) return { ok: false, reason: idText(op.tooltip()) }
    this.tagEdit = null
    this.selected = [...ids]
    op()
    this.pruneSelection()
    return { ok: true, reason: null }
  }

  private resolveNode(target: SnapTarget, graph: any): { graph: any; id: string } {
    if (target.kind === 'node') return { graph, id: target.id }
    const node = new iD.osmNode({ loc: target.loc })
    if (target.kind === 'way') return { graph: iD.actionAddMidpoint({ loc: target.loc, edge: target.edge }, node)(graph), id: node.id }
    return { graph: graph.replace(node), id: node.id }
  }

  addPoint(target: SnapTarget, tags: Tags = {}): string {
    this.tagEdit = null
    if (target.kind === 'node') {
      this.selected = [target.id]
      return target.id
    }
    const node = new iD.osmNode({ loc: target.loc, tags })
    if (target.kind === 'way') {
      this.history.perform(iD.actionAddMidpoint({ loc: target.loc, edge: target.edge }, node), idText(iD.t('operations.add.annotation.vertex')))
    } else {
      this.history.perform(iD.actionAddEntity(node), idText(iD.t('operations.add.annotation.point')))
    }
    this.selected = [node.id]
    return node.id
  }

  startWay(kind: 'line' | 'area', target: SnapTarget): string {
    this.tagEdit = null
    let wayId = ''
    this.history.perform((graph: any) => {
      const r = this.resolveNode(target, graph)
      const way = new iD.osmWay({ tags: kind === 'area' ? { area: 'yes' } : {}, nodes: kind === 'area' ? [r.id, r.id] : [r.id] })
      wayId = way.id
      return r.graph.replace(way)
    }, idText(iD.t(`operations.start.annotation.${kind}`)))
    this.drawing = { wayId, kind, affix: 'suffix', steps: 1 }
    this.selected = [wayId]
    return wayId
  }

  continueWay(wayId: string, nodeId: string): boolean {
    const g = this.history.graph()
    const way = g.hasEntity(wayId)
    if (!way || way.type !== 'way' || way.isClosed()) return false
    const affix = way.first() === nodeId ? 'prefix' : way.last() === nodeId ? 'suffix' : null
    if (!affix) return false
    this.drawing = { wayId, kind: 'line', affix, steps: 0 }
    this.selected = [wayId]
    return true
  }

  drawingTip(): string | null {
    const d = this.drawing
    if (!d) return null
    const way = this.history.graph().hasEntity(d.wayId) as IdWay | undefined
    if (!way) return null
    if (d.kind === 'area') return way.nodes[way.nodes.length - 2] ?? null
    return d.affix === 'prefix' ? way.first() : way.last()
  }

  extendWay(target: SnapTarget): 'added' | 'finished' | 'ignored' {
    const d = this.drawing
    if (!d) return 'ignored'
    const g = this.history.graph()
    const way = g.hasEntity(d.wayId) as IdWay | undefined
    if (!way) {
      this.drawing = null
      return 'ignored'
    }
    const tip = this.drawingTip()
    if (target.kind === 'node') {
      if (target.id === tip) return this.finishWay() ? 'finished' : 'ignored'
      if (d.kind === 'area' && target.id === way.first()) return this.finishWay() ? 'finished' : 'ignored'
    }
    const kindKey = d.kind
    let closing = false
    this.history.perform((graph: any) => {
      const r = this.resolveNode(target, graph)
      const w = r.graph.entity(d.wayId)
      let index: number
      if (d.kind === 'area') index = w.nodes.length - 1
      else if (d.affix === 'prefix') index = 0
      else index = w.nodes.length
      if (d.kind === 'line' && ((d.affix === 'suffix' && r.id === w.first()) || (d.affix === 'prefix' && r.id === w.last()))) closing = true
      return iD.actionAddVertex(d.wayId, r.id, index)(r.graph)
    }, idText(iD.t(`operations.continue.annotation.${kindKey}`)))
    d.steps++
    if (closing) {
      this.finishWay()
      return 'finished'
    }
    return 'added'
  }

  finishWay(): string | null {
    const d = this.drawing
    if (!d) return null
    this.drawing = null
    const way = this.history.graph().hasEntity(d.wayId) as IdWay | undefined
    const distinct = way ? new Set(way.nodes).size : 0
    const valid = way && (d.kind === 'area' ? distinct >= 3 : distinct >= 2)
    if (!valid) {
      if (d.steps > 0) this.history.pop(d.steps)
      this.pruneSelection()
      return null
    }
    this.selected = [d.wayId]
    return d.wayId
  }

  beginDrag(nodeId: string) {
    this.tagEdit = null
    this.history.perform(iD.actionNoop())
    this.drag = { id: nodeId, moved: false, created: false }
  }

  beginMidpointDrag(edge: [string, string], loc: LngLatTuple): string {
    this.tagEdit = null
    const node = new iD.osmNode({ loc })
    this.history.perform(iD.actionAddMidpoint({ loc, edge }, node))
    this.drag = { id: node.id, moved: false, created: true }
    return node.id
  }

  dragging(): string | null {
    return this.drag?.id ?? null
  }

  dragTo(loc: LngLatTuple) {
    if (!this.drag) return
    this.history.replace(iD.actionMoveNode(this.drag.id, loc))
    this.drag.moved = true
  }

  endDrag(target: SnapTarget | null): string | null {
    const d = this.drag
    this.drag = null
    if (!d) return null
    const g = this.history.graph()
    const node = g.hasEntity(d.id)
    if (!node) return null
    if (!d.moved && !d.created) {
      this.history.pop()
      return d.id
    }
    const from = node.geometry(g) === 'vertex' ? 'from_vertex' : 'from_point'
    if (target?.kind === 'node' && target.id !== d.id && g.hasEntity(target.id)) {
      const action = iD.actionConnect([target.id, d.id])
      if (!action.disabled(g)) {
        const to = g.entity(target.id).geometry(g) === 'vertex' ? 'to_vertex' : 'to_point'
        this.history.replace(action, idText(iD.t(`operations.connect.annotation.${from}.${to}`)))
        const survivor = this.history.graph().hasEntity(d.id) ? d.id : target.id
        this.selected = this.selected.filter((id) => this.history.graph().hasEntity(id))
        return survivor
      }
    }
    if (target?.kind === 'way' && !target.edge.includes(d.id)) {
      const parentIds = g.parentWays(node).map((w: any) => w.id)
      if (!parentIds.includes(target.id) || node.geometry(g) === 'point') {
        const to = g.entity(target.id).geometry(g) === 'area' ? 'to_area' : 'to_line'
        this.history.replace(iD.actionAddMidpoint({ loc: target.loc, edge: target.edge }, g.entity(d.id)), idText(iD.t(`operations.connect.annotation.${from}.${to}`)))
        return d.id
      }
    }
    const annotation = d.created ? 'operations.add.annotation.vertex' : `operations.move.annotation.${node.geometry(g) === 'vertex' ? 'vertex' : 'point'}`
    this.history.replace(iD.actionNoop(), idText(iD.t(annotation)))
    return d.id
  }

  cancelDrag() {
    if (!this.drag) return
    this.drag = null
    this.history.pop()
  }

  chooseEdge(wayId: string, point: [number, number], activeId?: string): { loc: LngLatTuple; edge: [string, string]; distance: number } | null {
    const g = this.history.graph()
    const way = g.hasEntity(wayId)
    if (!way || way.type !== 'way') return null
    const nodes = g.childNodes(way)
    const choice = iD.geoChooseEdge(nodes, point, this.projection, activeId)
    if (!choice) return null
    return { loc: choice.loc, edge: [way.nodes[choice.index - 1], way.nodes[choice.index]], distance: choice.distance }
  }

  private allNodeLocs(ids: string[]): LngLatTuple[] {
    const g = this.history.graph()
    const out: LngLatTuple[] = []
    const visit = (id: string, seen: Set<string>) => {
      if (seen.has(id)) return
      seen.add(id)
      const e = g.hasEntity(id)
      if (!e) return
      if (e.type === 'node') out.push(e.loc)
      else if (e.type === 'way') e.nodes.forEach((n: string) => visit(n, seen))
      else e.members.forEach((m: any) => visit(m.id, seen))
    }
    const seen = new Set<string>()
    ids.forEach((id) => visit(id, seen))
    return out
  }

  beginTransform(kind: 'move' | 'rotate', ids: string[], mouseLoc: LngLatTuple) {
    this.tagEdit = null
    const pts = this.allNodeLocs(ids).map((l) => this.projection(l) as [number, number])
    const pivot: [number, number] = pts.length
      ? [pts.reduce((a, p) => a + p[0], 0) / pts.length, pts.reduce((a, p) => a + p[1], 0) / pts.length]
      : [0, 0]
    const m = this.projection(mouseLoc)
    this.transform = { kind, ids, originLoc: mouseLoc, pivot, startAngle: Math.atan2(m[1] - pivot[1], m[0] - pivot[0]), started: false, cache: {} }
  }

  transforming(): 'move' | 'rotate' | null {
    return this.transform?.kind ?? null
  }

  transformTo(mouseLoc: LngLatTuple) {
    const t = this.transform
    if (!t) return
    const m = this.projection(mouseLoc)
    let action: any
    if (t.kind === 'move') {
      const o = this.projection(t.originLoc)
      action = iD.actionMove(t.ids, [m[0] - o[0], m[1] - o[1]], this.projection, t.cache)
    } else {
      const angle = Math.atan2(m[1] - t.pivot[1], m[0] - t.pivot[0]) - t.startAngle
      action = iD.actionRotate(t.ids, t.pivot, angle, this.projection)
    }
    if (t.started) this.history.pop()
    this.history.perform(action)
    t.started = true
  }

  endTransform() {
    const t = this.transform
    this.transform = null
    if (!t || !t.started) return
    const g = this.history.graph()
    const key = t.ids.length === 1 ? g.entity(t.ids[0]).geometry(g) : null
    const annotation =
      t.kind === 'move'
        ? key ? iD.t(`operations.move.annotation.${key}`) : iD.t('operations.move.annotation.feature', { n: t.ids.length })
        : key && key !== 'point' && key !== 'vertex' ? iD.t(`operations.rotate.annotation.${key}`) : iD.t('operations.rotate.annotation.feature', { n: t.ids.length })
    this.history.replace(iD.actionNoop(), idText(annotation))
  }

  cancelTransform() {
    const t = this.transform
    this.transform = null
    if (t?.started) this.history.pop()
  }

  newIds(): string[] {
    return this.history.changes().created.map((e: any) => e.id)
  }

  wayNodes(wayId: string): IdNode[] {
    const g = this.history.graph()
    const w = g.hasEntity(wayId)
    return w && w.type === 'way' ? g.childNodes(w) : []
  }

  draftRefs(): string[] {
    const c = this.history.changes()
    return [...c.created, ...c.modified, ...c.deleted].map((e: any) => e.id)
  }
}

export function createIdAdapter(view?: ViewState): IdAdapter {
  return new IdAdapter(view)
}
