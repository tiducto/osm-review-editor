# REST and WebSocket API

The Ktor server on `:3000` serves the UI, this API, the MCP endpoint (`/mcp`) and OAuth (`/auth/*`).
All bodies are JSON in the shapes of `shared/` (`@Serializable` Kotlin), mirrored as TypeScript in
`web/src/generated/models.ts` (regenerate with `./gradlew :shared:generateTsModels`).

Responses always carry every field (defaults are encoded). Errors are `ApiError` (`{error, detail}`)
with 400 (bad input), 404 (unknown id), 409 (state conflict, e.g. merging with conflicts), 503 (no
GOL open yet).

Object refs are `{type: 'node'|'way'|'relation', id}` in bodies and JOSM-style strings (`n123`,
`w45`, `r6`) in paths. Bboxes in query strings are `west,south,east,north` (WGS84).

## Status, areas, imagery

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/status` | `AppStatus` (version, mirror, account, MCP URL, remote-control port); `mirror.sidecar` is the gol sidecar's `SidecarStatus`, `null` without `/data/status.json` |
| GET | `/api/areas` | `Area[]` presets from `DEFAULT_AREAS` and the built-in names (geometry omitted) |
| GET | `/api/areas/{name}` | `Area` with GeoJSON geometry (preset polygon from `DEFAULT_AREAS`, else admin boundary from the mirror) |
| GET | `/api/imagery` | `ImagerySource[]` for the map: the configured list plus Bing when `BING_KEY` is set |
| GET | `/api/search?q=` | `SearchHit[]`: area names, OSM IDs (`w123`, `node/5`, osm.org URLs), names, or a GOQL tag query (`w[highway=footway][!surface]`, scoped by `&bbox=`) |

## Mirror (local GOL copy)

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/features?bbox=&limit=` | `FeatureCollection` of mirror objects in the bbox (nodes with tags, ways, area relations). Refused for bboxes larger than about zoom 14. Default limit 5000. |
| GET | `/api/objects/{ref}` | `MapFeature` from the mirror (no version) |
| GET | `/api/objects/{ref}?live=true` | `MapFeature` from the live OSM API (with version) |
| GET | `/api/objects/{ref}/links` | `EditorLinks` (iD, osm.org, history) for the current API target |

## Rules

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/rules` | `Rule[]` |
| GET | `/api/rules/{id}/issues?area=` or `?bbox=` `&limit=` | `RuleRunResult` |

## Drafts and review

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| GET | `/api/drafts?status=&author=` | | `Draft[]`, newest first |
| POST | `/api/drafts` | `CreateDraftRequest` | `Draft` (author `user`) |
| POST | `/api/drafts/from-rule` | `DraftFromRuleRequest` | `DraftDetail` (author `rule:<id>`; every issue with a fix becomes a change, refetched live) |
| GET | `/api/drafts/{id}` | | `DraftDetail` |
| PATCH | `/api/drafts/{id}` | `UpdateDraftRequest` | `Draft` |
| POST | `/api/drafts/{id}/changes` | `ProposeChangesRequest` | `ProposalResult[]` |
| POST | `/api/drafts/{id}/refresh` | | `DraftDetail` (refetches live versions; marks conflicts, auto-rebases where possible) |
| POST | `/api/drafts/{id}/approve-remaining?ruleId=` | | `DraftDetail` (approves pending changes with `confidence=deterministic` only) |
| GET | `/api/drafts/{id}/merge-preview` | | `MergePreview` (changeset groups and comments, conflicts) |
| POST | `/api/drafts/{id}/merge` | | `202`; progress arrives as `merge_progress` events |
| GET | `/api/drafts/{id}/uploads` | | `Upload[]` |
| POST | `/api/changes/{id}/review` | `ReviewRequest` | `Change` (`approve`, `edit` with full `tags`, `reject` with `comment`, `send_to_id`, `reset`) |
| POST | `/api/changes/{id}/resolve` | `ResolveConflictRequest` | `Change` (final tags after a three-way pick; back to pending on the live version) |
| POST | `/api/manual-edits` | `ManualEditRequest` | `Change` in the open manual draft (created on demand) |

## Account and settings

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/account` | `AccountStatus` for the current target |
| PUT | `/api/settings/target` | `SetTargetRequest` (`confirm: true` required for `prod`) → `AccountStatus` |
| POST | `/api/account/logout` | `AccountStatus` |
| GET | `/api/settings/imagery` | `ImagerySource[]` stored in settings (key `imagery`, seeded with the defaults; Bing is never stored) |
| PUT | `/api/settings/imagery` | `ImagerySource[]` replaces the whole list → the stored `ImagerySource[]`; 400 listing every problem |
| POST | `/api/settings/imagery/reset` | `ImagerySource[]` defaults (ČÚZK orthophoto, ČÚZK cadastral map, Esri World Imagery) |
| GET | `/auth/login` | Redirect to the OSM OAuth 2.0 authorize page (PKCE) |
| GET | `/auth/callback` | OAuth redirect target; stores the token, redirects to `/` |

### Imagery sources

`ImagerySource` is `{id, name, type, url, attribution, maxZoom, sourceTag}`. `sourceTag` is the value
for the changeset `source` tag when the layer was used (`ČÚZK ortofoto`, `Esri World Imagery`, `Bing`).
`PUT /api/settings/imagery` trims fields and rejects the list when:

- an `id` is not 1-64 characters of `a-z 0-9 . _ -`, or two sources share one
- `name` or `attribution` is blank
- `type` is not `tms` or `wms`
- `url` is not an absolute `http(s)` URL; a `tms` URL needs `{z}`, `{x}`, `{y}` or `{quadkey}`, a
  `wms` URL needs `{bbox-epsg-3857}` (the MapLibre placeholders)
- `maxZoom` is outside 1-24, or `sourceTag` is longer than 255 characters (the OSM tag limit)

### Sidecar status

`SidecarStatus` mirrors `/data/status.json`, which the gol sidecar rewrites atomically after every
build or update: `lastRunAt`, `lastRunKind` (`build` or `update`), `result` (`ok` or `failed`;
`unknown` when the file cannot be parsed), `error`, `lastFullBuildAt`, `nextFullBuildDue`,
`replicationSequence` and `replicationTimestamp` (the GOL's revision as `gol info` reports it) and
`current` (the published GOL file). Every field is optional.

## WebSocket `/api/events`

Server → UI messages, one `UiEvent` JSON per frame, discriminated by `type`:

- `remote_command`: a JOSM remote-control request (`load_and_zoom`, `zoom`, `load_object`, `import`,
  `add_node`, `imagery`). Fly to `bbox`, select `select`, open `draftId` if set, add `imagery`.
  Queued while no tab is connected and replayed when one connects.
- `draft_updated`: a draft changed (e.g. Claude proposed changes over MCP); refetch it.
- `merge_progress`: upload progress for a draft.
- `mirror_swapped`: the GOL was updated and swapped; refetch rule layers.
