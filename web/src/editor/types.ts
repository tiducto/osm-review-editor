export type LngLatTuple = [number, number]
export type Bounds = [LngLatTuple, LngLatTuple]
export type Tags = Record<string, string>
export type EntityKind = 'node' | 'way' | 'relation'
export type EntityGeometry = 'point' | 'vertex' | 'line' | 'area' | 'relation'

export interface IdNode {
  id: string
  type: 'node'
  loc: LngLatTuple
  tags: Tags
  version?: string
}

export interface IdWay {
  id: string
  type: 'way'
  nodes: string[]
  tags: Tags
  version?: string
  isClosed(): boolean
  isArea(): boolean
  first(): string
  last(): string
}

export interface IdRelationMember {
  id: string
  type: EntityKind
  role: string
}

export interface IdRelation {
  id: string
  type: 'relation'
  members: IdRelationMember[]
  tags: Tags
  version?: string
  isMultipolygon(): boolean
  isComplete(graph: IdGraph): boolean
  multipolygon(graph: IdGraph): LngLatTuple[][][]
}

export type IdEntity = IdNode | IdWay | IdRelation

export interface IdGraph {
  entity(id: string): IdEntity
  hasEntity(id: string): IdEntity | undefined
  parentWays(entity: IdEntity): IdWay[]
  parentRelations(entity: IdEntity): IdRelation[]
  childNodes(way: IdWay): IdNode[]
  geometry(id: string): EntityGeometry
}

export interface PresetInfo {
  id: string
  name: string
  icon: string | null
  fields: string[]
  moreFields: string[]
}

export interface EntitySnapshot {
  id: string
  kind: EntityKind
  osmId: number
  isNew: boolean
  version: string | null
  tags: Tags
  baseTags: Tags | null
  geometry: EntityGeometry
  preset: PresetInfo
  displayName: string | null
  nodeCount: number | null
  memberCount: number | null
}

export interface OperationInfo {
  id: string
  title: string
  tooltip: string
  keys: string[]
  available: boolean
  disabled: string | false
}

export interface OsmChangeCounts {
  created: number
  modified: number
  deleted: number
}

export type SnapTarget =
  | { kind: 'node'; id: string; loc: LngLatTuple }
  | { kind: 'way'; id: string; loc: LngLatTuple; edge: [string, string] }
  | { kind: 'none'; loc: LngLatTuple }

export interface ViewState {
  center: LngLatTuple
  zoom: number
  width: number
  height: number
  bounds: Bounds
}

export type EnteredMode =
  | { id: 'select'; ids: string[] }
  | { id: 'browse' }
  | { id: 'move' | 'rotate' | 'continue'; ids: string[] }

export interface IdRemap {
  from: string
  to: string
}

export interface RehydrateReport {
  created: number
  modified: number
  deleted: number
  missing: string[]
  stale: string[]
}
