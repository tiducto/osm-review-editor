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

> Status: early development, single-user, local or home-lab. Geometry editing is out of scope for
> v1: geometry work is handed to iD with a deep link.

## Quick start (Docker)

```sh
cp .env.example .env      # set DB_PASSWORD and TOKEN_ENCRYPTION_KEY at least
docker compose up -d --build
```

The first start downloads the Czechia extract from Geofabrik and builds the GOL (about 15 minutes);
the `gol` sidecar then applies daily replication diffs and does a weekly full rebuild. Open
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
| `DEFAULT_AREAS` | GeoJSON FeatureCollection whose features' `properties.name` become area presets |
| `EXTRACT_URL` | Geofabrik extract the gol sidecar mirrors (default Czechia) |
| `CONTACT` | Contact for the User-Agent sent to the OSM API |

### How the mirror stays current

The `gol` sidecar keeps two GOL files, `a.gol` and `b.gol`, in the shared volume; `/data/current`
names the one the app reads. Each day it copies the current file under a temporary name, runs
`gol update` and `gol check` on the copy, renames it to the idle name and rewrites `/data/current`
atomically. The app notices within 15 seconds, opens the new file, swaps it in under a write lock,
drops rule caches and closes the old one; the UI refreshes its layers. Because `gol update` is
marked experimental, a full rebuild from a fresh extract runs every 7 days (`REBUILD_DAYS`).

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
| `list_rules` | Rule ids, titles, severity, confidence |
| `run_rule` | Issues for a rule in an area (name, bbox or GeoJSON), with proposed fixes |
| `query_osm` | GOQL over the local mirror, or Overpass QL; at most 500 objects |
| `get_object` | One object from the mirror (with parents) or live from the API (with version) |
| `create_draft` | A draft authored by Claude |
| `propose_changes` | Up to 2,000 changes: `add_tags`, `remove_tags`, `set_tags`, `reason` |
| `update_change` | Revise or withdraw a proposed change |
| `list_drafts`, `get_draft` | Drafts, changes, your review decisions and comments |

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

Rules are plain Kotlin over GOQL in [`rules/`](rules/src/main/kotlin/eu/tiducto/osmreview/rules/BuiltInRules.kt):

| Rule | Finds | Fix | Confidence |
| --- | --- | --- | --- |
| `deprecated-tags` | Tags in iD's `deprecated.json` | Replacement from that file | Deterministic for 1:1 renames |
| `platform-area` | Closed platform ways without `area=yes` | Add `area=yes` | Deterministic |
| `platform-node-only` | Platform nodes with no platform way within 15 m | Issue only | — |
| `sidewalk-separate` | Roads with `sidewalk=*` and a parallel `footway=sidewalk` within 10 m | `sidewalk:<side>=separate`, side from geometry | Needs review |
| `crossing-missing-type` | `highway=crossing` without `crossing=*` | From `crossing_ref` when it implies one | Needs review |
| `footway-missing-surface` | Footways, paths, cycleways without `surface` | Surface of connected ways when they agree | Needs review |
| `platform-details` | Platforms missing `shelter`, `bench`, `tactile_paving` | Issue only | — |

Only deterministic fixes can be approved in bulk.

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

Tests: `./gradlew :shared:allTests :rules:test :server:test`. Rule tests against a real GOL run when
`OSM_REVIEW_TEST_GOL` points at one; `DraftFlowTest` uses Testcontainers.

After changing a `@Serializable` model in `shared/`, run `./gradlew :shared:generateTsModels`; CI
fails when `web/src/generated/models.ts` is stale.

### Layout

```
server/   Ktor app: REST, WebSocket, MCP, JOSM remote control, core (drafts, merge, OSM API)
shared/   KMP module (jvm + js): wire models, TagDiff logic, TS model generator
rules/    rule(...) DSL, GOL mirror with hot swap, rule engine, built-in rules
web/      Vue 3 app (MapLibre, Pinia, Tailwind), consumes the shared JS build and generated types
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

## Milestones

| # | Milestone | State |
| --- | --- | --- |
| 0 | GOL spike | Reads verified on a Czechia GOL (GOQL, `parents()`, distance queries, all rules < 1.2 s). `gol update` half: run `infra/gol/spike.sh`. |
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

## License

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Map data © OpenStreetMap contributors,
ODbL. The gol sidecar downloads GOL Tool (AGPL-3.0) at image build time; it is not part of this
repository.
