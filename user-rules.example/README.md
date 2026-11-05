# Rules folder

Your own map highlights, QA checks and presets for OSM Review Editor, as plain text files. Edit
them in any editor, in the app, or let Claude edit them over MCP. The server watches this folder
and reloads a file shortly after it changes; problems in a file show up as diagnostics (file, line,
column, message) in the app and in the MCP tools.

Copy this folder to start: `cp -R user-rules.example user-rules`. Locally the server reads
`RULES_DIR` (default `user-rules`); Docker Compose mounts `./user-rules` at `/rules`.

## Layout

| Path | Kind | What it is |
| --- | --- | --- |
| `highlights/*.mapcss` | `highlight` | MapCSS styles that highlight objects on the map |
| `checks/*.validator.mapcss` | `check` | MapCSS validator rules (`throwWarning`, `throwError`, `fixAdd`, ...) like JOSM's |
| `presets/*.json` | `preset` | iD-style presets, either full presets or overlays of an iD preset |
| `presets/smart/*.json` | `smart_preset` | Multi-step presets that edit geometry and tags |
| `sources.json` | `sources` | Remote rule files fetched read-only into the app's cache |

File names use letters, digits, `_`, `.` and `-`. Other files (like this README) are ignored, and so
is everything starting with a dot. Files must be UTF-8 and at most 2 MB. Deleting a file from the app
or over MCP moves it to `.trash/<timestamp>/` here, so you can restore it by hand.

## Highlights

A highlight is a MapCSS style sheet. The `meta` block names it (`title`, `description`) and adds our
extensions: `-ore-dim-others: true` greys out everything the file does not style, and
`-ore-stats: length` (or `count`, `area`) is what coverage numbers sum up. `-ore-legend: "label"` on a
rule puts it in the legend; an object counts under the last legend rule it matches.

- `highlights/sidewalks.mapcss`: how sidewalks are tagged on streets, with a legend and length stats.
- `highlights/missing-surface.mapcss`: streets and paths without `surface`, and segments whose
  surface differs from both neighbours (`odd_segment`, evaluated on the server).
- `highlights/footways.mapcss`: sidewalk ways and crossings without a type.

## Checks

A check is a JOSM-style validator file: a rule with `throwError`, `throwWarning` or `throwOther` reports
an issue, `group` sorts it, and `fixAdd`, `fixRemove`, `fixChangeKey` and `fixDeleteObject` propose a
fix that you review in a draft. Every `assertMatch` / `assertNoMatch` (`"node highway=crossing"`,
`"closedway building=yes"`) is tested when the file loads.

Besides JOSM's functions, checks can call `odd_segment("maxspeed;surface")`, `network_island_size()`,
`half_width()`, `connected_value("surface", "footway;path")`, `inside("CZ")` / `outside("CZ")`,
`waylength()` and `areasize()`, and use `:righthandtraffic`, `⧉` (crossing) and `∈` (inside). The
main README describes them.

- `checks/crossings.validator.mapcss`: crossings without a type, the type taken from `crossing_ref`.
- `checks/platforms.validator.mapcss`: closed platform ways without `area=yes`; platforms without
  shelter, bench or tactile paving.
- `checks/surfaces.validator.mapcss`: paths without `surface`, with the surface of connected paths
  when they agree.
- `checks/street-consistency.validator.mapcss`: street segments that differ from both neighbours
  of the same street; footways cut off from the network.

## Presets

A preset uses the keys of iD's tagging schema: `name`, `geometry` (`point`, `vertex`, `line`,
`area`, `relation`), `tags`, `addTags`, `removeTags`, `fields`, `moreFields`, `icon`, `terms`,
`aliases`, `matchScore`, `searchable`. Two keys are ours:

- `extends`: the id of an iD preset (or another of your presets). Every key you leave out is taken
  from it, so an overlay only lists what it changes. An overlay may keep the id of the preset it
  extends to change that preset in place.
- `apply`: how applying the preset changes an object's tags. `merge` (default) keeps the other tags
  and sets `addTags`, like iD; `replace` makes `addTags` the complete tag set; `create` is for new,
  untagged objects only.

`{presetId}` in `name`, `fields`, `moreFields`, `terms` or `aliases` refers to another preset, as in
id-tagging-schema: `"fields": ["{highway/footway/sidewalk}", "lit"]` is that preset's fields plus
`lit`. A file holds one preset object or an array of them. `presets/sidewalk-cz.json` is an overlay
of iD's sidewalk preset with a Czech name and paving stones as the default surface.

## Smart presets

`presets/smart/footway-crossing.json` turns the part of a footway that crosses a road into a
crossing. `match` and each step's `with` are MapCSS selectors. `params` are asked for when the preset
runs (`combo`, `text`, `number`, `check`) and used in tag values as `{name}`. Steps bind their result
with `as`; `self` is the matched object.

| `op` | Arguments | Binds |
| --- | --- | --- |
| `findCrossing` | `with`, `as`, `require` (`one`, `any`, `optional`) | a way crossing `self` |
| `findConnected` | `with`, `as`, `require` | a way sharing a node with `self` |
| `findNearby` | `with`, `as`, `distance`, `require` | an object within `distance` |
| `ensureSharedNode` | `ways` (two or more names), `as` | the node where they meet |
| `splitAround` | `way`, `node`, `at` (`nearestNodeOr`, `exact`), `distance`, `as` | the piece of `way` around `node` |
| `tag` | `target`, `set`, `remove` | nothing |

`distance` is metres (`2`, `"2.5m"`) or `width(name)` / `half_width(name)` of a bound way. Smart
presets are checked when saved; running them comes with the editing core.

## Remote sources

`sources.json` lists rule files to fetch: `name` (`a-z 0-9 . _ -`), `url`, `kind` (`highlight`,
`check`, `preset`, `smart_preset`), `refreshHours` (default 24) and an optional `licence` note. They
are cached in the app's data folder, listed as `remote/<name>.<ext>` and cannot be edited in the app.
They keep their own licence: JOSM's validator files, like the example in `sources.json`, are
GPL-2.0-or-later and are fetched for your own use, not shipped with the app.

## Licence

The example files in this folder are part of OSM Review Editor and licensed under Apache-2.0.
