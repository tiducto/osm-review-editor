import type {
  BBox,
  CoverageStats,
  DeleteRuleFileResult,
  HighlightEvaluation,
  HighlightFile,
  HighlightList,
  HighlightStatsRequest,
  JsonValue,
  RuleFileContent,
  RuleFileList,
  RuleKind,
  RunScanRequest,
  Scan,
  ScanDiff,
  ScanDraftRequest,
  ScanDraftResult,
  ScanReport,
  ValidateRuleFileResult,
  WriteRuleFileResult,
} from '@/generated/models'
import { apiRequest, withQuery } from './client'
import { bboxParam } from '@/lib/geo'

export interface ScanFilter {
  severity?: string
  group?: string
  rule?: string
  limit?: number
  offset?: number
}

export interface ScopeBody {
  area?: string | null
  bbox?: BBox | null
  polygon?: JsonValue | null
}

const filePath = (path: string) => path.split('/').map(encodeURIComponent).join('/')

export const rulesApi = {
  files: (kind?: RuleKind) => apiRequest<RuleFileList>('GET', withQuery('/api/rule-files', { kind })),
  read: (path: string) => apiRequest<RuleFileContent>('GET', `/api/rule-files/${filePath(path)}`),
  create: (path: string, content: string, force = false) => apiRequest<WriteRuleFileResult>('POST', '/api/rule-files', { path, content, force }),
  write: (path: string, content: string, expectedHash: string | null, force = false) =>
    apiRequest<WriteRuleFileResult>('PUT', `/api/rule-files/${filePath(path)}`, { content, expectedHash, force }),
  validate: (path: string, content: string, signal?: AbortSignal) =>
    apiRequest<ValidateRuleFileResult>('POST', '/api/rule-files/validate', { path, content }, signal),
  remove: (path: string) => apiRequest<DeleteRuleFileResult>('DELETE', `/api/rule-files/${filePath(path)}`),
  copy: (path: string, to?: string) => apiRequest<WriteRuleFileResult>('POST', '/api/rule-files/copy', { path, to: to ?? null }),

  highlights: () => apiRequest<HighlightList>('GET', '/api/highlights'),
  highlight: (path: string) => apiRequest<HighlightFile>('GET', withQuery('/api/highlights/file', { path })),
  evaluateTile: (path: string, tile: string, signal?: AbortSignal) =>
    apiRequest<HighlightEvaluation>('GET', withQuery('/api/highlights/evaluate', { path, tile }), undefined, signal),
  evaluateBBox: (path: string, bbox: BBox, signal?: AbortSignal) =>
    apiRequest<HighlightEvaluation>('GET', withQuery('/api/highlights/evaluate', { path, bbox: bboxParam(bbox) }), undefined, signal),
  stats: (req: HighlightStatsRequest, signal?: AbortSignal) => apiRequest<CoverageStats>('POST', '/api/highlights/stats', req, signal),

  runScan: (req: RunScanRequest, signal?: AbortSignal) => apiRequest<Scan>('POST', '/api/scans', req, signal),
  scans: (scope?: string, limit = 30) => apiRequest<Scan[]>('GET', withQuery('/api/scans', { scope, limit })),
  scan: (id: string, filter: ScanFilter = {}) => apiRequest<ScanReport>('GET', withQuery(`/api/scans/${encodeURIComponent(id)}`, { ...filter })),
  scanGeoJsonUrl: (id: string, filter: ScanFilter = {}) => withQuery(`/api/scans/${encodeURIComponent(id)}/geojson`, { ...filter }),
  diff: (id: string, against?: string, limit = 2000) =>
    apiRequest<ScanDiff>('GET', withQuery(`/api/scans/${encodeURIComponent(id)}/diff`, { against, limit })),
  draftFromScan: (id: string, req: ScanDraftRequest) => apiRequest<ScanDraftResult>('POST', `/api/scans/${encodeURIComponent(id)}/draft`, req),
}
