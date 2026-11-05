import type { PresetApplyResult, ResolvedPreset, SmartPreset, WriteRuleFileResult } from '@/generated/models'
import { apiRequest, withQuery } from './client'
import type { Locale } from '@/presets/types'

export const SCHEMA_BASE = '/api/presets/id-schema/'

const presetPath = (id: string) => id.split('/').map(encodeURIComponent).join('/')

export const presetsApi = {
  schemaFile: <T>(file: string) => apiRequest<T>('GET', SCHEMA_BASE + file),
  user: (locale: Locale) => apiRequest<ResolvedPreset[]>('GET', withQuery('/api/presets/user', { locale })),
  get: (id: string, locale: Locale) => apiRequest<ResolvedPreset>('GET', withQuery(`/api/presets/${presetPath(id)}`, { locale })),
  smart: () => apiRequest<SmartPreset[]>('GET', '/api/presets/smart'),
  apply: (presetId: string, tags: Record<string, string>, geometry: string | null, fromPresetId: string | null) =>
    apiRequest<PresetApplyResult>('POST', '/api/presets/apply', { presetId, tags, geometry, fromPresetId }),
  importOverlay: (presetId: string, id?: string | null, path?: string | null) =>
    apiRequest<WriteRuleFileResult>('POST', '/api/presets/import', { presetId, id: id ?? null, path: path ?? null }),
  detach: (id: string) => apiRequest<WriteRuleFileResult>('POST', '/api/presets/detach', { id }),
  bulk: <Req, Res>(req: Req) => apiRequest<Res>('POST', '/api/presets/apply/bulk', req),
}
