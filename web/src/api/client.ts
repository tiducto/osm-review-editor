import type {
  AccountStatus,
  AppStatus,
  Area,
  Change,
  CreateDraftRequest,
  Draft,
  DraftDetail,
  DraftFromRuleRequest,
  DraftStatus,
  EditorLinks,
  FeatureCollection,
  ImagerySource,
  ManualEditRequest,
  MapFeature,
  McpInfo,
  MergePreview,
  RemoteDecision,
  RemoteRequest,
  ProposalResult,
  ProposeChangesRequest,
  ResolveConflictRequest,
  ReviewRequest,
  Rule,
  RuleIssuesRequest,
  RuleRunResult,
  SearchHit,
  SetTargetRequest,
  UpdateDraftRequest,
  Upload,
  BBox,
  OsmRef,
  OpenChangeElsewhere,
  OsmChangeImportRequest,
  OsmChangeImportResult,
  DraftFromOsmChangeRequest,
} from '@/generated/models'
import { bboxParam } from '@/lib/geo'
import { refString } from '@/lib/osm'
import { t } from '@/i18n'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly error: string,
    readonly detail: string | null = null,
    readonly code: string | null = null,
    readonly openChanges: OpenChangeElsewhere[] | null = null,
  ) {
    super(detail ? `${error}: ${detail}` : error)
    this.name = 'ApiError'
  }
}

type Query = Record<string, string | number | boolean | null | undefined>

function withQuery(path: string, query?: Query): string {
  if (!query) return path
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v))
  }
  const qs = params.toString()
  return qs ? `${path}?${qs}` : path
}

async function send(method: string, path: string, body: unknown, signal: AbortSignal | undefined, accept: string): Promise<string> {
  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? { Accept: accept } : { Accept: accept, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, 'network', t('api.unreachable'))
  }
  const text = await res.text()
  if (!res.ok) {
    let error = res.statusText || `HTTP ${res.status}`
    let detail: string | null = null
    let code: string | null = null
    let openChanges: OpenChangeElsewhere[] | null = null
    try {
      const parsed = JSON.parse(text) as { error?: string; detail?: string | null; code?: string | null; openChanges?: OpenChangeElsewhere[] | null }
      if (parsed.error) error = parsed.error
      detail = parsed.detail ?? null
      code = parsed.code ?? null
      openChanges = parsed.openChanges ?? null
    } catch {
      detail = text || null
    }
    throw new ApiError(res.status, error, detail, code, openChanges)
  }
  return text
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const text = await send(method, path, body, signal, 'application/json')
  return (text ? JSON.parse(text) : undefined) as T
}

const get = <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', withQuery(path, query), undefined, signal)
const post = <T>(path: string, body?: unknown, query?: Query, signal?: AbortSignal) =>
  request<T>('POST', withQuery(path, query), body ?? {}, signal)
const put = <T>(path: string, body: unknown) => request<T>('PUT', path, body)
const patch = <T>(path: string, body: unknown) => request<T>('PATCH', path, body)

const enc = encodeURIComponent

