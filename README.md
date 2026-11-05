# OSM Review Editor

A self-hosted OpenStreetMap editing tool where every change is a reviewable draft, like a merge
request. QA rules, Claude (over MCP) and external QA tools (JOSM remote control) propose exact tag
changes; you approve, tweak or skip each object; only approved changes are uploaded.

- **Find problems fast**: QA rules as filterable map layers over a whole city or country, from a
  local GeoDesk copy of OSM.
- **Fix without typing**: rules and Claude propose exact tag changes; you approve, edit or skip.
- **Review like a code MR**: before/after on the map plus a tag diff per object, before upload.
- **Plug into the ecosystem**: answers JOSM remote control, so "Edit in JOSM" on Osmose, MapRoulette,
  OSMCha and osm.org opens objects here.

Nothing reaches OSM without a human approving each object. Claude and external tools can create
and edit drafts, never upload.

> Status: early development, single-user, local or home-lab. Drafts can hold full osmChange
> edits (new, moved and deleted nodes, way node lists, relation members) imported from an editor
> session, reviewed per object with their dependencies and uploaded in dependency-safe changesets
> ([API](docs/api.md#geometry-drafts-osmchange)); the in-browser geometry editor is not built yet,
> so geometry work in the UI is still handed to iD with a deep link.

## Quick start (Docker)

```sh
cp .env.example .env      # set DB_PASSWORD and TOKEN_ENCRYPTION_KEY at least
docker compose up -d --build
```

The first start downloads the Czechia extract from Geofabrik (about 950 MB, so a few minutes on a
typical connection) and builds the GOL (under 2 minutes on Apple Silicon); the `gol` sidecar then applies daily replication diffs and does a weekly full rebuild. Open
http://localhost:3000.

Uploads go to the OSM **development** API until you switch to production in Settings (with a
confirm). Each target needs its own OAuth 2.0 app:

| Target | Register the app at | Redirect URI | Scopes |
| --- | --- | --- | --- |
| dev | https://master.apis.dev.openstreetmap.org/oauth2/applications | `http://localhost:3000/auth/callback` | `read_prefs write_api` |
| prod | https://www.openstreetmap.org/oauth2/applications | `http://localhost:3000/auth/callback` | `read_prefs write_api` |

Put the client IDs and secrets into `.env`, then use Settings → Connect OSM account. The mirror
always holds production data, so on the dev target live fetches of production IDs fail; test
uploads on dev with objects created on dev.

### Configuration (`.env`)

| Variable | Purpose |
| --- | --- |
| `DB_PASSWORD` | Postgres password (required) |
| `OSM_TARGET` | API target at first start: `dev` (default) or `prod`; later switched in Settings |
| `OSM_CLIENT_ID_DEV`, `OSM_CLIENT_SECRET_DEV` | OAuth app on the dev server |
| `OSM_CLIENT_ID_PROD`, `OSM_CLIENT_SECRET_PROD` | OAuth app on osm.org |
| `TOKEN_ENCRYPTION_KEY` | Encrypts stored OAuth tokens (`openssl rand -base64 32`); without it tokens live in memory only |
| `MCP_TOKEN` | Bearer token for `/mcp`; generated and shown in Settings when empty |
| `OVERPASS_URL` | Overpass endpoint for `query_osm` (default `overpass-api.de`) |
| `DEFAULT_AREAS` | Path to a GeoJSON FeatureCollection of area presets (see below); compose mounts `./config` at `/config` |
| `BING_KEY` | Optional Bing Maps key; when set, Bing aerial imagery is offered (see Imagery) |
| `RULES_DIR` | Your rules folder (see below); default `user-rules`, compose mounts `./user-rules` at `/rules` and sets `RULES_DIR=/rules` |
| `SCAN_RERUN_DAYS` | After a mirror swap, re-run validation scans started in this many days (default 7, 0 turns it off) |
| `EXTRACT_URL` | Geofabrik extract the gol sidecar mirrors (default Czechia) |
| `CONTACT` | Contact for the User-Agent sent to the OSM API |

### Area presets

`DEFAULT_AREAS` points at a GeoJSON FeatureCollection. Every feature with `properties.name` becomes a
preset: a `Polygon` or `MultiPolygon` geometry is used as is (its bbox computed from it); a feature
with no or another geometry is looked up by name among the mirror's admin boundaries, like the
built-in presets (Opava, Opava-Kateřinky, Brno, Brno-střed, Ostrava, Olomouc, Praha), which are
always added. A missing or invalid file is logged and ignored. Preset names work wherever an area
name does (rule runs, drafts from rules, MCP `area` and `area://{name}`); a preset with a geometry
wins over a mirror boundary of the same name, and rules are clipped to its polygon.

```sh
cp config/areas.example.geojson config/areas.geojson
```

### Imagery

The imagery list lives in the settings table (key `imagery`) and is seeded with ČÚZK orthophoto, the
ČÚZK cadastral map overlay and Esri World Imagery. Replace it with `PUT /api/settings/imagery` or go
back with `POST /api/settings/imagery/reset` ([API](docs/api.md#imagery-sources)). Each source has a
`sourceTag` used for the changeset `source` tag.

Bing aerial imagery needs a key: its tile URL comes from the Bing Maps Imagery Metadata API, which
the server calls once at start when `BING_KEY` is set, then offers the layer with
`© Microsoft Corporation` attribution and `source=Bing`. Microsoft allows tracing Bing aerial
imagery for OSM only in an OSM editor ([OSM wiki](https://wiki.openstreetmap.org/wiki/Bing_Maps)).
Basic Bing Maps keys stopped working on 30 June 2025 and Enterprise keys work until 30 June 2028, so
without an Enterprise key leave `BING_KEY` empty.

### Rules folder

Your own highlights, checks and presets live as plain text files in one folder that you edit in any
editor, in the app, or through Claude over MCP:

| Path | Kind |
| --- | --- |
| `highlights/*.mapcss` | MapCSS map highlights |
| `checks/*.validator.mapcss` | MapCSS validator checks |
| `presets/*.json` | iD-style presets and overlays of iD presets (`extends`, `apply`) |
| `presets/smart/*.json` | Smart presets: multi-step edits (schema and validation now, execution later) |
| `sources.json` | Remote rule files, fetched read-only |

```sh
cp -R user-rules.example user-rules
```

[`user-rules.example/README.md`](user-rules.example/README.md) explains every format with one example
per kind. The server indexes the folder at start (path, kind, SHA-256, modification time,
diagnostics), watches it (`java.nio` WatchService, changes debounced; on macOS the JDK polls, so
edits show up within a few seconds) and sends `rules_changed` over the WebSocket. Writes from the API
and MCP are validated first and written atomically; deletes move the file to `.trash/<timestamp>/`
in the folder. Paths that leave the folder, symlinks pointing out of it and other file types are
refused.

Remote sources in `sources.json` (`[{name, url, kind, refreshHours, licence?}]`) are fetched into
`DATA_DIR/rule-cache`, refreshed when due, and listed read-only as `remote/<name>.<ext>` with their
source and a licence note. The repository ships no JOSM rule files (they are GPL); you can point a
source at one, for example
`https://josm.openstreetmap.de/export/HEAD/josm/trunk/resources/data/validator/unnecessary.mapcss`.

Each file kind has a pluggable loader that parses it and reports diagnostics with line and column.
MapCSS highlights and checks are parsed by the shared MapCSS engine, and their `assertMatch` /
`assertNoMatch` lines run as self-tests on every load; a failing assertion is an error at its line.
Smart preset selectors are checked with the same parser.

### Highlights and validation scans

A highlight file (`highlights/*.mapcss`) styles objects on the map and can carry a legend
(`-ore-legend`) and a coverage measure (`-ore-stats: length|count|area`). Most rules compile to
MapLibre filters over the vector tiles; the rest (host functions like `odd_segment()`, linked
selectors) are evaluated on the server per bbox or tile and cached per mirror generation and file
hash. Coverage numbers give, per legend entry, kilometres, count or area and its share over an area,
e.g. how much of Opava's street length has sidewalks tagged.

A validation scan runs every check file (your own and remote sources) and the built-in Kotlin rules
over an area, stores the issues, reports counts per group and severity, serves them as GeoJSON
markers, and diffs against the previous scan of the same area and rule set (new, resolved,
unchanged). After the mirror is updated, areas scanned in the last `SCAN_RERUN_DAYS` days are scanned
again. Fixes of selected issues become a draft for review, deletes included. See the
[API](docs/api.md#highlights).

MapCSS checks can use these host functions on top of JOSM's: `odd_segment("k1;k2")` (keys in which a
street segment differs from the same-named segments joined at both of its ends, while those agree),
`network_island_size()` (highway ways in the connected network of a way, capped at 100),
`half_width()` (metres, from `width`, `lanes` or a default per `highway` class),
`connected_value("key", "footway;path")` (the value of `key` shared by all connected highway ways of
those classes, `""` otherwise), `inside("CZ,CZ-80")` / `outside(...)` (ISO 3166 codes of the mirror's
admin boundaries), `waylength()` and `areasize()` (metres, m²), and the `⧉` (crossing) and `∈`
(inside) links. Czechia and the rest of continental Europe count as right-hand traffic.

### iD presets

The server ships `presets.json`, `fields.json`, `preset_categories.json` and the `en` and `cs`
translations of [id-tagging-schema](https://github.com/openstreetmap/id-tagging-schema) 7.3.0 (ISC),
about 1.6 MB. `npm install` fetches the package (`devDependencies` of the root `package.json`, exact
version), and the Gradle task `:server:copyIdTaggingSchema` copies the minified files and its
`package.json` (the recorded version) into the server's resources, so run `npm install` before
building the server. The web app gets the same files from the server at
`/api/presets/id-schema/{file}` ([API](docs/api.md#presets)).

Presets from the rules folder are layered over iD's: an overlay `extends` an iD preset and lists only
what it changes, `{presetId}` references work as in id-tagging-schema, and matching follows iD
(exact tag at the preset's `matchScore`, `*` at half, `addTags` bonus, geometry `point`, `vertex`,
`line`, `area` or `relation`), with your preset winning ties.

### How the mirror stays current

The `gol` sidecar keeps two GOL files, `a.gol` and `b.gol`, in the shared volume; `/data/current`
names the one the app reads. Each day it copies the current file under a temporary name, runs
`gol update` and `gol check` on the copy, renames it to the idle name and rewrites `/data/current`
atomically. The app notices within 15 seconds, opens the new file, swaps it in under a write lock,
drops rule caches and closes the old one; the UI refreshes its layers. Because `gol update` is
marked experimental, a full rebuild from a fresh extract runs every 7 days (`REBUILD_DAYS`).

Disk space for the `goldata` volume: `gol update` only appends, so the GOL grows with every update
and the weekly rebuild compacts it again. A fresh Czechia GOL is about 1.5 GB; after one month of
daily updates it had doubled to 3.1 GB. During an update the volume holds the current GOL, the idle
one and the temporary copy being updated, so three GOLs at once; a full build additionally needs the
950 MB extract and the build's index work directory. With the default `REBUILD_DAYS=7` plan for at
least 10 GB free, and more if you rebuild less often. The sidecar deletes the extract, the build's
`-indexes` directory, the update's `.gob` file and any temporary copy after each run, failed or not.

After every run the sidecar atomically rewrites `/data/status.json`, which the app serves as
`mirror.sidecar` in `/api/status`:

```json
{
  "lastRunAt": "2026-11-05T03:00:12Z",
  "lastRunKind": "update",
  "result": "ok",
  "error": null,
  "lastFullBuildAt": "2026-11-01T02:10:00Z",
  "nextFullBuildDue": "2026-11-08T02:10:00Z",
  "replicationSequence": 4612,
  "replicationTimestamp": "2026-11-04T20:21:03Z",
  "current": "b.gol"
}
```

`replicationSequence` and `replicationTimestamp` are the GOL's revision from `gol info`. A failed
run keeps the previous GOL and records the last lines of the tool's error output; a failed first
build is retried every 15 minutes (`RETRY_SECONDS`). Without the sidecar (local development) the
file is absent and `mirror.sidecar` is `null`.

## Claude over MCP

The server exposes MCP (Streamable HTTP) at `http://localhost:3000/mcp`, protected by a bearer token
(`MCP_TOKEN` in `.env`, or generated at first start and shown in Settings).

```sh
claude mcp add --transport http osm-review http://localhost:3000/mcp \
  --header "Authorization: Bearer <token>"
```

Then ask, for example: "fix deprecated crossing tags in Opava-Kateřinky". Claude runs rules and
queries, creates a draft with a reason per change, and reads your rejection comments to revise it.

| Tool | What it does |
| --- | --- |
| `list_rules` | Built-in rule ids and MapCSS check files, with titles, severity, confidence |
| `run_rule` | Issues for a built-in rule or a check file in an area, with proposed fixes |
| `run_scan`, `get_scan`, `diff_scans` | Validation scan of an area with every check and built-in rule; stored issues by group and severity; new and resolved issues against the previous scan |
| `draft_from_scan` | Turn the fixes of a scan's issues into a draft |
| `list_highlights`, `highlight_stats`, `evaluate_highlight` | Highlight files with legend; coverage per legend entry over an area; matching object ids with their style |
| `query_osm` | GOQL over the local mirror, or Overpass QL; confined to `area`; at most 500 objects |
| `get_object` | One object with its neighbours (`parents` with member roles, `connected_ways` for nodes and ways), from the mirror or live from the API (with version) |
| `create_draft` | A draft authored by Claude, optionally scoped to an area |
| `propose_changes` | Up to 2,000 changes: `add_tags`, `remove_tags`, `set_tags`, `reason` |
| `propose_geometry` | Up to 500 osmChange-style elements: new nodes (also inserted into existing ways), way node lists and splits, relation member lists, deletes with `if_unused` |
| `update_change` | Revise or withdraw a proposed change (withdrawing also withdraws changes that depend on it) |
| `list_drafts`, `get_draft` | Drafts, changes, your review decisions and comments |
| `list_rule_files`, `read_rule_file` | Files in the rules folder and remote sources, with kind, hash and diagnostics |
| `validate_rule_file` | Diagnostics for content without saving |
| `write_rule_file` | Validate and write atomically; refused on errors unless `force=true`; optional `expected_hash` |
| `delete_rule_file` | Move a file to the folder's `.trash/` |
| `search_presets`, `get_preset`, `match_preset` | iD and user presets: search (cs, en), resolved preset, best preset for tags and geometry |
| `import_preset`, `detach_preset` | Create an overlay file for an iD preset; turn an overlay into a full copy |

`propose_geometry` limits: relations can only be modified (member list), not created or deleted;
way node lists and relation member lists are complete replacements (or `insert_nodes` for a node
after an existing one); elements of other drafts with open changes are refused. The changes go
through the same per-object review as tag changes.

`area` is an area preset, admin boundary or place name (`"Opava"`, `"Opava-Kateřinky"`), a bbox
`[west, south, east, north]` (or the string `"w,s,e,n"`), or a GeoJSON Polygon/MultiPolygon in WGS84
(geometry, `Feature` or one-feature `FeatureCollection`, at most 10° across and 20,000 vertices).
Rules and GOQL queries are clipped to the polygon. Overpass queries get the area's bbox as a global
`[bbox:...]` setting, and results whose center falls outside the polygon are dropped. An area that
cannot be resolved (unknown name, malformed bbox, invalid polygon) is a tool error that says why.

Resources: `draft://{id}`, `rule://{id}`, `area://{name}`.

## JOSM remote control

The app listens on `127.0.0.1:8111` like JOSM (`/version`, `/load_and_zoom`, `/zoom`,
`/load_object`, `/import`, `/load_data`, `/add_node`, `/imagery`). Requests fly the open tab to the
object. If no tab is open, the command is queued and replayed when one connects. Real JOSM cannot
run at the same time (same port).

- `addtags` on `/load_and_zoom` and `/load_object` creates one one-change draft per selected object,
  ready to approve. `select` accepts `n123` and `node123` forms.
- `changeset_comment` and `changeset_source` are stored on the draft and become the `comment` and
  `source` tags of its changesets; the draft title stays descriptive.
- `/load_object` with `relation_members=true` also frames the relation's members. Objects missing
  from the mirror are fetched live to frame them.
- `/import` and `/load_data` keep tag changes on existing objects and new tagged nodes. Geometry is
  out of scope for v1: moved nodes, changed way node lists or relation members, deletions, new ways
  and relations and untagged new nodes are skipped, and their counts are listed in the draft
  description and in the response (`OK` followed by a `Skipped ...` line).
- Errors are plain text like JOSM: 400 for missing or invalid parameters, 500 otherwise.

## QA rules

Checks are MapCSS files in the rules folder. The example folder ships these (Apache-2.0), four of
which replace earlier Kotlin rules:

| File | Finds | Fix |
| --- | --- | --- |
| `checks/platforms.validator.mapcss` | Closed platform ways without `area=yes` (was `platform-area`); platforms missing `shelter`, `bench`, `tactile_paving` (was `platform-details`) | Add `area=yes`; issue only |
| `checks/crossings.validator.mapcss` | `highway=crossing` without `crossing=*` (was `crossing-missing-type`); deprecated `crossing=zebra` | `crossing` from `crossing_ref` when it implies one; upgrade to `crossing=uncontrolled` + `crossing:markings=zebra` |
| `checks/surfaces.validator.mapcss` | Footways, paths, cycleways without `surface` (was `footway-missing-surface`) | Surface of connected paths when they all agree (`connected_value`) |
| `checks/street-consistency.validator.mapcss` | Street segments that differ from both same-named neighbours (`odd_segment`); footways cut off from the network | Issue only |

Three rules stay in Kotlin ([`rules/`](rules/src/main/kotlin/eu/tiducto/osmreview/rules/BuiltInRules.kt))
because MapCSS cannot express them:

| Rule | Finds | Fix | Confidence |
| --- | --- | --- | --- |
| `deprecated-tags` | Tags in iD's `deprecated.json` | Replacement from that file | Deterministic for 1:1 renames |
| `platform-node-only` | Platform nodes with no platform way within 15 m (needs a distance search) | Issue only | — |
| `sidewalk-separate` | Roads with `sidewalk=*` and a parallel `footway=sidewalk` within 10 m | `sidewalk:<side>=separate`, side from geometry | Needs review |

`deprecated-tags` is a table of hundreds of iD upgrades applied together, which would be one generated
MapCSS rule per entry. Only deterministic fixes can be approved in bulk.

## Development

Requirements: JDK 25, Node 24+, Docker (for Postgres and the integration tests).

```sh
docker run -d --name osmreview-db -e POSTGRES_USER=osmreview -e POSTGRES_PASSWORD=osmreview \
  -e POSTGRES_DB=osmreview -p 127.0.0.1:5432:5432 postgres:18

./gradlew :shared:jsBrowserProductionLibraryDistribution :shared:generateTsModels
npm install

# server on :3000 and :8111, against any GOL built with GOL Tool 2.x
GOL_FILE=/path/to/czechia.gol ./gradlew :server:run
# UI with hot reload on :5173 (proxies /api, /auth, /mcp to :3000)
npm run dev
```

Tests: `./gradlew :shared:allTests :rules:test :server:test` (after `npm install`: the server build
copies the iD tagging schema from `node_modules`). Rule tests against a real GOL run when
`OSM_REVIEW_TEST_GOL` points at one (the MapCSS host tests also check that every GOQL prefilter the
compiler emits is accepted by GeoDesk and selects a superset of the matches); `DraftFlowTest`,
`McpTest`, `ScansMcpTest` and `RemoteControlTest` use Testcontainers and skip without Docker.

After changing a `@Serializable` model in `shared/`, run `./gradlew :shared:generateTsModels`; CI
fails when `web/src/generated/models.ts` is stale.

### Map

The map draws the mirror like iD: the server cuts vector tiles from the GOL
([`/api/tiles`](docs/api.md#vector-tiles)), a web worker classifies every feature (iD's way and area
colours, casings, dashes, patterns, preset icons from id-tagging-schema) and re-encodes the tile for
MapLibre. Large areas get iD's 30 px partial fill as an inset band computed on the server per display
zoom; buildings, points and address points are hidden when too dense, with iD's thresholds. Imagery,
when chosen, replaces the light grey background under the data. Above the data and below the
overlays (scope, issues, drafts) the style keeps an empty `ore-highlight-slot` layer: future highlight
layers are inserted before it.

### Layout

```
server/   Ktor app: REST, WebSocket, MCP, JOSM remote control, core (drafts, merge, OSM API)
shared/   KMP module (jvm + js): wire models, TagDiff logic, presets (resolve, match, apply), TS model generator
rules/    rule(...) DSL, GOL mirror with hot swap, rule engine, built-in rules, MapCSS host over GeoDesk
web/      Vue 3 app (MapLibre, Pinia, Tailwind), consumes the shared JS build and generated types
user-rules.example/  Example rules folder (highlights, checks, presets, smart presets, sources.json)
infra/    gol sidecar: build, update, check, swap
docs/     API reference
```

### Stack

| Layer | Choice |
| --- | --- |
| Backend | Kotlin 2.4, Ktor 3.6 on JVM 25, coroutines |
| Shared | Kotlin Multiplatform (JVM + JS): `@Serializable` models, TagDiff, generated TypeScript types |
| OSM data | GeoDesk for Java 2.1 over a GOL file, kept current by GOL Tool 2.5 (`gol update`) |
| App DB | PostgreSQL 18, Exposed 1.6, Flyway 13 |
| OSM API | Ktor client, xmlutil (osmChange), OAuth 2.0 + PKCE |
| MCP | Kotlin MCP SDK 0.16, Streamable HTTP |
| Frontend | Vue 3.5, Vite 8, TypeScript, MapLibre GL 6, Pinia, Tailwind 4 |
| Map | Vector tiles cut from the GOL by the server (own MVT encoder), styled like iD in a web worker (iD sprites, patterns and presets), light only |

## Milestones

| # | Milestone | State |
| --- | --- | --- |
| 0 | GOL spike | Done end to end on a Czechia GOL: reads (GOQL, `parents()`, distance queries, all rules < 1.2 s), `gol build --updatable`, a month of `gol update` and reading the result from Kotlin (`infra/gol/spike.sh`) |
| 1 | Skeleton and data | Monorepo, compose, Postgres, gol sidecar with update, rebuild and swap |
| 2 | Map explorer | Done |
| 3 | Rules engine | Seven rules, issue layers with counts and filters |
| 4 | Drafts and review | Done: keyboard review, tag diff, before/after, conflicts |
| 5 | OAuth and merge on dev | Done in code and tests; needs a dev OAuth app for a real upload |
| 6 | MCP server | Done |
| 7 | JOSM remote control | Done |
| 8 | More rules and production | Rules done; first real changeset pending |

Notes from the GOL spike: GeoDesk for Java 2.1.0 declares `maxMetersFrom(double, Feature)` but
throws "todo", so rules use `withinMeters()` (bbox prefilter plus exact JTS distance). Lookup by ID
scans the whole GOL (about 0.5 s for Czechia), so lookups are batched into one ID-set scan.

The `gol update` half (GOL Tool 2.5.3, Czechia extract of 2026-10-04, Apple Silicon): `gol build
--updatable` took 1 min 36 s and gave a 1.53 GB GOL; `gol update` from replication sequence 4930
(2026-10-04) to 4960 (2026-11-04) took 44 s over 373 tiles (+84,748, \*120,419, −10,284 features)
and grew the file to 3.07 GB, leaving an 18 MB `.gob` file next to it; the build leaves a
`-indexes` directory. `gol check` was clean after both. Reading both files from Kotlin
(`GolUpdateSpikeTest`) in Brno-střed went from 123,168 to 123,371 features: 271 added, 68 removed,
1,133 changed. GeoDesk adds pseudo-tags such as `geodesk:missing_members` to relations that cross
the extract boundary; they are not OSM data, so the app drops every `geodesk:` key when it reads
the mirror and again before anything is proposed or uploaded.

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Map data © OpenStreetMap contributors,
ODbL. The bundled iD tagging schema files are ISC (see NOTICE). The gol sidecar downloads GOL Tool (AGPL-3.0) at image build time; it is not part of this
repository.
