# REST and WebSocket API

The Ktor server on `:3000` serves the UI, this API, the WebSocket (`/api/events`), the [MCP endpoint](#mcp-mcp)
(`/mcp`) and OAuth (`/auth/*`); `:8111` answers [JOSM remote control](#josm-remote-control-8111).
All bodies are JSON in the shapes of `shared/` (`@Serializable` Kotlin), mirrored as TypeScript in
`web/src/generated/models.ts` (regenerate with `./gradlew :shared:generateTsModels`).

Responses always carry every field (defaults are encoded). Errors are `ApiError` (`{error, detail, code, openChanges}`;
`code` is set for errors a client can act on, see below) with 400 (bad input), 401 (no OSM account
connected), 403 (see below), 404 (unknown id), 409 (state conflict, e.g. reviewing an uploaded change,
merging a closed draft or with nothing approved, another merge running), 413 (live data area too
large), 422 (a rules file with errors was not written), 429 (OSM rate limit), 502 (OSM API error),
503 (no GOL open yet). Unknown `/api/...` paths are a 404 `ApiError`. The remote-control port `:8111`
answers errors as `text/plain` instead, like JOSM.

Everything on `:3000` (UI, API, WebSocket, MCP, OAuth) only answers requests whose `Host` is
`localhost`, `127.0.0.1`, `[::1]` or the host of `PUBLIC_URL`; other hosts get a 403 asking to set
`PUBLIC_URL`. Requests other than `GET` and `HEAD` that carry an `Origin` header from another host
are refused with a 403 (no cross-site writes).

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
| GET | `/api/settings/mcp` | `McpInfo` `{url, token, claudeCodeCommand}`: the MCP endpoint, its bearer token and the `claude mcp add` command shown in Settings |
| GET | `/api/areas` | `Area[]` presets from `DEFAULT_AREAS` and the built-in names (geometry omitted) |
| GET | `/api/areas/{name}` | `Area` with GeoJSON geometry (preset polygon from `DEFAULT_AREAS`, else admin boundary from the mirror) |
| GET | `/api/imagery` | `ImagerySource[]` for the map: the configured list plus Bing when `BING_KEY` is set |
| GET | `/api/search?q=` | `SearchHit[]`: area names, OSM IDs (`w123`, `node/5`, osm.org URLs), names, or a GOQL tag query (`w[highway=footway][!surface]`, scoped by `&bbox=`) |

## Mirror (local GOL copy)

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/features?bbox=&limit=` | `FeatureCollection` of mirror objects in the bbox (nodes with tags, ways, area relations). Refused for bboxes larger than about zoom 14. Default limit 5000, at most 20,000. |
| GET | `/api/objects/{ref}` | `MapFeature` from the mirror (no version) |
| GET | `/api/objects/{ref}?live=true` | `MapFeature` from the live OSM API (with version) |
| GET | `/api/objects/{ref}/links` | `EditorLinks` (iD, osm.org, history) for the current API target |

### Live OSM

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/osm/map?bbox=west,south,east,north` | `application/xml`: the target API's (dev or prod, as for uploads) `GET /api/0.6/map` for the bbox, read-only, no login needed, sent with the server's User-Agent |

The bbox must be four finite numbers with west < east and south < north inside ±180/±90, else
`400`. An area over 0.25 square degrees or a response over 25 MB is `413` with `code`
`area_too_large` ("zoom in"). Errors from OSM carry `code` `osm_api_error` and OSM's message in
`detail`: its `400` (e.g. "You requested too many nodes (limit is 50000)") is `400`, `429` is `429`,
`509` is `503`, anything else `502`. An unreachable API is `502`.

### Vector tiles

`GET /api/tiles/{z}/{x}/{y}.mvt` serves Mapbox Vector Tiles (spec v2, 512 px tiles) cut from the mirror
for the map. Tiles below z9 are empty; above z16 clients overzoom z16. Responses are gzip-encoded when
the client accepts it and carry an `ETag` of the tile schema, mirror generation and tile
(`"t3-g4-16-36025-22243"`) with `Cache-Control: no-cache`, so a mirror swap invalidates them;
`If-None-Match` answers 304. Built tiles are kept in memory per mirror generation (192 MB LRU), and a
`Server-Timing` header reports the build time. A z16 tile in central Opava builds in about 25 ms (z15
about 75 ms) on Apple Silicon.

Content per zoom (iD's zoom is one higher, since iD uses 256 px tiles):

| Zoom | Content | Tags |
| --- | --- | --- |
| 15+ | Every object: lines, areas, tagged nodes, way nodes as vertices | All |
| 14 | Highways, railways, waterways, barriers, power lines, aerialways, all areas incl. buildings, POI nodes | Curated |
| 13 | Highways, railways, waterways, all areas incl. buildings, places | Curated |
| 12 | Roads down to residential, track and service, rail, rivers and streams, landuse/natural/leisure/amenity areas, places | Curated |
| 11 | Roads down to tertiary, rail, rivers, landuse/natural/leisure/amenity areas, places | Curated |
| 9-10 | Motorway to primary, rail, rivers, water and forest, cities and towns | Curated |

Curated tags are those the style needs (`highway`, `landuse`, `name`, `surface`, `bridge`, ...).
`geodesk:` pseudo-tags are never included. Boundary, route and site relations are left out; other
area relations are assembled once per generation and cached. Geometry is clipped to the tile plus a
buffer (16 px at z15+, 8 px below); below z15 it is simplified (Douglas-Peucker, 0.5 units at extent
4096) and areas under a few pixels are dropped. z15+ tiles use extent 8192.

`?keys=k1,k2` (at most 64 keys besides the curated ones) adds those tags to tiles below z15, so
highlight filters that test other keys (`sidewalk`, `sidewalk:both`, ...) work when zoomed out; the
`ETag` and the server cache are per key set. z15+ tiles carry every tag anyway and ignore it.

Layers (feature ids are `osmId * 4 + type`, with type 0 node, 1 way, 2 relation; every feature has
`@id` and `@type`):

| Layer | Geometry | Extra properties |
| --- | --- | --- |
| `area` | Polygon | `@fz`: the highest zoom at which the area is filled whole (its `2·area/perimeter` is at most iD's 30 px partial-fill band) |
| `band` | Polygon | `@bz`: display zoom; the area minus its 30 px inset at that zoom, for iD's partial fill above `@fz` (z15 tiles carry bands for 15-20) |
| `label` | Point | Label point (centroid, or the centre of the largest inscribed circle when the centroid falls outside); `@area` and `@width` in z0 pixels |
| `line` | LineString | Untagged ways that belong to a relation are left out; `@closed` on closed ways |
| `point` | Point | Tagged nodes that are not way nodes |
| `vertex` | Point (z15+) | Tagged way nodes, plus untagged way nodes that end a way or join two ways at least one of which is a tagged line (iD's rule); `@junction` (2+ parent ways), `@end` (end of a single way), `@bonly` (only buildings use it) |

Junctions come from way membership: every way that intersects the tile buffer is walked node by
node, so a node's parent count is exact for nodes inside the tile.

## Rules

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/rules` | `Rule[]` |
| GET | `/api/rules/{id}/issues?area=` or `?bbox=` or `?polygon=` `&limit=` | `RuleRunResult` |
| POST | `/api/rules/{id}/issues` with `RuleIssuesRequest` `{area?, bbox?, polygon?, limit?}` | `RuleRunResult` |

These are the built-in Kotlin rules (`deprecated-tags`, `platform-node-only`, `sidewalk-separate`).
MapCSS check files run through [validation scans](#validation-scans), and over MCP with `run_rule`
and the file path as the rule id.

## Drafts and review

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| GET | `/api/drafts?status=&author=` | | `Draft[]`, newest first |
| POST | `/api/drafts` | `CreateDraftRequest` `{title, description?, scope?, area?, changesetComment?, changesetSource?}` | `201` `Draft` (author `user`) |
| POST | `/api/drafts/from-rule` | `DraftFromRuleRequest` `{ruleId, area?, bbox?, polygon?, title?, limit?}` | `201` `DraftDetail` (author `rule:<id>`; every issue with a fix becomes a change, refetched live; the draft's `scope` is the scope geometry) |
| POST | `/api/drafts/from-osmchange` | `DraftFromOsmChangeRequest` `{title, osmChange, description?, area?, changesetComment?, changesetSource?, reasons?, reason?, takeOver?}` | `201` `OsmChangeImportResult` (author `user`; nothing is created when the osmChange is invalid) |
| GET | `/api/drafts/{id}` | | `DraftDetail` |
| GET | `/api/drafts/{id}/osmchange` | | `application/xml` osmChange of the draft's pending, approved and conflict changes |
| POST | `/api/drafts/{id}/osmchange` | `OsmChangeImportRequest` `{osmChange, reasons?, reason?, replace?, takeOver?}`, or the raw XML with `Content-Type: application/xml` and `?replace=&reason=&takeOver=` | `OsmChangeImportResult` `{draft, results}` |
| PATCH | `/api/drafts/{id}` | `UpdateDraftRequest` `{title?, description?, status?, changesetComment?, changesetSource?}` | `Draft` (omitted fields are kept; `status` only `open` or `closed`) |
| POST | `/api/drafts/{id}/changes` | `ProposeChangesRequest` | `ProposalResult[]` |
| POST | `/api/drafts/{id}/refresh` | | `DraftDetail` (refetches live versions; marks conflicts, auto-rebases where possible) |
| POST | `/api/drafts/{id}/approve-remaining?ruleId=` | | `DraftDetail` (approves pending changes with `confidence=deterministic` only) |
| GET | `/api/drafts/{id}/merge-preview` | | `MergePreview` (changeset groups with comment and tags, ids of changes in conflict) |
| POST | `/api/drafts/{id}/merge` | | `202`; progress arrives as `merge_progress` events |
| GET | `/api/drafts/{id}/uploads` | | `Upload[]` |
| POST | `/api/changes/{id}/review` | `ReviewRequest` | `Change` (`approve`, `edit` with full `tags`, `reject` with `comment`, `send_to_id`, `reset`; `cascade` see [dependencies](#dependencies)) |
| POST | `/api/changes/{id}/resolve` | `ResolveConflictRequest` `{tags, useLiveGeometry?}` | `Change` (final tags after a three-way pick; back to pending on the live version; a geometry change keeps its geometry unless `useLiveGeometry`) |
| POST | `/api/manual-edits` | `ManualEditRequest` `{ref, tags, reason?, baseTags?}` | `Change` in the open manual draft (created on demand) |

### Geometry drafts (osmChange)

A change is one OSM element with `action` `create`, `modify` or `delete`. Besides tags it can carry
geometry:

| Field on `Change` | Meaning |
| --- | --- |
| `baseShape` | `ElementShape` `{location?, nodes?, members?}` of the live element the change is based on (node location, way node ids, relation members `{type, ref, role}`) |
| `newShape` | The proposed geometry; only the parts that change are set. `null` on a tag-only modify |
| `geometryChanged` | `true` for creates, deletes and modifies with a `newShape` |
| `geometrySummary` | `new way, 3 nodes`, `moved 11 m`, `nodes 4 → 2 (+0 −2)`, `members 1 → 2 (+1 −0)`, `deleted if unused` |
| `baseGeom`, `newGeom` | GeoJSON for drawing before/after: `Point` for nodes, `LineString` for ways (node coordinates from the osmChange, other changes of the draft and the live API); relations have no `newGeom` (`baseGeom` from the mirror when available), use `newShape.members` |
| `ifUnused` | A delete sent in an `<delete if-unused="true">` block |
| `placeholderId` | The negative id a created element had before upload; `ref` then holds the real id |
| `liveShape`, `liveGeom` | The live geometry when the change is a geometry conflict |
| `dependsOn`, `requiredBy` | `ChangeLink[]` `{changeId, ref, action, status}`, see below |

`POST /api/drafts/{id}/osmchange` takes an osmChange 0.6 document as iD writes it
(`osmChangeset.osmChangeJXON`): `<create>` with negative ids, `<modify>` and `<delete>` with the
versions the editor started from, `if-unused` on delete blocks, empty blocks allowed. `reasons` maps
an element ref as in the document (`n-1`, `w60`) to a reason shown to the reviewer; `reason` applies
to the rest. The import:

- validates the document: unique elements, negative ids only in `<create>`, new nodes with
  `lat`/`lon`, ways with at least 2 nodes, every reference resolvable (to an element of the document,
  a new element already in the draft or a live element), nothing referencing an element the draft
  deletes. Any problem rejects the whole import with a 400 listing them.
- fetches the live elements of modifies and deletes in batches (missing or deleted ones are a 400)
  and stores base and new state per element. An element with an open (pending, approved or
  conflict) change in another draft is a 400 with `code` `open_change_elsewhere` and `openChanges`
  `OpenChangeElsewhere[]` `{ref, changeId, status, draftId, draftTitle}` (other validation problems
  take precedence as a plain 400). With `takeOver: true` those changes are withdrawn instead:
  rejected with the comment `moved to draft "<title>" (<id>)`, their dependents in that draft
  rejected as with MCP `withdraw`, and a `draft_updated` event sent for that draft.
- gives new elements server-wide unique negative ids. `results[].ref` is the stored ref and
  `sourceRef` the ref from the document when they differ; the export uses the stored ids, and
  importing an element whose negative id is a new element of the draft updates it.
- updates the draft's open change for an element instead of adding a second one (outcome
  `duplicate`, back to `pending`); an element equal to its stored change is `no_op` "unchanged",
  a modify equal to the live element is `no_op` and withdraws an existing change for it.
- marks a modify or delete as `conflict` when its `version` differs from the live one (the base is
  then the live element; resolve it like any conflict).
- with `replace: true` treats the document as the whole session: open changes of the draft that are
  not in it are rejected ("removed in the editor"). A `<delete>` of a negative id withdraws that new
  element.
- strips `geodesk:` keys from all tags.

A modify that only changes tags is stored as a tag-only change (no `newShape`) and behaves like a
rule or MCP tag change. `GET /api/drafts/{id}/osmchange` returns the pending, approved and conflict
changes as one osmChange (creates node → way → relation, modifies, deletes relation → way → node,
`if-unused` deletes in their own block) with the base versions, so the editor can rehydrate the
session and send it back with `replace: true`. Uploaded and rejected changes are left out.

### Dependencies

A change depends on another change of the same draft when it cannot be uploaded without it:

- a way (new or modified) whose node list contains a new node, and a relation whose member list
  contains a new element;
- a delete of an element that a modified or deleted way or relation of the draft uses in its base
  but no longer in its new state (the node of a shortened way, the nodes of a deleted way).

Review stays per object, with these rules:

- `reject` and `send_to_id` are refused (409) while a direct or indirect dependent is `approved`;
  with `cascade: true` every dependent that is not rejected or uploaded is rejected too, each with a
  review comment naming the dependency.
- `approve` and `edit` are refused while a dependency is `rejected`; with `cascade: true` the pending
  dependencies are approved too (a dependency in conflict is refused).
- MCP `update_change` with `withdraw` rejects the dependents as well.

`MergePreview.blocked` lists approved changes that cannot be uploaded because a dependency is not
approved or uploaded (`{changeId, ref, message}`); the merge skips them.

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
500 changes each. Changes that depend on each other always share a changeset: a connected group
goes into the changeset of its first rule and is never split (one larger than 500 gets a changeset
of its own); a group larger than 10,000 elements fails the merge with an error. Each changeset carries:

- `comment`: the draft's `changesetComment` when set, else `<area>: <rule comment> (rule <id>),
  reviewed individually` (or the draft title for manual changes); the `<area>: ` prefix is left out
  when the draft has no area, as for bbox and polygon scopes.
- `source`: the distinct non-blank values of the draft's `changesetSource` and the `imagery` of the
  `approve` and `edit` reviews of the changes in that changeset, joined with `; `. Omitted when empty.
- `created_by`.

The merge preview shows these tags per group.

### Merge behaviour

Merging uploads only `approved` changes. Each changeset gets one osmChange with creates (nodes,
ways, relations in dependency order), then modifies, then deletes (relations, ways, nodes;
`if-unused` deletes in a `<delete if-unused="true">` block). The ids in the `diffResult` are stored
per change (`ref` gets the real id, `placeholderId` the old one) and replace the placeholders in the
node and member lists of the draft's other changes, so later changesets and later merges reference
the uploaded elements.

Version checks run per element at refresh and before upload. A tag-only modify whose live version
moved is auto-rebased as before. A geometry modify is auto-rebased only when the live geometry still
equals its base geometry and no tag it touches changed; otherwise, and for every delete whose live
version moved, it becomes `conflict` with `liveVersion`, `liveTags`, `liveShape` and `liveGeom`.
Geometry is never merged automatically: `resolve` puts the change onto the live version keeping its
own geometry (`useLiveGeometry: true` drops it, leaving a tag change). A `<delete>` is never rebased: any upstream
change to the element (tags or geometry) since the draft was made puts it in `conflict`, both at
refresh and in the check right before upload, so a stale delete is never uploaded with the live
version. `resolve` keeps the delete against the new live version (`baseVersion` becomes the live
version; tags and `useLiveGeometry` in the request are ignored) and returns it to `pending`;
rejecting the change drops the delete. New elements have no version
check; a new way using a live node deleted meanwhile fails at upload.
 Changes in conflict are skipped, never a 409: they are
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
| GET | `/auth/callback` | OAuth redirect target (`code`, `state`; an `error` is a 400); stores the token, redirects to `/?connected=1` |

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

## Rules folder

Files in `RULES_DIR` (see the README). Paths are relative to the folder and use `/`:
`highlights/<name>.mapcss`, `checks/<name>.validator.mapcss`, `presets/<name>.json`,
`presets/smart/<name>.json` or `sources.json`, where `<name>` is letters, digits, `_`, `.` and `-`.
Anything else is a 400: other extensions, `..`, absolute paths, hidden segments (`.trash`), and paths
that resolve through a symlink to outside the folder. Remote files are listed as
`remote/<name>.<ext>` and are read-only (409 on write or delete).

Built-in files (the highlights, checks and smart presets from `user-rules.example`, shipped with the server) are
listed under their normal paths with `readOnly: true` and `origin.builtIn: true`, unless a local file
has the same path: the local file overrides the built-in one. Writing to a built-in path creates
that override; deleting the override brings the built-in back (`rules_changed` reports the path as
changed). Deleting a path that only exists as a built-in is a 409.

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| GET | `/api/rule-files?kind=` | | `RuleFileList` `{rulesDir, files: RuleFileInfo[]}`; `kind` is `highlight`, `check`, `preset`, `smart_preset` or `sources` |
| GET | `/api/rule-files/{path}` | | `RuleFileContent` `{file, content}`; 404 when missing |
| POST | `/api/rule-files` | `CreateRuleFileRequest` `{path, content, force?}` | 201 `WriteRuleFileResult`; 409 when the file exists |
| PUT | `/api/rule-files/{path}` | `WriteRuleFileRequest` `{content, force?, expectedHash?}` | `WriteRuleFileResult` (creates or replaces); 409 when `expectedHash` is not the current hash |
| POST | `/api/rule-files/copy` | `CopyRuleFileRequest` `{path, to?}` | 201 `WriteRuleFileResult`: a built-in or remote file's content written to `to` in the folder (default: the same path for built-in files, `<kind folder>/<name><ext>` for `remote/<name><ext>`); 404 for an unknown `path`, 409 when the target exists |
| POST | `/api/rule-files/validate` | `ValidateRuleFileRequest` `{path, content}` | `ValidateRuleFileResult` `{path, kind, valid, diagnostics}`; nothing is written |
| DELETE | `/api/rule-files/{path}` | | `DeleteRuleFileResult` `{path, trashedTo}`: the file moves to `.trash/<UTC timestamp>/<path>` |
| POST | `/api/rule-files/sources/refresh` | | `RuleFileList` after fetching every source in `sources.json` again |

`RuleFileInfo` is `{path, kind, hash, modifiedAt, size, readOnly, diagnostics, origin}`: `hash` is the
SHA-256 of the file's bytes, `origin` is `null` for local files, `{source, url, licence,
fetchedAt, error}` for remote ones and `{source: "built-in", url: "builtin:<path>", licence, builtIn:
true}` for built-in ones. A diagnostic is `{file, line, column, severity, message}` with
`severity` `error`, `warning` or `info`; lines and columns start at 1.

MapCSS files (`highlights/*.mapcss`, `checks/*.validator.mapcss`) are parsed by the shared MapCSS
engine: syntax errors, unknown functions and bad regexes are errors at their line and column,
unsupported JOSM features are warnings. After parsing, every `assertMatch` / `assertNoMatch` runs
(`selfTest`) and a failing one is an error at the assertion. A check file without `throw*` rules
and a highlight without style rules get a warning. Smart preset selectors (`match`, `with`) are
checked with the same parser.

Writes validate the content with the loader for the path's kind, including checks across files (a
preset's `extends` and `{presetId}` references, duplicate ids). With an `error` diagnostic the file is
not written and the answer is 422 with `WriteRuleFileResult` `{saved: false, file: null,
diagnostics}`, unless `force` is true. Successful writes go to a temporary file in the same folder
and are renamed over the target. Files over 2 MB are refused.

Remote sources: `sources.json` is `[{name, url, kind, refreshHours = 24, licence?}]`. Each source is
fetched with the server's User-Agent into `DATA_DIR/rule-cache/<name>.<ext>` when it is new, its URL
or kind changed, or `refreshHours` passed (checked every 15 minutes and whenever `sources.json`
changes). A failed fetch keeps the last good copy and reports `origin.error`. Without a `licence`,
`origin.licence` is a note that the file is third-party, read-only and not redistributed.

## Highlights

Highlights are the MapCSS files in `highlights/` (and remote `highlight` sources). The web map draws
them on the vector tiles with MapLibre filters compiled from each rule; rules that cannot be compiled
(linked selectors, host functions such as `odd_segment()`, non-literal regexes, `;`-list tests) are
evaluated on the server instead.

Tile property convention (`TileProperties` in every `HighlightList`): every OSM tag is a plain
feature property under its own key (`highway`, `sidewalk:both`, ...; `tagPrefix` is empty), plus
`$type` (`node`, `way` or `relation`), `$closed` (boolean, closed ring or area relation) and `$id`
(the OSM id). Compiled filters read exactly these, e.g. `way.street[!sidewalk]` becomes
`["all", ["==", ["get", "$type"], "way"], ..., ["!", ["has", "sidewalk"]]]`. The server's tiles carry
`@type`, `@id` and `@closed`; the web app's tile worker adds `$type`, `$id` and `$closed` (true on the
`area` layer and on lines with `@closed`) to every feature it re-encodes, and a unit test
(`web/src/map/osm/highlightFilter.test.ts`) checks on synthetic and real Opava tiles that every
compiled filter selects exactly the features the MapCSS engine matches. Each rule also carries
`style`, its declarations with literal values (`color`, `width`, `dashes`, `casing-*`, `fill-*`,
`symbol-*`, `text` as `{tag}` or a literal), which the map uses to draw it.

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/highlights` | `HighlightList` `{tileProperties, files: HighlightFile[]}` |
| GET | `/api/highlights/file?path=` | `HighlightFile` |
| GET | `/api/highlights/evaluate?path=&bbox=` or `&tile=z/x/y` `&limit=` | `HighlightEvaluation` |
| GET | `/api/highlights/stats?path=&area=` or `&bbox=` or `&polygon=` | `CoverageStats` |
| POST | `/api/highlights/stats` with `HighlightStatsRequest` `{path, area?, bbox?, polygon?}` | `CoverageStats` |

`HighlightFile` is `{path, hash, title, description, dimOthers, stats, legend, rules, allCompiled,
goql, valid, readOnly, diagnostics}`: `title`, `description`, `dimOthers` (`-ore-dim-others`) and
`stats` (`-ore-stats`) come from the `meta` block, `legend` is `[{label, color, rule}]` from
`-ore-legend`, and each of `rules` (only rules with style properties) is `{index, line, selectors,
legend, color, compiled, filter}` with the MapLibre `filter` when `compiled`. `goql` is the GeoDesk
prefilter the server uses. A file with errors is listed with `valid: false` and only its diagnostics.

`evaluate` runs the whole stylesheet on the mirror objects in a bbox (at most about 0.1° × 0.06°,
i.e. zoom 13 or closer) or one slippy-map tile: `HighlightEvaluation` `{path, hash, bbox, tile,
features: [{ref, style}], truncated, candidates, tookMs, cached}`, where `style` is the cascaded
`StyleResult` `{properties, classes, rules}` (`properties` holds `color`, `width`, `-ore-legend` and the
other style properties; `rules` the indexes of the matching style rules). Objects matching no style
rule are left out. Results are cached per file, file hash, mirror generation, area and limit (512
entries; a mirror swap or a rules change empties the cache). Default limit 20,000.

Coverage (`stats`) evaluates the file over a [scope](#scopes) and sums per legend entry, using the
measure the file names in `-ore-stats` (`length`, `area` or `count`, default `count`). Each object
counts once, under the legend of the last matching rule. `CoverageStats` is `{path, title, stats,
scope, areaName, scopeAreaKm2, entries, total, candidates, tookMs}` with entries `{label, color,
rule, count, lengthKm, areaKm2, percent}`; lengths and areas are clipped to the scope's polygon (or
bbox), and `percent` is the entry's share of the measure over all entries.

## Validation scans

A scan runs check files and built-in rules over a [scope](#scopes) and stores the issues
(`app.scans`, `app.scan_issues`). Scans run one at a time.

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/api/scans` | `RunScanRequest` `{area?, bbox?, polygon?, files?, builtIn?}` | 201 `Scan` when finished; 503 without a GOL |
| GET | `/api/scans?scope=&limit=` | | `Scan[]`, newest first |
| GET | `/api/scans/{id}?severity=&group=&rule=&limit=&offset=` | | `ScanReport` `{scan, counts, issues, truncated}` |
| GET | `/api/scans/{id}/geojson?severity=&group=&rule=&limit=` | | GeoJSON `FeatureCollection` of issue markers (`application/geo+json`) |
| GET | `/api/scans/{id}/diff?against=&limit=` | | `ScanDiff` |
| POST | `/api/scans/{id}/draft` | `ScanDraftRequest` `{issueIds?, group?, rule?, title?, draftId?}` | 201 `ScanDraftResult` `{draft, results, skipped}` |

`files` lists check file paths (default: every check file without errors, local and remote) and
`builtIn` built-in rule ids (default all, `[]` for none). `Scan` is `{id, scope, areaName, geometry,
ruleSet, ruleSetKey, status, trigger, issueCount, errors, warnings, infos, tookMs, startedAt,
finishedAt, mirrorFile, error}`; `ruleSet` is the sorted list of check paths and `builtin:<id>`
entries and `ruleSetKey` a hash of it. `status` is `running`, `done` or `failed`; `trigger` is
`manual` or `mirror_swap`.

`ScanIssue` is `{id, rule, file, line, severity, group, message, ref, centroid, fix, deleteObject}`.
For MapCSS checks `rule` is `<file>:<line>` of the rule, `group` its `group:` and `fix` the `TagEdit`
from `fixAdd`, `fixRemove` and `fixChangeKey`; `deleteObject` is `fixDeleteObject`. For built-in rules
`rule` is `builtin:<id>`, `group` the rule title and `message` the title with the rule's note.
`counts` in the report are `[{group, severity, count}]`, errors first.

The GeoJSON has one `Point` per issue at its centroid with properties `{id, rule, file, line,
severity, group, message, ref, osmType, osmId, hasFix, deleteObject}`.

`diff` compares with `against` or, by default, the previous finished scan with the same scope key and
rule set key. Issues are matched by rule source (file or built-in id, not the line), object and
message: `ScanDiff` is `{scan, against, newCount, resolvedCount, unchangedCount, new, resolved,
truncated}` with up to `limit` (default 500) issues per list. Without an earlier scan every issue is
new.

After a mirror swap the server re-runs, once per scope and rule set, the scans started in the last
`SCAN_RERUN_DAYS` days (default 7, 0 turns it off) with their original request, as `trigger:
mirror_swap`, and sends `scan_finished` for each. Scans still `running` at a restart are marked
failed.

`draft` turns the fixes of the selected issues (default every issue with a fix) into proposals: tag
fixes go through the normal proposal flow (several fixes for one object are merged into one change,
reasons joined), `deleteObject` issues become geometry-draft deletes with `if-unused`; for a way its
untagged nodes are deleted too (`if-unused`, so shared nodes stay). Relations cannot be deleted
here. A new draft is created by the user (title `Scan fixes · <area>`) unless `draftId` names an open
one; `skipped` counts selected issues without a fix.

## Presets

iD presets (id-tagging-schema 7.3.0, `en` and `cs`) with the presets from the rules folder layered
over them. `locale` is `en` (default) or `cs`; other values fall back to their language, then `en`.
Preset ids contain `/` and go into the path unencoded.

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| GET | `/api/presets/search?q=&locale=&geometry=&limit=` | | `PresetSummary[]` `{id, name, geometry, icon, origin, extends, file}` |
| GET | `/api/presets/{id}?locale=` | | `ResolvedPreset`; 404 for unknown ids |
| POST | `/api/presets/match` | `PresetMatchRequest` `{tags, geometry, locale?}` | `PresetMatch` `{preset, score}` |
| POST | `/api/presets/apply` | `PresetApplyRequest` `{presetId, tags, geometry?, fromPresetId?}` | `PresetApplyResult` `{presetId, edit: TagEdit, tags}` |
| POST | `/api/presets/apply/bulk` | `PresetBulkApplyRequest` `{presetId, refs, dryRun?, title?, draftId?, reason?, area?, locale?}` | `PresetBulkApplyResult` `{presetId, presetName, total, changed, unchanged, skipped, preview, truncated, draft, results}`; 400 above 5000 refs, 404 for an unknown preset, 503 without a GOL |
| POST | `/api/presets/import` | `ImportPresetRequest` `{presetId, id?, path?}` | 201 `WriteRuleFileResult`: a new overlay file `{id, extends, apply}`; `id` defaults to `custom/<presetId>`, `path` to `presets/<id with dashes>.json`; 409 when the id or file exists |
| POST | `/api/presets/detach` | `DetachPresetRequest` `{id?, path?}` | `WriteRuleFileResult`: the overlay rewritten as a full preset without `extends` |
| GET | `/api/presets/smart?locale=` | | `SmartPreset[]` from `presets/smart/` without errors; `name` in the locale (default `en`), `names` with every translation |
| GET | `/api/presets/user?locale=` | | `ResolvedPreset[]`: every preset from the rules folder (local, remote and built-in files) that resolves, in the locale, sorted by name |
| GET | `/api/presets/id-schema` | | `IdSchemaInfo` `{version, locales, files, baseUrl}` |
| GET | `/api/presets/id-schema/{file}` | | The raw iD file: `presets.json`, `fields.json`, `preset_categories.json`, `preset_defaults.json`, `deprecated.json`, `discarded.json`, `translations/en.json`, `translations/cs.json`, `package.json`; `ETag` is the version, cached for a day |

`ResolvedPreset` has every key filled in: `name` (localised; a user preset's own `name` wins, a
`{presetId}` name takes that preset's localised name), `geometry`, `tags`, `addTags` (default
`tags`), `removeTags` (default `addTags`), `fields` and `moreFields` as field ids with `{presetId}`
references expanded (a preset without fields inherits them from its `extends` or, for iD presets,
from the parent id like `highway/crossing` for `highway/crossing/zebra`), `icon`, `terms`, `aliases`,
`matchScore`, `searchable`, `extends`, `apply` (`merge`, `replace`, `create`), `origin` (`id` or
`user`), `file`, `locale` and `fieldInfo` (`{id, key, keys, type, label, placeholder, options,
optionLabels}` in the locale).

Search matches the localised name, aliases and terms without diacritics (`chodnik` finds
`Chodník`): exact name first, then name prefix, aliases, word prefix, terms, substrings; in `cs` the
English name is searched too, ranked lower. Unsearchable presets and `@templates` are left out.

Matching scores every preset whose `geometry` includes the given one like iD: each preset tag adds
`matchScore` (default 1) when equal and half of it when the preset's value is `*`; any other tag
mismatch rules the preset out; `addTags` that are not in `tags` and match add `matchScore`;
unsearchable presets score 0.999 times. The highest score wins, a user preset wins ties, and with no
match the generic `point`, `line`, `area` or `relation` preset is returned with score 0. `geodesk:`
pseudo-tags are ignored.

Applying: `merge` removes `fromPresetId`'s `removeTags` (and `area`) when given, then sets `addTags`,
keeping every other tag; a `*` value keeps the object's value or becomes `yes`. `replace` makes
`addTags` the whole tag set (`TagEdit.replaceAll`). `create` does the same but only for objects with
no tags (400 otherwise). A geometry the preset does not support is a 400.

Bulk apply looks the refs up in the GOL mirror in one ID-set scan and applies the preset to each
object like `/api/presets/apply`, with the geometry taken from the mirror (a node is a `vertex` when
it is on a way, else a `point`; a way is an `area` when GeoDesk treats it as one, else a `line`; a
multipolygon is an `area`, other relations `relation`) and `fromPresetId` set to the object's
current best match. Refs missing from the mirror, objects whose geometry the preset does not support
and tagged objects for a `create` preset go to `skipped` (`{ref, message}`); objects whose tags would
not change count as `unchanged`. `preview` holds the first 200 changed objects as `{ref, name,
fromPresetId, before, after, summary}` (`summary` is the `TagDiff` summary, `truncated` when there
are more). `dryRun` defaults to `true`. With `dryRun: false` and at least one change, the changes go
into a new draft (author `user`, `title` default `Preset <name> · <n> objects`, `area` stored with the
draft) or into the open draft `draftId`, one proposal per object with `reason` (default `Preset
"<name>"`) and confidence `needs_review`; `draft` is its `DraftDetail` and `results` the
`ProposalResult[]`. Nothing is uploaded.

The MCP tool `apply_preset_bulk` runs the same bulk apply (`preset_id`, `refs` as `"w45"` or
`{type, id}`, `dry_run`, `title`, `draft_id`, `reason`, `area`, `locale`); its drafts are authored by
`claude`, it returns at most 50 preview rows and 50 skipped objects (`truncated` when cut), and with a
draft its `draft_id`, `title`, `author` and `results` (count per outcome).

`name` in a preset or smart preset file is a string or an object of locale to name
(`{"cs": "Přechod", "en": "Crossing"}`; keys like `en`, `cs`, `cs-CZ`; at least one entry, each a
non-empty string). A name is resolved for a locale by the exact locale, then its language, then `en`,
then the first entry. For presets the object form feeds `ResolvedPreset.name`; the string form may
still be a `{presetId}` reference.

Smart presets (`presets/smart/*.json`) are `{id, name, geometry, match, params, steps, description?}`.
`SmartPreset.name` is the resolved name and `names` holds every translation of an object-form name
(empty for a plain string), so the editor can switch language without refetching. Smart presets run
in the editor only, with iD actions (crossing lookup, shared nodes, splitting ways); there is no REST
or MCP endpoint that applies them.
Validation checks the keys and types, parameter definitions (`combo` needs `options` and a default
among them), each step's `op` and arguments, that names used in steps are bound earlier (`self` is
always bound) and of the right type (way or node), `distance` expressions, and `{param}` placeholders
in `tag` values. `match` and `with` are MapCSS selectors kept as strings; the smart preset loader takes
a selector validator that the MapCSS engine provides.

## WebSocket `/api/events`

Server → UI messages, one `UiEvent` JSON per frame, discriminated by `type` (`UiEvent` in
`shared/src/commonMain/kotlin/eu/tiducto/osmreview/shared/Api.kt`). The server ignores frames from
the client.

| `type` | Fields | Meaning |
| --- | --- | --- |
| `remote_command` | `command`, `bbox?`, `select`, `draftId?`, `draftIds`, `imagery?`, `message?` | A JOSM remote-control request (`command` is `zoom`, `load_and_zoom`, `load_object`, `import`, `load_data`, `add_node` or `imagery`). Fly to `bbox`, select `select`, open `draftId` if set, add `imagery` (`ImagerySource`). `draftIds` lists every draft the command created (one per object for `addtags`; `draftId` is the first). `message` reports what an import skipped. |
| `push_view` | `bbox?`, `center?`, `zoom?`, `areaName?`, `highlights`, `replaceHighlights`, `scanId?`, `draftId?`, `select`, `message?` | Claude moved the view with the MCP `push_view` tool. `bbox` is `{west, south, east, north}` (the resolved area or, with only `select`, the selected objects), `center` is `{lng, lat}` with `zoom` (0–22, 17 when only a center was given), `areaName` is set when the area was given by name, `highlights` are highlight file paths (`replaceHighlights: false` adds them to the active ones, `true` makes exactly these active), `select` is an `OsmRef` list. The UI shows a toast "Claude changed the view" with undo. |
| `draft_updated` | `draftId` | A draft changed (Claude proposed changes, a takeover withdrew changes, a draft was edited); refetch it. |
| `merge_progress` | `progress`: `MergeProgress` `{draftId, group, done, total, changesetId?, message, finished, failed}` | Upload progress of a draft; `finished` ends it, `failed` with the error in `message`. |
| `mirror_swapped` | `status`: `MirrorStatus` `{available, file?, openedAt?, dataTimestamp?, message?, sidecar?}` | The GOL was updated and swapped; refetch tiles and rule layers. |
| `rules_changed` | `changed`, `removed` | Files in the rules folder or remote sources changed (paths). Refetch `/api/rule-files` and presets. |
| `scan_finished` | `scanId`, `scope`, `issueCount` | A validation scan finished, for example the automatic re-run after a mirror swap. |

`remote_command` and `push_view` events emitted while no tab is connected are queued (the 20 most
recent, in order) and replayed to the next tab that connects.

## MCP `/mcp`

MCP over Streamable HTTP (JSON responses, sessions in the `mcp-session-id` header): `POST` for
requests, `DELETE` to close a session, `GET` is a 405. Every request needs `Authorization: Bearer
<MCP_TOKEN>` (401 otherwise); the token and a ready `claude mcp add` command are at
`GET /api/settings/mcp`. Tool errors come back as MCP tool errors with the reason.

`area` arguments take an area preset, admin boundary or place name (`"Opava"`), a bbox
`[west, south, east, north]` or `"w,s,e,n"`, or a GeoJSON Polygon/MultiPolygon (geometry, `Feature`
or one-feature `FeatureCollection`, as an object or a JSON string) with the [scope](#scopes) limits.
Refs are `"n123"`, `"w45"`, `"r6"` unless noted.

| Tool | Arguments (required in bold) | Returns |
| --- | --- | --- |
| `list_rules` | | Built-in rules and MapCSS check files as `Rule[]` (id, title, severity, confidence, fixes) |
| `run_rule` | **`rule_id`**, **`area`**, `limit` (200) | `RuleRunResult`; `rule_id` is a built-in id or a check file path |
| `query_osm` | **`area`**, `goql` or `overpass` | `{scope, truncated, objects}`, at most 500 objects with tags and a geometry summary; GOQL runs on the mirror clipped to the area, Overpass gets the area's bbox and results outside a polygon are dropped |
| `get_object` | **`type`**, **`id`**, `live` | Tags, geometry summary, `parents` (with member role, at most 50) and `connected_ways`; with `live` the API version, `visible`, `lat`/`lon`, `node_count`, `member_count` |
| `create_draft` | **`title`**, `description`, `area` | `{draft_id}`; author `claude`, the area name and geometry stored with the draft |
| `propose_changes` | **`draft_id`**, **`changes`**: `[{type, id, add_tags?, remove_tags?, set_tags?, reason}]` (≤ 2,000) | `ProposalResult[]` (`accepted`, `no_op`, `duplicate`, `error`) |
| `propose_geometry` | **`draft_id`**, **`elements`**: `[{action, type, id, reason, lat?, lon?, nodes?, insert_nodes?, members?, add_tags?, remove_tags?, set_tags?, if_unused?}]` (≤ 500) | `ProposalResult[]`; negative ids for new elements, relations can only be modified, `if_unused` defaults to `true` |
| `update_change` | **`change_id`**, `add_tags`, `remove_tags`, `set_tags`, `withdraw`, `reason` | The revised `Change` (back to `pending`); `withdraw` also withdraws its dependents |
| `list_drafts` | `status`, `author` | `Draft[]` |
| `get_draft` | **`draft_id`** | `DraftDetail` with changes, reviews and comments |
| `run_scan` | **`area`**, `files`, `built_in` | The finished `Scan` |
| `get_scan` | `scan_id`, `severity`, `group`, `rule`, `limit` (100), `offset` | `ScanReport`, or recent `Scan[]` without `scan_id` |
| `diff_scans` | **`scan_id`**, `against_id`, `limit` (200) | `ScanDiff` |
| `draft_from_scan` | **`scan_id`**, `issue_ids`, `group`, `rule`, `title`, `draft_id` | `ScanDraftResult`; the draft is authored by `claude` |
| `list_highlights` | | `{tile_properties, files}` as in `HighlightList`, without the MapLibre filters |
| `highlight_stats` | **`path`**, **`area`** | `CoverageStats` |
| `evaluate_highlight` | **`path`**, **`area`**, `limit` (500), `legend` | `{path, scope, count, truncated, took_ms, features: [{ref, style}]}` |
| `list_rule_files` | `kind` | `{rules_dir, files: RuleFileInfo[]}` |
| `read_rule_file` | **`path`** | `RuleFileContent` |
| `validate_rule_file` | **`path`**, **`content`** | `ValidateRuleFileResult` |
| `write_rule_file` | **`path`**, **`content`**, `force`, `expected_hash` | `WriteRuleFileResult`; refused with the diagnostics when there are errors and `force` is not set |
| `copy_rule_file` | **`path`**, `to` | `WriteRuleFileResult`, like `POST /api/rule-files/copy` |
| `delete_rule_file` | **`path`** | `{path, trashed_to}` |
| `search_presets` | **`query`**, `locale`, `geometry`, `limit` (20) | `PresetSummary[]` |
| `get_preset` | **`id`**, `locale` | `ResolvedPreset` |
| `match_preset` | **`tags`**, **`geometry`**, `locale` | `PresetMatch` |
| `import_preset` | **`preset_id`**, `id`, `path` | `WriteRuleFileResult` of the new overlay file |
| `detach_preset` | `id` or `path` | `WriteRuleFileResult` |
| `apply_preset_bulk` | **`preset_id`**, **`refs`** (strings or `{type, id}`, ≤ 5,000), `dry_run` (true), `title`, `draft_id`, `reason`, `area` (name stored on the draft), `locale` | Counts, up to 50 preview rows and 50 skipped objects, and with a draft its `draft_id`, `title`, `author` and `results` per outcome, see [Presets](#presets) |
| `push_view` | `area` or `center` (`[lon, lat]`) with `zoom`, `highlights`, `replace_highlights`, `scan_id`, `draft_id`, `select`, `message`; at least one | `{delivered, queued, event}`; sends a [`push_view`](#websocket-apievents) event |

Resources: `draft://{id}` (`DraftDetail`), `rule://{id}` (a built-in rule's `Rule`) and
`area://{name}` (`Area` with its GeoJSON geometry). No tool uploads, and smart presets have no tool:
they run in the editor only.

## JOSM remote control `:8111`

The app answers JOSM's remote-control protocol on `127.0.0.1:8111` (`REMOTE_CONTROL_PORT`). Every
response carries `Access-Control-Allow-Origin: *`, and `OPTIONS` preflights are answered (with
`Access-Control-Allow-Private-Network: true`) so QA websites can call it. Success is `OK` as
`text/plain`, followed by a second line when an import skipped something; errors are plain text, 400
for missing or invalid parameters and 500 otherwise. Every command is sent to the UI as a
[`remote_command`](#websocket-apievents) event.

| Method | Path | Parameters | Effect |
| --- | --- | --- | --- |
| GET | `/version` | | JSON `{protocolversion: {major: 1, minor: 13}, application, version, osm_server: {url}}` |
| GET | `/zoom` | `left`, `right`, `top`, `bottom` | Fly to the bbox |
| GET | `/load_and_zoom` | `left`, `right`, `top`, `bottom`, `select`, `addtags`, `changeset_comment`, `changeset_source` | Fly to the bbox and select the objects; with `addtags` one draft per selected object |
| GET | `/load_object` | `objects`, `relation_members`, `addtags`, `changeset_comment`, `changeset_source` | Frame and select the objects (with `relation_members=true` also the members); objects missing from the mirror are fetched live to frame them; `addtags` as above |
| GET | `/import` | `url`, `changeset_comment`, `changeset_source` | Fetch OSM XML from `url` and turn it into a draft |
| GET, POST | `/load_data` | `data` (OSM XML), `layer_name`, `changeset_comment`, `changeset_source` | Turn the XML into a draft titled `layer_name`; POST takes form parameters |
| GET | `/add_node` | `lat`, `lon`, `addtags` | A draft with one new node |
| GET | `/imagery` | `url`, `title`, `type` (`tms` default, or `wms`) | Add an imagery layer to the open tab (JOSM's `{zoom}`, `{bbox}`, `{proj}`, `{width}`, `{height}` are translated) |

`select` and `objects` are comma-separated refs (`n123`, `w45`, `r6` or `node123`, `way45`,
`relation6`). `addtags` is `k=v|k2=v2`. `changeset_comment` and `changeset_source` are stored on the
draft (`changesetComment`, `changesetSource`); with `addtags` the comment is also the change's
reason. When an object already has an open change elsewhere, the `addtags` draft is closed and that
change's draft is opened instead.

`/import` and `/load_data` keep tag changes of existing objects (a full tag set per object; with
`action` attributes only `action="modify"` elements count) and new tagged nodes with a position.
They do not import geometry: moved nodes, changed way node lists or relation members, deletions, new
ways, new relations and untagged new nodes are skipped, counted in the draft description and in the
response (`OK` then `Skipped (geometry editing is not supported yet): ...`). To bring geometry edits
into a draft, use `POST /api/drafts/from-osmchange` or the in-browser editor.
