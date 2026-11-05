# REST and WebSocket API

The Ktor server on `:3000` serves the UI, this API, the MCP endpoint (`/mcp`) and OAuth (`/auth/*`).
All bodies are JSON in the shapes of `shared/` (`@Serializable` Kotlin), mirrored as TypeScript in
`web/src/generated/models.ts` (regenerate with `./gradlew :shared:generateTsModels`).

Responses always carry every field (defaults are encoded). Errors are `ApiError` (`{error, detail}`)
with 400 (bad input), 401 (no OSM account connected), 404 (unknown id), 409 (state conflict, e.g.
reviewing an uploaded change, merging a closed draft or with nothing approved, another merge
running), 502 (OSM API error), 503 (no GOL open yet). The remote-control port `:8111` answers
errors as `text/plain` instead, like JOSM.

Object refs are `{type: 'node'|'way'|'relation', id}` in bodies and JOSM-style strings (`n123`,
`w45`, `r6`) in paths. Bboxes in query strings are `west,south,east,north` (WGS84).

## Scopes

Endpoints that run rules take exactly one of:

- `area`: a preset or admin boundary name. Presets with a geometry in `DEFAULT_AREAS` are matched
  first (case-insensitive), then the mirror's admin boundaries and places. When the area has a
  polygon (admin boundaries, presets with geometry) rules are clipped to it, not just to its bbox.
- `bbox`: `west,south,east,north` in query strings, `{west, south, east, north}` in bodies.
- `polygon`: a GeoJSON `Polygon` or `MultiPolygon` in WGS84 `[lon, lat]` (a bare geometry, a
  `Feature`, or a `FeatureCollection` with one feature). In query strings it is URL-encoded JSON; use
  the POST form for large polygons. Rings must be closed with at least 4 positions, the geometry must be
  valid (no self-intersections), at most 20,000 vertices and at most 10° across in each direction.
  Anything else is a 400 with the reason.

Giving more than one is a 400. Rule results are cached per rule, scope key and mirror generation;
the scope key is `area:<name>#<hash>`, `bbox:<w>,<s>,<e>,<n>` or `polygon:<hash>`, where the hash is
a SHA-256 prefix of the normalized geometry (independent of ring start, orientation and JSON layout).
Each run is recorded in `app.rule_runs` with its scope key and scope geometry (simplified to at most
2,000 points).

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
| GET | `/api/rules/{id}/issues?area=` or `?bbox=` or `?polygon=` `&limit=` | `RuleRunResult` |
| POST | `/api/rules/{id}/issues` with `RuleIssuesRequest` `{area?, bbox?, polygon?, limit?}` | `RuleRunResult` |