export const api = {
  status: () => get<AppStatus>('/api/status'),
  areas: () => get<Area[]>('/api/areas'),
  area: (name: string) => get<Area>(`/api/areas/${enc(name)}`),
  imagery: () => get<ImagerySource[]>('/api/imagery'),
  search: (q: string, bbox?: BBox | null, signal?: AbortSignal) =>
    get<SearchHit[]>('/api/search', { q, bbox: bbox ? bboxParam(bbox) : undefined }, signal),

  features: (bbox: BBox, limit?: number, signal?: AbortSignal) =>
    get<FeatureCollection>('/api/features', { bbox: bboxParam(bbox), limit }, signal),
  object: (ref: OsmRef, live = false) => get<MapFeature>(`/api/objects/${refString(ref)}`, live ? { live: true } : undefined),
  links: (ref: OsmRef) => get<EditorLinks>(`/api/objects/${refString(ref)}/links`),

  rules: () => get<Rule[]>('/api/rules'),
  issues: (ruleId: string, scope: RuleIssuesRequest, signal?: AbortSignal) => {
    const body: RuleIssuesRequest = scope.area
      ? { area: scope.area, limit: scope.limit }
      : scope.polygon
        ? { polygon: scope.polygon, limit: scope.limit }
        : { bbox: scope.bbox, limit: scope.limit }
    return post<RuleRunResult>(`/api/rules/${enc(ruleId)}/issues`, body, undefined, signal)
  },

  drafts: (filter: { status?: DraftStatus; author?: string } = {}) => get<Draft[]>('/api/drafts', filter),
  createDraft: (req: CreateDraftRequest) => post<Draft>('/api/drafts', req),
  draftFromRule: (req: DraftFromRuleRequest) => post<DraftDetail>('/api/drafts/from-rule', req),
  draft: (id: string) => get<DraftDetail>(`/api/drafts/${enc(id)}`),
  updateDraft: (id: string, req: UpdateDraftRequest) => patch<Draft>(`/api/drafts/${enc(id)}`, req),
  proposeChanges: (id: string, req: ProposeChangesRequest) => post<ProposalResult[]>(`/api/drafts/${enc(id)}/changes`, req),
  refreshDraft: (id: string) => post<DraftDetail>(`/api/drafts/${enc(id)}/refresh`),
  approveRemaining: (id: string, ruleId: string) =>
    post<DraftDetail>(`/api/drafts/${enc(id)}/approve-remaining`, undefined, { ruleId }),
  mergePreview: (id: string) => get<MergePreview>(`/api/drafts/${enc(id)}/merge-preview`),
  merge: (id: string) => post<void>(`/api/drafts/${enc(id)}/merge`),
  uploads: (id: string) => get<Upload[]>(`/api/drafts/${enc(id)}/uploads`),

  review: (changeId: string, req: ReviewRequest) => post<Change>(`/api/changes/${enc(changeId)}/review`, req),
  remotePending: () => get<RemoteRequest[]>('/api/remote/pending'),
  remoteDecide: (id: string, req: RemoteDecision) => post<void>(`/api/remote/pending/${enc(id)}`, req),
  resolve: (changeId: string, req: ResolveConflictRequest) => post<Change>(`/api/changes/${enc(changeId)}/resolve`, req),
  manualEdit: (req: ManualEditRequest) => post<Change>('/api/manual-edits', req),

  account: () => get<AccountStatus>('/api/account'),
  setTarget: (req: SetTargetRequest) => put<AccountStatus>('/api/settings/target', req),
  logout: () => post<AccountStatus>('/api/account/logout'),
  mcpInfo: () => get<McpInfo>('/api/settings/mcp'),
  imagerySettings: () => get<ImagerySource[]>('/api/settings/imagery'),
  saveImagerySettings: (list: ImagerySource[]) => put<ImagerySource[]>('/api/settings/imagery', list),
  resetImagerySettings: () => post<ImagerySource[]>('/api/settings/imagery/reset'),

  osmMap: (bbox: BBox, signal?: AbortSignal) => send('GET', withQuery('/api/osm/map', { bbox: bboxParam(bbox) }), undefined, signal, 'application/xml'),
  draftOsmChange: (id: string) => send('GET', `/api/drafts/${enc(id)}/osmchange`, undefined, undefined, 'application/xml'),
  importOsmChange: (id: string, req: OsmChangeImportRequest) => post<OsmChangeImportResult>(`/api/drafts/${enc(id)}/osmchange`, req),
  draftFromOsmChange: (req: DraftFromOsmChangeRequest) => post<OsmChangeImportResult>('/api/drafts/from-osmchange', req),
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.detail ?? e.error
  if (e instanceof Error) return e.message
  return String(e)
}

export function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}

export { request as apiRequest, withQuery }
