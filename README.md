# OSM Review Editor

A self-hosted OpenStreetMap editing tool where every change is a reviewable draft, like a merge
request. QA rules, Claude (over MCP) and external QA tools (JOSM remote control) propose exact tag
changes; you approve, tweak or skip each object; only approved changes are uploaded.

> Status: under active development. See the milestones below.

## Stack

| Layer | Choice |
| --- | --- |
| Backend | Kotlin 2.4, Ktor 3.6 (JVM 25), coroutines |
| Shared | Kotlin Multiplatform `shared` module (JVM + JS): wire models, TagDiff logic, generated TS types |
| OSM data | GeoDesk for Java 2.1 over a GOL file, kept current by GOL Tool 2.5 (`gol update`) |
| App DB | PostgreSQL 18, Exposed, Flyway |
| OSM API | Ktor client, xmlutil (osmChange), OAuth 2.0 + PKCE |
| MCP | Kotlin MCP SDK 0.16 (Streamable HTTP) |
| Frontend | Vue 3.5, Vite 8, TypeScript, MapLibre GL 6, Pinia, Tailwind 4 |

## Layout

```
server/   Ktor app: REST, WebSocket, MCP, JOSM remote control, core (drafts, merge, OSM API)
shared/   KMP module (jvm + js): models, TagDiff logic, TS model generator
rules/    rule(...) DSL, GOL mirror, rule engine and the built-in QA rules
web/      Vue 3 app, consumes the shared JS build and generated types
infra/    gol sidecar (build, update, swap scripts)
docs/     API reference
```