## Drafts and review

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| GET | `/api/drafts?status=&author=` | | `Draft[]`, newest first |
| POST | `/api/drafts` | `CreateDraftRequest` | `Draft` (author `user`) |
| POST | `/api/drafts/from-rule` | `DraftFromRuleRequest` `{ruleId, area?, bbox?, polygon?, title?, limit?}` | `DraftDetail` (author `rule:<id>`; every issue with a fix becomes a change, refetched live; the draft's `scope` is the scope geometry) |
| GET | `/api/drafts/{id}` | | `DraftDetail` |
| PATCH | `/api/drafts/{id}` | `UpdateDraftRequest` `{title?, description?, status?, changesetComment?, changesetSource?}` | `Draft` (omitted fields are kept; `status` only `open` or `closed`) |
| POST | `/api/drafts/{id}/changes` | `ProposeChangesRequest` | `ProposalResult[]` |
| POST | `/api/drafts/{id}/refresh` | | `DraftDetail` (refetches live versions; marks conflicts, auto-rebases where possible) |
| POST | `/api/drafts/{id}/approve-remaining?ruleId=` | | `DraftDetail` (approves pending changes with `confidence=deterministic` only) |
| GET | `/api/drafts/{id}/merge-preview` | | `MergePreview` (changeset groups with comment and tags, ids of changes in conflict) |
| POST | `/api/drafts/{id}/merge` | | `202`; progress arrives as `merge_progress` events |
| GET | `/api/drafts/{id}/uploads` | | `Upload[]` |
| POST | `/api/changes/{id}/review` | `ReviewRequest` | `Change` (`approve`, `edit` with full `tags`, `reject` with `comment`, `send_to_id`, `reset`) |
| POST | `/api/changes/{id}/resolve` | `ResolveConflictRequest` | `Change` (final tags after a three-way pick; back to pending on the live version) |
| POST | `/api/manual-edits` | `ManualEditRequest` | `Change` in the open manual draft (created on demand) |

### Changeset metadata

`CreateDraftRequest` takes optional `area`, `changesetComment` and `changesetSource`; `Draft` returns
them. Drafts from rules and MCP `create_draft` store the resolved area name in `area` (the preset or
boundary name as found, without the scope hash) and `null` for bbox and polygon scopes. JOSM remote
control stores `changeset_comment` and `changeset_source` there.

`PATCH /api/drafts/{id}` edits `changesetComment` and `changesetSource` after creation. Values are
trimmed; an empty string clears the field, so the merge falls back to the generated comment or to
the review imagery alone.

`ReviewRequest.imagery` is the attribution string of the imagery the reviewer had on (for example
`Esri World Imagery`). It is stored on the `Review` as `imagery`.

The merge splits approved changes into changesets by rule (`manual` when none) and draft area, up to
500 changes each. Each changeset carries:

- `comment`: the draft's `changesetComment` when set, else `<area>: <rule comment> (rule <id>),
  reviewed individually` (or the draft title for manual changes); the `<area>: ` prefix is left out
  when the draft has no area, as for bbox and polygon scopes.
- `source`: the distinct non-blank values of the draft's `changesetSource` and the `imagery` of the
  `approve` and `edit` reviews of the changes in that changeset, joined with `; `. Omitted when empty.
- `created_by`.

The merge preview shows these tags per group.

### Merge behaviour

Merging uploads only `approved` changes. Changes in conflict are skipped, never a 409: they are
listed in `MergePreview.conflicts` and stay in the draft. Before upload every approved change is
checked against the live version; changes that moved upstream are auto-rebased back to `pending`
or marked `conflict`, and are skipped too. When the API rejects a changeset with 409 or 412, its
changes are checked against the live versions again and the merge goes on with the next changeset.

After a merge the draft is `merged` when at least one change was uploaded and none is pending,
approved or in conflict (rejected changes don't count), `partially_merged` while any remain, and
keeps its status when nothing was uploaded.

### Manual edits on the live base

`ManualEditRequest` takes `ref`, the full `tags` and optional `baseTags`, exactly the tags the
inspector showed when the edit started: the proposed `newTags` of the object's active change if it
has one, the live tags otherwise.

If the object has an active change (pending, approved or conflict in an open or partially merged
draft) and `baseTags` equals that change's current `newTags`, the difference between `baseTags` and
`tags` is applied to those `newTags`. Removing a key the earlier edit added removes it. The change
keeps its `baseVersion` and `baseTags`, goes back to `pending` (a `conflict` stays one) and gets a
`reset` review entry. No live check is made, so no conflict is reported. If the result equals the
change's `baseTags`, the change becomes `rejected` with a `reject` review entry, like an edit back to
the base in review.

Otherwise, with `baseTags`, only the difference between `baseTags` and `tags` is applied to the live
tags (or merged into the active change), so edits made upstream in the meantime are kept. If the
live object changed a key the edit touches (to a value other than the one being set), the change is
stored as `conflict` with `liveTags` and `liveVersion`, ready for `/api/changes/{id}/resolve`.
Without `baseTags`, `tags` replace the live tags. A `baseTags` equal to `tags` is a 400.

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
  `load_data`, `add_node`, `imagery`). Fly to `bbox`, select `select`, open `draftId` if set, add
  `imagery`. `draftIds` lists every draft the command created (one per object for `addtags`;
  `draftId` is the first). `message` reports what an import skipped. Queued while no tab is
  connected and replayed when one connects.
- `draft_updated`: a draft changed (e.g. Claude proposed changes over MCP); refetch it.
- `merge_progress`: upload progress for a draft.
- `mirror_swapped`: the GOL was updated and swapped; refetch rule layers.
