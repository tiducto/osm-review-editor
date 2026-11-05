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
> ([API](docs/api.md#geometry-drafts-osmchange)). The map has an in-browser editor built on iD's
> editing engine that saves into such drafts (see [Editing](#editing)), with iD's validations and your
> MapCSS checks on the edited features.

## Quick start (Docker)

```sh
cp .env.example .env      # set DB_PASSWORD and TOKEN_ENCRYPTION_KEY at least
docker compose up -d --build
```

The first start downloads the Czechia extract from Geofabrik (about 950 MB, so a few minutes on a
typical connection) and builds the GOL (under 2 minutes on Apple Silicon); from then on the app clips
the planet's minutely diffs to Czechia and the `gol` sidecar applies them every 3 minutes, with a
weekly full rebuild (see [How the mirror stays current](#how-the-mirror-stays-current)). Open
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
| `EXTRACT_URL` | Geofabrik extract the gol sidecar builds from (default Czechia) |
| `MIRROR_UPDATE_SECONDS` | Sidecar cycle: update the idle GOL and flip (default 180) |
| `REBUILD_DAYS` | Full rebuild from `EXTRACT_URL` every this many days (default 7) |
| `REPLICATION_ENABLED` | Run the minutely clipper in the app (compose sets `true`; default `false` for `./gradlew :server:run`) |
| `REPLICATION_SOURCE_URL` | Upstream replication feed (default `https://planet.openstreetmap.org/replication/minute`) |
| `REPLICATION_INTERVAL_SECONDS`, `REPLICATION_BATCH` | Clipper polling (60 s) and diffs per batch (60) |
| `REPLICATION_AREA`, `REPLICATION_BUFFER_METERS` | Clip area: empty for the shipped Czechia polygon, a `.poly`/GeoJSON path or `mirror:CZ`; buffer (2000 m) |
| `REPLICATION_DIR`, `REPLICATION_KEEP_DAYS` | Local replication directory (default `DATA_DIR/replication`) and how long diffs are kept (3 days) |
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
| `presets/smart/*.json` | Smart presets: multi-step edits run in the editor |
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

The highlights, checks and smart presets in `user-rules.example` also ship inside the server as a
read-only built-in source, so a new install with an empty `RULES_DIR` already has them. They are
listed under their usual paths (`highlights/sidewalks.mapcss`, `checks/surfaces.validator.mapcss`,
`presets/smart/footway-crossing.json`, ...) with `origin.builtIn` and are used by highlights,
validation scans and the smart preset list like your own files. A file of yours
with the same path overrides the built-in one; delete it and the built-in comes back. Built-in files
cannot be deleted; copy one into your folder (`POST /api/rule-files/copy`, MCP `copy_rule_file`) to
edit it. The same copy action turns a remote file into a local one.

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

A validation scan runs every check file (your own, built-in and remote ones) and the built-in
Kotlin rules over an area, stores the issues, reports counts per group and severity, serves them as GeoJSON
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

The server ships `presets.json`, `fields.json`, `preset_categories.json`, `preset_defaults.json`,
`deprecated.json`, `discarded.json` and the `en` and `cs` translations of
[id-tagging-schema](https://github.com/openstreetmap/id-tagging-schema) 7.3.0 (ISC), about 1.6 MB. `npm install` fetches the package (`devDependencies` of the root `package.json`, exact
version), and the Gradle task `:server:copyIdTaggingSchema` copies the minified files and its
`package.json` (the recorded version) into the server's resources, so run `npm install` before
building the server. The web app gets the same files from the server at
`/api/presets/id-schema/{file}` ([API](docs/api.md#presets)).

Presets from the rules folder are layered over iD's: an overlay `extends` an iD preset and lists only
what it changes, `{presetId}` references work as in id-tagging-schema, and matching follows iD
(exact tag at the preset's `matchScore`, `*` at half, `addTags` bonus, geometry `point`, `vertex`,
`line`, `area` or `relation`), with your preset winning ties.

### How the mirror stays current

The mirror is minutes behind OSM, not a day. Two parts cooperate over the shared `goldata` volume:

1. **Clipper (in the app).** Every minute (`REPLICATION_INTERVAL_SECONDS`) it reads
   `state.txt` of the planet's minutely replication feed (`REPLICATION_SOURCE_URL`), downloads every
   new `AAA/BBB/CCC.osc.gz` in order (batches of `REPLICATION_BATCH`, one request at a time, with the
   app's User-Agent) and keeps only what concerns the mirror:
   - every change to an element whose id is already in the GOL (one batched ID-set scan of the open
     mirror per batch) or that the clipper kept before (`tracked-ids.bin`, so a node created a minute
     ago and moved now is still kept even though the GOL has not caught up yet);
   - created, modified and deleted nodes inside the clip area plus `REPLICATION_BUFFER_METERS`
     (default 2,000 m). The area is the Geofabrik extract polygon shipped with the server
     (`czech-republic.poly`), a `.poly` or GeoJSON file named by `REPLICATION_AREA`, or
     `REPLICATION_AREA=mirror:CZ` for the mirror's own `admin_level=2` boundary with that ISO code;
   - ways that reference a kept or existing node (always with their full `nd` list), plus the nodes
     of kept ways that appear in the same diff, so `gol update` finds their coordinates;
   - relations with a kept or existing member, repeated until nothing changes.

   It writes the clipped diffs to `/data/replication` in the replication layout, with OSM's sequence
   numbers and timestamps (`AAA/BBB/CCC.osc.gz`, `AAA/BBB/CCC.state.txt`, then `state.txt` last, all
   atomically), and keeps `clipper.json` (first sequence, source, area). User names and uids are not
   copied. A restart resumes from `state.txt`. When the GOL is older than the oldest local diff (first
   start, a long outage, or `REPLICATION_SOURCE_URL` changed), the clipper re-bases: it finds the
   upstream sequence 15 minutes before the GOL's data time, empties the directory and starts there,
   working off the backlog in batches. Diffs older than `REPLICATION_KEEP_DAYS` (3) are deleted,
   but never one the GOL pair may still need.
2. **Sidecar (`gol` container).** It keeps two GOLs, `a.gol` and `b.gol`; `/data/current` names the
   one the app reads. Every `MIRROR_UPDATE_SECONDS` (default 180) it serves `/data` on
   `127.0.0.1:8189` inside its container (busybox `httpd`) and runs
   `gol update <idle>.gol http://127.0.0.1:8189/replication` on the idle file in place, then
   `gol check`, deletes the `-update.gob` and `-indexes` leftovers and rewrites `/data/current`
   atomically. The app polls every 5 seconds, opens the new file, swaps it in under the write lock,
   closes the old one, drops rule caches and writes `/data/released`
   (`{"open":"b.gol","released":"a.gol","generation":7,"at":"…"}`). The sidecar updates a file
   only after the app has acknowledged that it opened the other one: GeoDesk holds a shared lock
   while a GOL is open and `gol update` needs an exclusive one. Without an ack for
   `RELEASE_TIMEOUT_SECONDS` (600) it tries anyway, and `gol update` refuses a file that is still
   open. When nothing new arrived it skips the cycle without flipping.

`gol update` scans every tile on each run, so each cycle has a fixed cost of roughly 10 to 30
seconds for Czechia plus the changes; with the default cadence the mirror's data is usually 3 to 6
minutes old. `POST /api/mirror/update-now` (or the MCP tool `update_mirror_now`) starts a cycle at
once. The weekly full rebuild stays (`REBUILD_DAYS`, default 7): the sidecar builds the idle file
from a fresh Geofabrik extract with `--updatable`, flips to it, waits for the ack and copies it over
the other file. The rebuilt GOL carries Geofabrik's replication URL, so its next `gol update` with
the local URL finds its start sequence by the data timestamp, which the clipper keeps available
(it re-bases if not).

Disk space for the `goldata` volume: two GOLs (about 1.5 GB each for Czechia right after a build;
`gol update` appends rewritten tiles and punches holes into the old pages, so each stays near its
size between rebuilds), the replication directory (clipped Czech minutely diffs, a few MB per day,
three days kept), and during the weekly rebuild the 950 MB extract, a third GOL being built and its
index work directory. Plan for at least 8 GB free.

Failure modes:

- **Clipper lag.** `mirror.replication.lagSeconds` and `backlog` grow when the planet feed is
  unreachable or the app was down; it catches up in batches. `mirror.replication.error` has the
  last error. The sidecar meanwhile reports `result: "current"` (nothing new).
- **Missing nodes.** A GOL keeps no area, so the clipper decides what is relevant. Untagged way nodes
  are not GeoDesk features and their ids cannot be looked up, so a node moved outside the buffer
  that belongs to a way inside the area keeps its old position, and a new way that only reuses
  existing untagged nodes is dropped, until the weekly rebuild. Ways whose nodes could not be
  resolved get `geodesk:missing_nodes`; `infra/gol/live-spike.sh` counts them before and after.
- **Failed update or check.** The current GOL stays; a file that fails `gol check` is replaced by a
  copy of the current one before the next cycle. `mirror.sidecar.error` has the tool's last lines.
- **App not acknowledging.** The sidecar waits (`phase: "idle"`, `result: "waiting"`) and retries.

After every step the sidecar atomically rewrites `/data/status.json`, which the app serves as
`mirror.sidecar` in `/api/status` and pushes as the `mirror_status` WebSocket event:

```json
{
  "lastRunAt": "2026-11-05T22:30:12Z",
  "lastRunKind": "update",
  "result": "ok",
  "error": null,
  "lastFullBuildAt": "2026-11-01T02:10:00Z",
  "nextFullBuildDue": "2026-11-08T02:10:00Z",
  "replicationSequence": 7360230,
  "replicationTimestamp": "2026-11-05T22:46:03Z",
  "current": "b.gol",
  "phase": "idle",
  "cycleStartedAt": null,
  "lastCycleSeconds": 27,
  "nextCycleAt": "2026-11-05T22:53:12Z",
  "availableSequence": 7360232,
  "dataTimestamp": "2026-11-05T22:46:03Z",
  "lagSeconds": 249,
  "intervalSeconds": 180
}
```

`replicationSequence` and `dataTimestamp` come from the current GOL's header. A failed first build
is retried every 15 minutes (`RETRY_SECONDS`). Without the sidecar the file is absent and
`mirror.sidecar` is `null`. Edits you upload stay visible as an overlay until the mirror's data
time passes the upload (`GET /api/overlay`, [API](docs/api.md#overlay-of-own-uploads)).

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
| `update_mirror_now` | Ask the gol sidecar to update the mirror now; returns the mirror status (data time, lag, phase) or fails while a cycle is running |
| `push_view` | Move your open browser tab: fly to an `area` or a `center` + `zoom`, add or replace active `highlights`, open a `scan_id` or `draft_id`, `select` objects (framed when nothing else frames the view). You see a toast "Claude changed the view" with its `message` and undo; queued until a tab connects |

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

For a minutes-fresh mirror without Docker, `infra/gol/local-mirror.sh` seeds an alternating pair
`data/live/a.gol` and `b.gol` from an updatable GOL (APFS clones, so no extra disk at first) and runs
the sidecar loop natively (needs the `gol` binary, `jq`, and `python3` or `busybox` for the local
HTTP server); start the server against the pair in a second terminal, which runs the clipper:

```sh
infra/gol/local-mirror.sh /path/to/gol data/spike/after.gol
DATA_DIR=data/live REPLICATION_ENABLED=true ./gradlew :server:run
```

`./gradlew :server:runClipper --args="--gol <file.gol> --out <dir> --max 20"` runs the clipper once
(20 minutely sequences past the GOL's data time); `--header <file.gol>` prints a GOL's replication
sequence, timestamp and URL. `infra/gol/live-spike.sh /path/to/gol data/spike/after.gol` checks the
whole chain against the real `gol` on copies in `data/live-spike`: clipping, `gol update` from the
local HTTP feed (first by timestamp, then incrementally), `gol check`, the lock refusal while a JVM
holds the file, both files of the pair, file size and `geodesk:missing_nodes`, printing `PASS`/`FAIL`
lines and `RESULT PASS` at the end.

Tests: `./gradlew :shared:allTests :rules:test :server:test` (after `npm install`: the server build
copies the iD tagging schema from `node_modules`). Rule tests against a real GOL run when
`OSM_REVIEW_TEST_GOL` points at one (the MapCSS host tests also check that every GOQL prefilter the
compiler emits is accepted by GeoDesk and selects a superset of the matches); `DraftFlowTest`,
`McpTest`, `ScansMcpTest` and `RemoteControlTest` use Testcontainers and skip without Docker. Web tests: `npm test -w web` (Vitest, jsdom; they load the iD
editing core against an Opava-Kateřinky fixture). `HostParityTest` (rules) runs the server's MapCSS
host on a small GOL of Kateřinky's highways and compares `odd_segment` and `network_island_size` with
`web/src/editor/validation/fixtures/host-parity.json`, which the browser host's Vitest checks against
the same data; after changing either host, regenerate it with `UPDATE_HOST_PARITY=1 ./gradlew
:rules:test --tests '*HostParityTest*'`.

After changing a `@Serializable` model in `shared/`, run `./gradlew :shared:generateTsModels`; CI
fails when `web/src/generated/models.ts` is stale.

### Map

The map draws the mirror like iD: the server cuts vector tiles from the GOL
([`/api/tiles`](docs/api.md#vector-tiles)), a web worker classifies every feature (iD's way and area
colours, casings, dashes, patterns, preset icons from id-tagging-schema) and re-encodes the tile for
MapLibre. Large areas get iD's 30 px partial fill as an inset band computed on the server per display
zoom; buildings, points and address points are hidden when too dense, with iD's thresholds. Imagery,
when chosen, replaces the light grey background under the data. Above the data and below the
overlays (scope, issues, drafts) the style keeps an empty `ore-highlight-slot` layer, and highlight
layers are inserted before it.

The left panel has four tabs:

- **Map**: imagery, a quick tag filter (`highway=footway !surface`, drawn at once and saved as a
  highlight file with one click) and the highlight files. Each active file is drawn per rule with its
  colour, width, dashes, casing, fill, node symbols and text; MapCSS cascading is kept by building
  each paint property as a `case` over the rules. Compiled rules are filters on the tiles (below z15
  a second tile source asks for the extra keys the filters read); the other rules are evaluated on
  the server per z13 tile of the view (zoom 12 or closer, cached per file hash) and drawn by OSM id.
  `-ore-dim-others` lays a translucent background over the base map under the highlights. A legend on
  the map shows coverage per legend entry (km, count or km² and %) for the area from the area
  picker, the drawn polygon or the map view (refreshed on pan). `?hl=`, `hlq=`, `area=` and `at=` in
  the URL restore the highlights, filter, area and view.
- **Validate**: pick the area, check files (local, built-in, remote) and built-in Kotlin rules
  (`deprecated-tags`, `platform-node-only`, `sidewalk-separate`), run a scan, read counts per group
  and severity, filter the issues, fly to each one, see the markers and the diff with the previous
  scan (new, resolved, unchanged), and turn selected fixes into a draft that opens in review.
- **Rules**: the rules folder by kind, an editor (CodeMirror 6, MapCSS and JSON) with live validation
  as inline markers and a list, save with the file's hash (a conflict shows a diff to reload or
  overwrite), templates for highlights, checks, preset overlays and smart presets, delete to the
  trash, copy of built-in and remote files into the folder, and a live map preview of unsaved
  highlight edits. Everything reloads on `rules_changed`.
- **Presets**: iD presets by category or search, your presets and smart presets. Import an iD
  preset as an overlay file (opens in the Rules editor), detach an overlay, create a preset from the
  selected feature's tags, apply a preset to the selection, draw a point, line or area with it, or
  bulk apply it (see [Presets in the editor](#presets-in-the-editor)).


### Editing

"Edit map" (zoom 16 or more in iD terms, our tile zoom 15) loads the lazy editor chunk: iD's editing
core (`@openstreetmap/id` 2.42.3, pinned) runs behind one adapter, `web/src/editor/id/adapter.ts`,
with a context shim instead of iD's UI; its presets and translations come from
`/api/presets/id-schema/`. Live data for the view is fetched in z15 tiles through the read-only
proxy [`/api/osm/map`](docs/api.md#live-osm) of the current API target and merged into iD's graph as
you pan. The graph is drawn with the same iD style as GeoJSON sources above the tiles, and tile
features with the same ids are hidden, so you always see the edited state.

| Input | Action |
| --- | --- |
| Click, Shift+click | Select, add to selection; right-click opens iD's operations with their availability and reasons |
| Shift+drag | Lasso: select the loaded nodes (points and vertices) inside the drawn shape |
| Drag a node or vertex, drag a midpoint | Move it (snaps to nodes and ways, Alt to not snap), add a vertex |
| `1` / `2` / `3` | Draw a point, line or area; clicking an existing node or way connects to it; click the last node or Enter to finish |
| `X` `D` `C` `V` `Q` `O` `S` `M` `R` `A` `E` | Split, disconnect, combine, reverse, square, circularize, straighten, move, rotate, continue, extract |
| `Delete`, `⌘⌫` | Delete |
| `⌘Z`, `⇧⌘Z`, `Esc`, `⌘S` | Undo, redo, cancel or deselect, save |

Save turns iD's history into an osmChange and imports it into a new draft (or, after "Edit draft" in
the review, back into that draft with `replace`), then opens the review. Elements with open changes in
other drafts are moved over with `takeOver` after a confirm. The editor keeps the ids the server
assigned and never reuses a negative id the draft already uses. "Edit draft" loads the draft's
osmChange over the live data, so a stale modify keeps its old version and comes back as a conflict.
The editor chunk adds about 680 kB gzip and is only fetched on entering edit mode.

**Validation.** About 350 ms after each edit (not while drawing, dragging, moving or rotating) the
editor validates the features touched by the session, like iD's issues panel:

- iD's own validations through `coreValidator` (crossing ways, almost junctions, disconnected ways,
  close nodes, missing and outdated tags, mismatched geometry, impossible oneways, unsquare buildings
  and the rest; `help_request`, `incompatible_source`, `maprules` and `osm_api_limits` are left out).
  Only issues on features you edited are listed (`what: 'edited'`). iD's "data loaded here" checks ask
  the editor's tile loader.
- Your MapCSS check files: the active ones of the Validate tab (by default every check file without
  errors, local, built-in and remote), parsed by the shared MapCSS engine and run in the browser over
  iD's graph with `web/src/editor/validation/mapcssHost.ts`. It provides parents and children with
  roles and indexes, `:closed`, `:unconnected`, `:connection`, `⧉` (proper segment crossings, not at
  shared nodes), `∈`, `waylength()`, `areasize()`, `half_width()`, `connected_value()`,
  `odd_segment()` and `network_island_size()` with the server host's semantics (only linear ways
  count as neighbours, like GOQL `w`; the island search stops at 100). Only the loaded tiles are seen,
  so islands and neighbours at the edge of the loaded data can differ from a scan. `inside()` and
  `outside()` have no region index in the browser and match nothing; traffic is right-hand.

Issues show in a panel (the warning button in the edit toolbar, with the count of errors and
warnings) and as markers on the map; clicking a marker or an issue selects the feature. Fix buttons
run iD's fix actions (connect, add a bridge or tunnel, extend to the other way, upgrade tags, ...) and
for MapCSS checks apply the check's `fixAdd`/`fixRemove`/`fixChangeKey` as one tag edit or delete the
feature for `fixDeleteObject`. Every fix is one undoable step in the history, labelled after the
issue. Warnings can be ignored for the session. Saving first shows the errors and warnings of the
edits, like iD's save screen: "Review issues" opens the panel at the first one, "Save anyway"
continues.

**Choosing a preset.** Finishing a new line or area (or placing a point) without tags other than
`area=yes` asks for a preset, like iD. The editor store exposes this for the Inspector:

- `editor.needsPreset`: `{entityId, geometry}` (`'w-1'`, `'line' | 'area' | 'point'`) or `null`.
- `editor.onNeedsPreset((req) => ...)`: called each time a feature needs a preset; returns an
  unsubscribe function.
- `editor.clearNeedsPreset()`: call it after a preset was picked or the picker was dismissed.

`needsPreset` clears itself when the feature gets tags, is deleted or undone, or is deselected.

**Rendering.** The edited graph is redrawn once per animation frame. A change from the history
(dragging a node, an operation, a fix) updates only the features of the changed entities and their
parents in the GeoJSON sources (`updateData`); panning, zooming, selection and newly loaded tiles
redraw everything. Dragging a vertex with about 2,100 features drawn (26,000 entities loaded,
Opava centre at zoom 16.3, headless Chrome) went from 18 fps (a full `setData` per frame, 4.3 s of
script for 120 mouse moves) to 62 fps (0.7 s). The partial fill of large areas is computed like the
tile renderer's band: the polygon minus its 30 px inset (Clipper2 offset with mitre joins, limit 3)
for the display zooms around the current one, so concave corners stay clean.

### Presets in the editor

The inspector works like iD's sidebar, in Czech or English (the `CS`/`EN` switch, remembered per
browser): a preset header with icon and name, "Change preset" with a picker (search over names,
terms and aliases without diacritics, English names too in Czech, categories, recently used, your
presets first, smart presets in their own group), the preset's fields from id-tagging-schema with
their labels, placeholders, options and translations, an "Add field" list for `moreFields` and
universal fields, and the raw tag editor (list and text) below. Fields follow iD: `prerequisiteTag`,
`geometry`, `default` and region (`locationSet`, Czechia) filters; field types without an editor
fall back to the tag editor. The browser builds its preset catalog from the same
`/api/presets/id-schema/` files plus `/api/presets/user`, and matches and changes presets with iD's
rules (old preset's `removeTags` and field defaults out, new `addTags` and defaults in). In edit
mode a preset change goes through iD's `actionChangePreset` (your presets are merged into iD's
preset index) as one undoable step, for one feature or the whole multi-selection. Outside edit mode,
editing a field or the preset of the selected feature starts edit mode with it selected; below
zoom 15 the change goes to the manual draft as before.

Drawing with a preset (Presets tab, "Draw") starts point, line or area mode and the new feature is
born with the preset's tags. Bulk apply takes the features of a highlight (in the area or map view)
or the picked scan issues, shows the count and a per-object tag preview from
`POST /api/presets/apply/bulk` with `dryRun`, and creates a draft for review; nothing is uploaded.

Smart presets that match the selected feature appear in the inspector and the picker. Running one
shows its `params` form; Preview runs the steps with iD actions on the current graph and draws the
result (the new pieces are selected), Apply keeps it as one history step annotated with what it did,
Cancel removes it. Steps: `findCrossing` (ways matching the MapCSS `with` selector, evaluated by the
shared MapCSS engine, that cross the feature or share an inner node with it), `findConnected`,
`findNearby`, `ensureSharedNode` (reuses the shared node or inserts one at the intersection into
both ways), `splitAround` (splits the way at `distance` on both sides of the node, reusing a vertex
within 0.5 m, and binds the middle piece) and `tag` (`set`/`remove` with `{param}`). A step that
cannot run stops the preset with a message (no crossing road, more than one, unknown width) and
leaves the data unchanged. `half_width(road)` uses the same table on the server and in the browser
(`HalfWidth` in `shared/`, exported to JS): `width`, else `lanes` × lane width, else a default per
`highway` class, halved.

### Layout

```
server/   Ktor app: REST, WebSocket, MCP, JOSM remote control, core (drafts, merge, OSM API)
shared/   KMP module (jvm + js): wire models, TagDiff logic, presets (resolve, match, apply), TS model generator
rules/    rule(...) DSL, GOL mirror with hot swap, rule engine, built-in rules, MapCSS host over GeoDesk
web/      Vue 3 app (MapLibre, Pinia, Tailwind), consumes the shared JS build and generated types
user-rules.example/  Example rules folder (highlights, checks, presets, smart presets, sources.json)
infra/    gol sidecar: build, minutely update of an alternating GOL pair, check, swap; live spike
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
| 1 | Skeleton and data | Monorepo, compose, Postgres, gol sidecar with update, rebuild and swap; minutely clipped replication and an alternating GOL pair (end-to-end check with the real `gol`: `infra/gol/live-spike.sh`) |
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
ODbL; the server ships Geofabrik's Czechia extract polygon and the tests recorded minutely diffs
(see NOTICE). The bundled iD tagging schema files are ISC (see NOTICE). The gol sidecar downloads GOL Tool (AGPL-3.0) at image build time; it is not part of this
repository.
