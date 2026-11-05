# OSM Review Editor

A self-hosted OpenStreetMap editor where every change is a reviewable draft, like a merge request.
You edit in the browser with iD's editing engine; QA rules, Claude (over MCP) and external QA tools
(JOSM remote control) propose changes too. Every object is approved, tweaked or skipped by a human,
and only approved changes are uploaded.

- **See the data like iD**: an iD-look MapLibre map over vector tiles that the server cuts from a
  local GeoDesk copy of OSM (light theme only).
- **Find problems fast**: MapCSS highlights with coverage legends ("how much of Opava's street length
  has sidewalks tagged") and validation scans over a city or a country, with diffs between scans.
- **Edit like in iD**: iD's editing core with iD's inspector, preset fields and validations, plus your
  own MapCSS checks, presets and smart presets (footway → crossing in one step).
- **Review like a code MR**: before/after on the map, a tag and geometry diff per object, dependencies
  between new and changed elements, then upload in dependency-safe changesets.
- **Plug into the ecosystem**: Claude works through 30 MCP tools; "Edit in JOSM" on Osmose,
  MapRoulette, OSMCha and osm.org opens objects here.

Nothing reaches OSM without a human approving each object. Claude and external tools can create
and edit drafts, never upload. The app is single-user and meant for a laptop or a home lab. The whole
interface is in Czech and English.

## Contents

- [Quick start (Docker)](#quick-start-docker)
- [Configuration](#configuration)
- [Rules folder](#rules-folder)
- [Map and highlights](#map-and-highlights)
- [Validation scans](#validation-scans)
- [Editing](#editing)
- [Drafts, review and upload](#drafts-review-and-upload)
- [Presets](#presets)
- [Language](#language)
- [Claude over MCP](#claude-over-mcp)
- [JOSM remote control](#josm-remote-control)
- [QA rules](#qa-rules)
- [How the mirror stays current](#how-the-mirror-stays-current)
- [Development](#development)
- [Status](#status)
- [Licences](#licences)

The REST, WebSocket, MCP and remote-control interfaces are documented in [docs/api.md](docs/api.md).

## Quick start (Docker)

```sh
cp .env.example .env
docker compose up -d --build
```

Set at least `DB_PASSWORD` and `TOKEN_ENCRYPTION_KEY` in `.env` first. The first start downloads the
Czechia extract from Geofabrik (about 950 MB) and builds the GOL (under 2 minutes on Apple Silicon);
the `gol` sidecar then applies daily replication diffs and rebuilds weekly. Open
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

## Configuration

Environment variables (`.env` for Docker Compose):

| Variable | Purpose |
| --- | --- |
| `DB_PASSWORD` | Postgres password (required) |
| `OSM_TARGET` | API target at first start: `dev` (default) or `prod`; later switched in Settings |
| `OSM_CLIENT_ID_DEV`, `OSM_CLIENT_SECRET_DEV` | OAuth app on the dev server |
| `OSM_CLIENT_ID_PROD`, `OSM_CLIENT_SECRET_PROD` | OAuth app on osm.org |
| `TOKEN_ENCRYPTION_KEY` | Encrypts stored OAuth tokens (`openssl rand -base64 32`); without it tokens live in memory only |
| `MCP_TOKEN` | Bearer token for `/mcp`; generated and shown in Settings when empty |
| `PUBLIC_URL` | Address you open the app at (default `http://localhost:3000`); requests for other hosts are refused |
| `OVERPASS_URL` | Overpass endpoint for `query_osm` (default `overpass-api.de`) |
| `DEFAULT_AREAS` | GeoJSON FeatureCollection of area presets (see below); Compose mounts `./config` at `/config` |
| `BING_KEY` | Optional Bing Maps key; when set, Bing aerial imagery is offered |
| `RULES_DIR` | Your rules folder; default `user-rules`, Compose mounts `./user-rules` at `/rules` |
| `SCAN_RERUN_DAYS` | After a mirror swap, re-run validation scans started in this many days (default 7, 0 turns it off) |
| `EXTRACT_URL` | Geofabrik extract the gol sidecar mirrors (default Czechia) |
| `UPDATE_INTERVAL`, `REBUILD_DAYS` | Sidecar update interval in seconds (default 86400) and full rebuild period (default 7) |
| `CONTACT` | Contact for the User-Agent sent to the OSM API |
| `PORT`, `REMOTE_CONTROL_PORT` | Ports (default 3000 and 8111) |
| `DB_URL`, `DB_USER`, `DATA_DIR`, `GOL_FILE` | Set by Compose; `GOL_FILE` points at a GOL when running without the sidecar |

**Area presets.** `DEFAULT_AREAS` points at a GeoJSON FeatureCollection. Every feature with
`properties.name` becomes a preset: a `Polygon` or `MultiPolygon` is used as is, any other feature is
looked up by name among the mirror's admin boundaries, like the built-in presets (Opava,
Opava-Kateřinky, Brno, Brno-střed, Ostrava, Olomouc, Praha). Preset names work wherever an area name
does (scans, highlights, drafts, MCP `area`); rules are clipped to the polygon. Start from
`cp config/areas.example.geojson config/areas.geojson`.

**Imagery.** The imagery list lives in the settings table and is seeded with ČÚZK orthophoto, the
ČÚZK cadastral map overlay and Esri World Imagery; Settings edits it
([API](docs/api.md#imagery-sources)). Each source has a `sourceTag` for the changeset `source` tag.
Bing aerial imagery needs `BING_KEY`: the server reads the tile URL from the Bing Maps Imagery
Metadata API at start. Microsoft allows tracing Bing imagery for OSM only in an OSM editor; Basic keys
stopped working on 30 June 2025 and Enterprise keys work until 30 June 2028, so without an Enterprise
key leave `BING_KEY` empty.

## Rules folder

Your highlights, checks and presets are plain text files in one folder that you edit in any editor,
in the app's Rules pane, or through Claude over MCP:

| Path | Kind |
| --- | --- |
| `highlights/*.mapcss` | MapCSS map highlights |
| `checks/*.validator.mapcss` | MapCSS validator checks |
| `presets/*.json` | iD-style presets and overlays of iD presets (`extends`, `apply`) |
| `presets/smart/*.json` | Smart presets: multi-step edits run in the editor |
| `sources.json` | Remote rule files, fetched read-only |

```sh
cp -R user-rules.example user-rules
```

[`user-rules.example/README.md`](user-rules.example/README.md) explains every format with one
example per kind. The examples:

| File | What it does |
| --- | --- |
| `highlights/sidewalks.mapcss` | Streets coloured by how their sidewalks are tagged, with a length legend |
| `highlights/footways.mapcss` | Sidewalk ways and crossings |
| `highlights/missing-surface.mapcss` | Streets and paths without `surface`, and segments whose surface differs from both neighbours |
| `checks/crossings.validator.mapcss` | Crossings without `crossing=*`, deprecated `crossing=zebra` |
| `checks/platforms.validator.mapcss` | Platform areas and details |
| `checks/surfaces.validator.mapcss` | Paths without `surface`, fixed from connected paths |
| `checks/street-consistency.validator.mapcss` | Odd street segments, footways cut off from the network |
| `presets/sidewalk-cz.json` | Overlay of iD's sidewalk preset with a Czech name and a default surface |
| `presets/smart/footway-crossing.json` | Turns the part of a footway that crosses a road into a crossing |

The highlights, checks and smart presets of `user-rules.example` also ship inside the server as a
read-only built-in source, so a new install already has them. A file of yours with the same path
overrides the built-in one; delete it and the built-in comes back. Copy a built-in or remote file into
your folder (Rules pane, `POST /api/rule-files/copy`, MCP `copy_rule_file`) to edit it.

The server watches the folder (on macOS the JDK polls, so edits show up within a few seconds) and
tells the UI over the WebSocket. Writes from the app and MCP are validated first and written
atomically; deletes move the file to `.trash/<timestamp>/` in the folder. Every file kind has a loader
that reports diagnostics with line and column; MapCSS `assertMatch` / `assertNoMatch` lines run as
self-tests on every load.

Remote sources in `sources.json` (`[{name, url, kind, refreshHours, licence?}]`) are cached in
`DATA_DIR/rule-cache`, refreshed when due and listed read-only as `remote/<name>.<ext>`. The
repository ships no JOSM rule files (they are GPL); you can point a source at one, for example
`https://josm.openstreetmap.de/export/HEAD/josm/trunk/resources/data/validator/unnecessary.mapcss`.

## Map and highlights

The server cuts vector tiles from the GOL ([`/api/tiles`](docs/api.md#vector-tiles)); a web worker
classifies every feature like iD (way and area colours, casings, dashes, fill patterns, partial fills
of large areas, preset icons from id-tagging-schema) and the map draws them with MapLibre. A z16 tile
in central Opava builds in about 25 ms (z15 about 75 ms) on Apple Silicon; built tiles are cached per
mirror generation. Imagery, when chosen, replaces the light grey background.

The layout follows iD: the left sidebar shows what you are working on (the inspector of the selected
feature, the editor or the review card of a draft); it opens on its own, can be resized by dragging its
right edge (300 px to half the window, remembered per browser) and hidden with `[`. A strip of map
controls on the right has zoom, "show my location" and buttons for panes that open next to it, one at a
time (`]` closes the pane or reopens the last one, `Esc` closes it):

- **Background** (`B`): imagery.
- **Map data** (`F`, not while reviewing): a quick tag filter (`highway=footway !surface`, saved as a
  highlight file with one click) and the highlight files.
- **Validation** (`I`): validation scans (see below).
- **Rules** (`L`): the rules folder by kind, a CodeMirror editor with live diagnostics, templates, save
  with conflict detection (a diff to reload or overwrite), delete to the trash, copy of built-in and
  remote files, and a live map preview of unsaved highlight edits.
- **Presets** (`P`): iD presets by category or search, your presets and smart presets (see
  [Presets](#presets)).
- **Drafts** (`D`, not in edit mode): the drafts with filters and Review/Merge/Close, and the change
  list of the open draft.
- **Settings**: account, language, API target, imagery sources, mirror status, MCP and keys.

Panels overlay the map and never move it; camera moves (fit to a feature, fly to a change) keep the
target in the part of the map they leave visible. On phones one bottom sheet shows either the sidebar
content or the open pane, with tabs to switch and a handle to collapse it.

A highlight file styles objects on the map and can carry a legend (`-ore-legend`), a coverage measure
(`-ore-stats: length|count|area`) and `-ore-dim-others` to fade the base map. Most rules compile to
MapLibre filters on the tiles; the rest (host functions such as `odd_segment()`, linked selectors) are
evaluated on the server per tile and drawn by OSM id. A legend on the map shows coverage per entry
(km, count or km² and %) for the picked area, a drawn polygon or the map view. `?hl=`, `hlq=`, `area=`
and `at=` in the URL restore the highlights, filter, area and view.

MapCSS checks and highlights can use these host functions on top of JOSM's: `odd_segment("k1;k2")`
(keys in which a street segment differs from its same-named neighbours while those agree),
`network_island_size()` (highway ways in the connected network, capped at 100), `half_width()`
(metres, from `width`, `lanes` or a default per `highway` class),
`connected_value("key", "footway;path")` (the value shared by all connected ways of those classes),
`inside("CZ,CZ-80")` / `outside(...)`, `waylength()`, `areasize()`, and the `⧉` (crossing) and `∈`
(inside) links. Traffic is right-hand.

## Validation scans

A scan runs every check file (your own, built-in and remote, or the ones you pick) and the built-in
Kotlin rules over an area, stores the issues, reports counts per group and severity, shows them as
markers you can filter and fly to, and diffs against the previous scan of the same area and rule set
(new, resolved, unchanged). After the mirror is updated, areas scanned in the last `SCAN_RERUN_DAYS`
days are scanned again. Selected fixes become a draft that opens in review, deletes included.

## Editing

"Edit map" (map zoom 15 or more, iD's 16) loads the editor chunk: iD's editing core
(`@openstreetmap/id` 2.42.3, about 680 kB gzip, fetched only on entering edit mode) drives the edits,
while MapLibre handles drawing and interaction. Live data for the view is fetched in z15 tiles through
the read-only proxy [`/api/osm/map`](docs/api.md#live-osm) of the current API target, and the edited
graph is drawn in iD's style above the tiles.

| Input | Action |
| --- | --- |
| Click, Shift+click | Select, add to the selection; right-click opens iD's operations with their availability and reasons |
| Shift+drag | Lasso: select the loaded nodes inside the drawn shape |
| Drag a node, drag a midpoint | Move it (snaps to nodes and ways, Alt to not snap), add a vertex |
| `1` / `2` / `3` | Draw a point, line or area; click an existing node or way to connect; click the last node or press Enter to finish |
| `X` `D` `C` `V` `Q` `O` `S` `M` `R` `A` `E` | Split, disconnect, combine, reverse, square, circularize, straighten, move, rotate, continue, extract |
| `Delete`, `⌘⌫` | Delete |
| `⌘Z`, `⇧⌘Z` (or `⌘Y`), `Esc`, `⌘S` | Undo, redo, cancel or deselect, save |
| `/`, `[` `]` | Focus search, hide or show the sidebar, close or reopen the pane |
| `B` `F` `I` `L` `P` | Background, map data, validation, rules and presets panes (`D` drafts only outside edit mode) |

**Inspector.** Selecting a feature opens an inspector like iD's sidebar: the preset with its icon and
name, "Change preset", the preset's fields from id-tagging-schema and the raw tag editor. Edits are
undoable steps; a preset change applies to the whole multi-selection. Outside edit mode, editing a
field starts edit mode with the feature selected (below zoom 15 the change goes to a manual draft).

**Choosing a preset after drawing.** When you finish a new point, line or area that has no tags
(other than `area=yes`), the inspector opens the preset picker, as iD does. Pick a preset to tag the
feature, or dismiss the picker and tag it by hand. The prompt goes away when the feature gets tags,
is deleted or undone, or you select something else. Drawing from the Presets pane ("Draw") creates
the feature with that preset's tags right away.

**Validation.** Shortly after each edit (not while drawing or dragging) the editor validates the
features you touched: iD's own validations (crossing ways, almost junctions, disconnected ways,
missing and outdated tags, unsquare buildings and the rest) and your MapCSS check files (the active
ones of the Validation pane), run in the browser over the edited graph. Issues show in a collapsible
issues panel docked at the bottom of the sidebar and as markers on the map; clicking one selects
the feature. Fix buttons run iD's fixes or the check's `fixAdd`/`fixRemove`/`fixChangeKey` and
`fixDeleteObject`, each as one undoable step. Warnings can be ignored for the session. Saving first
lists the errors and warnings of the edits, like iD's save screen. In the browser only the loaded
tiles are seen, so network checks at the edge of the loaded data can differ from a scan, and
`inside()` / `outside()` match nothing.

**Saving.** Save turns the session into an osmChange and imports it into a new draft (or back into
the draft opened with "Edit draft"), then opens the review. Elements with open changes in other
drafts can be moved over after a confirm. Nothing is uploaded on save.

Developer note: the editor is wrapped by one adapter, `web/src/editor/id/adapter.ts`, with a context
shim instead of iD's UI. The "needs a preset" prompt comes from the editor store
(`editor.needsPreset`, `editor.onNeedsPreset`, `editor.clearNeedsPreset`), which
`EditInspector.vue` uses to open the picker. A change from the history updates only the changed
features in the GeoJSON sources: dragging a vertex with about 2,100 features drawn went from 18 fps
(full `setData` per frame) to 62 fps.

## Drafts, review and upload

A draft holds changes: tag changes and full osmChange edits (new, moved and deleted nodes, way node
lists, relation members). They come from the editor, rules and scans, bulk preset application, Claude
and JOSM remote control. The review card goes through the changes one by one with the matched
preset's icon and name, a tag diff and before/after geometry on the map; edit mode in the card uses
the full preset editor. Keys: `↵` approve, `E` edit, `S` skip, `R` reject (with a comment Claude can
read), `O` open in iD, `J`/`K` next and previous, `Space` toggle before/after, `F` zoom to the change (so the Map data pane has no key while reviewing).

Changes that depend on each other (a way and its new nodes, a delete of a node a way no longer uses)
are reviewed per object with their dependencies and always uploaded together. Before upload every
change is checked against the live version: tag-only changes are rebased automatically, geometry
conflicts and stale deletes are marked for a three-way resolve. The merge splits approved changes into
changesets of up to 500 by rule and area, with `comment` and `source` tags from the draft and the
imagery used during review ([API](docs/api.md#merge-behaviour)).

## Presets

The server ships id-tagging-schema 7.3.0 (presets, fields, categories, defaults, deprecated and
discarded tags, `en` and `cs` translations). Presets from the rules folder are layered over iD's: an
overlay `extends` an iD preset and lists only what it changes, `{presetId}` references work as in
id-tagging-schema, and matching follows iD's scoring, with your preset winning ties.

`name` in a preset or smart preset file is a string or a map of locale to name:

```json
{ "id": "custom/sidewalk-cz", "extends": "highway/footway/sidewalk", "name": { "cs": "Chodník", "en": "Sidewalk (Czech)" } }
```

The app picks the current language, then `en`, then the first entry; a string may still be a
`{presetId}` reference to another preset's translated name.

The Presets pane and the inspector's picker search names, terms and aliases without diacritics (in
Czech also the English names), with categories, recently used presets and your presets first. From a
preset you can import it as an overlay file, detach an overlay into a full copy, create a preset from
the selected feature's tags, apply it to the selection, draw with it, or **bulk apply** it to the
features of a highlight or picked scan issues: a dry run shows the count and a per-object tag preview,
then a draft is created for review.

**Smart presets** (`presets/smart/*.json`) are multi-step edits that match a feature with a MapCSS
selector, ask for parameters and run steps such as `findCrossing`, `ensureSharedNode`, `splitAround`
and `tag`. The bundled `footway-crossing` finds the road a footway crosses, joins them at a shared
node, cuts the crossing piece out of the footway at the road's half width on both sides and tags the
piece `footway=crossing` and the node `highway=crossing` with the chosen `crossing` type. Matching smart
presets appear in the inspector and the picker; Preview draws the result, Apply keeps it as one undo
step. They run only in the editor, on iD's graph; there is no MCP tool or REST endpoint for them.

## Language

The whole interface is in Czech and English, switched with one `CS`/`EN` control in the top bar or in
Settings. The default comes from the browser language; the choice is stored per browser in
`localStorage` (`ore.locale`). The same switch sets preset and field labels from id-tagging-schema's
translations, iD's own strings in the editor (operations, validations, fixes) and localized names of
your presets and smart presets.

## Claude over MCP

The server exposes MCP (Streamable HTTP) at `http://localhost:3000/mcp`, protected by a bearer token
(`MCP_TOKEN`, or generated at first start). Settings shows the token and the command:

```sh
claude mcp add --transport http osm-review http://localhost:3000/mcp \
  --header "Authorization: Bearer <token>"
```

Then ask, for example: "fix deprecated crossing tags in Opava-Kateřinky". Claude runs rules and
queries, creates a draft with a reason per change, shows you what it found, and reads your rejection
comments to revise it.

| Area | Tools |
| --- | --- |
| Rules | `list_rules`, `run_rule` |
| Scans | `run_scan`, `get_scan`, `diff_scans`, `draft_from_scan` |
| Highlights | `list_highlights`, `highlight_stats`, `evaluate_highlight` |
| Data | `query_osm` (GOQL on the mirror or Overpass, at most 500 objects), `get_object` (with parents and connected ways, from the mirror or live) |
| Drafts | `create_draft`, `propose_changes` (tags, up to 2,000), `propose_geometry` (osmChange-style elements, up to 500), `update_change`, `list_drafts`, `get_draft` |
| Rules folder | `list_rule_files`, `read_rule_file`, `validate_rule_file`, `write_rule_file`, `copy_rule_file`, `delete_rule_file` |
| Presets | `search_presets`, `get_preset`, `match_preset`, `import_preset`, `detach_preset`, `apply_preset_bulk` (dry run, then a draft of up to 5,000 objects) |
| View | `push_view`: move your open tab to an area or point, switch highlights, open a scan or draft, select objects; you see a toast "Claude changed the view" with undo |

Arguments and results of every tool are in [docs/api.md](docs/api.md#mcp-mcp). `area` is an area
preset, admin boundary or place name, a bbox `[west, south, east, north]` or a GeoJSON
Polygon/MultiPolygon (at most 10° across and 20,000 vertices). Resources: `draft://{id}`,
`rule://{id}`, `area://{name}`. No tool uploads, and smart presets have no tool.

## JOSM remote control

The app listens on `127.0.0.1:8111` like JOSM (`/version`, `/zoom`, `/load_and_zoom`,
`/load_object`, `/import`, `/load_data`, `/add_node`, `/imagery`), so "Edit in JOSM" links fly the open
tab to the object. If no tab is open, the command is queued and replayed when one connects. Real JOSM
cannot run at the same time (same port).

- `addtags` on `/load_and_zoom` and `/load_object` creates one one-change draft per selected object,
  ready to approve; `changeset_comment` and `changeset_source` become the changeset's tags.
- `/load_object` with `relation_members=true` also frames the relation's members; objects missing
  from the mirror are fetched live.
- `/import` and `/load_data` import tag changes of existing objects and new tagged nodes. They do not
  import geometry: moved nodes, changed way node lists or relation members, deletions, new ways and
  relations and untagged new nodes are skipped and counted in the response and the draft description.
  For geometry, use the in-browser editor or `POST /api/drafts/from-osmchange`.

## QA rules

Checks are MapCSS files in the rules folder (see the examples above). Three rules stay in Kotlin
([`rules/`](rules/src/main/kotlin/eu/tiducto/osmreview/rules/BuiltInRules.kt)) because MapCSS cannot
express them well:

| Rule | Finds | Fix | Confidence |
| --- | --- | --- | --- |
| `deprecated-tags` | Tags in iD's `deprecated.json` | Replacement from that file | Deterministic for 1:1 renames |
| `platform-node-only` | Platform nodes with no platform way within 15 m | Issue only | — |
| `sidewalk-separate` | Roads with `sidewalk=*` and a parallel `footway=sidewalk` within 10 m | `sidewalk:<side>=separate`, side from geometry | Needs review |

Only deterministic fixes can be approved in bulk.

## How the mirror stays current

The `gol` sidecar keeps two GOL files, `a.gol` and `b.gol`, in the shared volume; `/data/current`
names the one the app reads. Each day it copies the current file, runs `gol update` and `gol check`
on the copy and publishes it atomically. The app notices within 15 seconds, swaps the new file in,
drops rule caches and tells the UI to refresh. Because `gol update` is marked experimental, a full
rebuild runs every `REBUILD_DAYS` (7). After every run the sidecar writes `/data/status.json`, shown
as `mirror.sidecar` in `/api/status` ([API](docs/api.md#sidecar-status)).

Disk space: `gol update` only appends, so the GOL grows until the weekly rebuild compacts it. A fresh
Czechia GOL is about 1.5 GB and doubled to 3.1 GB after a month of daily updates; during an update the
volume holds three GOLs, and a rebuild also needs the extract and an index directory. Plan for at least
10 GB free with the default rebuild period.

## Development

Requirements: JDK 25, Node 24+, Docker (for Postgres and the integration tests).

```sh
docker run -d --name osmreview-db -e POSTGRES_USER=osmreview -e POSTGRES_PASSWORD=osmreview \
  -e POSTGRES_DB=osmreview -p 127.0.0.1:5432:5432 postgres:18
./gradlew :shared:jsBrowserProductionLibraryDistribution :shared:generateTsModels
npm install
GOL_FILE=/path/to/czechia.gol ./gradlew :server:run
npm run dev
```

The server runs on `:3000` and `:8111` against any GOL built with GOL Tool 2.x; `npm run dev` serves
the UI with hot reload on `:5173` and proxies `/api`, `/auth` and `/mcp` to `:3000`. Run `npm install`
before building the server: the Gradle task `:server:copyIdTaggingSchema` copies id-tagging-schema
from `node_modules`.

Tests:

```sh
./gradlew :shared:allTests :rules:test :server:test
npm test -w web
```

- Rule tests against a real GOL run when `OSM_REVIEW_TEST_GOL` points at one; they also check that
  every GOQL prefilter the MapCSS compiler emits is accepted by GeoDesk and selects a superset of the
  matches.
- `DraftFlowTest`, `GeometryDraftTest`, `McpTest`, `ScansMcpTest`, `RuleFilesMcpTest`,
  `PresetBulkTest`, `RemoteControlTest` and the other integration tests use Testcontainers and skip
  without Docker.
- `npm test -w web` runs Vitest (jsdom), including the iD editing core against an Opava-Kateřinky
  fixture, the in-browser MapCSS host, smart presets and the i18n catalogs.
- `HostParityTest` compares the server's and the browser's `odd_segment` and `network_island_size`
  on a small GOL of Kateřinky's highways through
  `web/src/editor/validation/fixtures/host-parity.json`; after changing either host, regenerate it with
  `UPDATE_HOST_PARITY=1 ./gradlew :rules:test --tests '*HostParityTest*'`.

After changing a `@Serializable` model in `shared/`, run `./gradlew :shared:generateTsModels`; CI
fails when `web/src/generated/models.ts` is stale.

### Layout

```
server/   Ktor app: REST, WebSocket, MCP, JOSM remote control, vector tiles, drafts, merge, OSM API
shared/   KMP module (jvm + js): wire models, TagDiff, MapCSS engine, presets, TS model generator
rules/    GOL mirror with hot swap, rule engine, built-in rules, MapCSS host over GeoDesk
web/      Vue 3 app: MapLibre map, iD editor adapter, inspector, presets, review, i18n (cs, en)
user-rules.example/  Example rules folder, also shipped as the built-in rules
infra/    gol sidecar: build, update, check, swap
config/   Example area presets
docs/     API reference
```

### Stack

| Layer | Choice |
| --- | --- |
| Backend | Kotlin 2.4, Ktor 3.6 on JVM 25, coroutines |
| Shared | Kotlin Multiplatform (JVM + JS): `@Serializable` models, TagDiff, MapCSS, presets, generated TypeScript types |
| OSM data | GeoDesk for Java 2.1 over a GOL file, kept current by GOL Tool 2.5 (`gol update`) |
| App DB | PostgreSQL 18, Exposed 1.6, Flyway 13 |
| OSM API | Ktor client, xmlutil (osmChange), OAuth 2.0 + PKCE |
| MCP | Kotlin MCP SDK 0.16, Streamable HTTP |
| Frontend | Vue 3.5, Vite 8, TypeScript, MapLibre GL 6, Pinia, Tailwind 4, CodeMirror 6 |
| Editor | `@openstreetmap/id` 2.42.3 editing core (lazy chunk), id-tagging-schema 7.3.0 |
| Map | Vector tiles cut by the server (own MVT encoder), styled like iD in a web worker, light only |

## Status

Single-user, local or home lab. Everything below is built and covered by tests. The upload path is
tested against a mocked OSM API; a real upload needs an OAuth app on the dev server, and the first
real production changeset is still to come.

| Area | State |
| --- | --- |
| GOL mirror, sidecar with update, rebuild and hot swap | Done |
| iD-look map from server vector tiles | Done |
| Rules engine, MapCSS engine and checks, built-in Kotlin rules | Done |
| Highlights with coverage legends | Done |
| Validation scans with diffs and re-runs after mirror updates | Done |
| Drafts, per-object review, conflicts, dependency-safe merge | Done |
| Geometry drafts (osmChange import and export) | Done |
| In-browser editor (iD core), inspector with preset fields, in-editor validation | Done |
| Presets with overlays, bulk apply; smart presets | Done |
| Czech and English UI | Done |
| MCP server (30 tools, `push_view`) | Done |
| JOSM remote control | Done; imports tag changes and new tagged nodes, not geometry |
| First real changeset on production | Pending |

Notes from the GOL spike: GeoDesk for Java 2.1.0 declares `maxMetersFrom(double, Feature)` but
throws "todo", so rules use `withinMeters()` (bbox prefilter plus exact JTS distance). Lookup by ID
scans the whole GOL (about 0.5 s for Czechia), so lookups are batched into one ID-set scan. All rules
ran under 1.2 s on Czechia. `gol build --updatable` (GOL Tool 2.5.3, Czechia, Apple Silicon) took
1 min 36 s for a 1.53 GB GOL; a month of `gol update` (sequences 4930 to 4960, 373 tiles) took 44 s
and grew it to 3.07 GB, with `gol check` clean after both. GeoDesk adds pseudo-tags such as
`geodesk:missing_members` to relations that cross the extract boundary; the app drops every
`geodesk:` key when it reads the mirror and again before anything is proposed or uploaded.

## Licences

OSM Review Editor is licensed under Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Map
data © OpenStreetMap contributors, ODbL. The server bundles id-tagging-schema files (ISC). The web
bundle includes `@openstreetmap/id` (iD, ISC: its editing core, locale strings, icon sprites and fill
patterns), id-tagging-schema (ISC), MapLibre GL (BSD-3-Clause) and clipper2-ts (Boost Software
License 1.0); iD's sprites carry icons under CC0 and CC BY 4.0 (see NOTICE). The example rules are
Apache-2.0; remote rule sources keep their own licences and are not redistributed. The gol sidecar
downloads GOL Tool (AGPL-3.0) when its image is built; it is not part of this repository.
