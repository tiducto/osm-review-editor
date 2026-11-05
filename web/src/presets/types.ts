export type Locale = 'en' | 'cs'

export type Geometry = 'point' | 'vertex' | 'line' | 'area' | 'relation'

export type Tags = Record<string, string>

export type TagValue = string | string[] | undefined

export type FieldTags = Record<string, TagValue>

export type TagPatch = Record<string, string | undefined>

export interface OptionLabel {
  title: string
  description?: string
}

export interface PrerequisiteTag {
  key?: string
  keyNot?: string
  value?: string
  valueNot?: string
  values?: string[]
  valuesNot?: string[]
}

export interface FieldDef {
  id: string
  type: string
  key?: string
  keys?: string[]
  label: string
  placeholder?: string
  placeholders?: Record<string, string>
  labels?: Record<string, string>
  types?: Record<string, string>
  options?: string[]
  optionLabels: Record<string, OptionLabel>
  terms: string[]
  default?: string
  universal?: boolean
  geometry?: Geometry[]
  prerequisiteTag?: PrerequisiteTag
  snake_case?: boolean
  caseSensitive?: boolean
  customValues?: boolean
  autoSuggestions?: boolean
  minValue?: number
  maxValue?: number
  increment?: number
  pattern?: string
  urlFormat?: string
  icons?: Record<string, string>
  reference?: { key?: string; value?: string }
}

export type PresetOrigin = 'id' | 'user'

export interface PresetDef {
  id: string
  origin: PresetOrigin
  name: string
  names: Partial<Record<Locale, string>>
  terms: string[]
  aliases: string[]
  geometry: Geometry[]
  tags: Tags
  addTags: Tags
  removeTags: Tags
  fields: string[]
  moreFields: string[]
  icon: string | null
  matchScore: number
  searchable: boolean
  extends: string | null
  apply: 'merge' | 'replace' | 'create'
  file: string | null
}

export interface CategoryDef {
  id: string
  name: string
  icon: string | null
  members: string[]
}
