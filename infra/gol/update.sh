#!/usr/bin/env bash
set -euo pipefail

DATA=${DATA_DIR:-/data}
EXTRACT_URL=${EXTRACT_URL:?set EXTRACT_URL to a Geofabrik .osm.pbf}
UPDATE_INTERVAL=${UPDATE_INTERVAL:-86400}
REBUILD_DAYS=${REBUILD_DAYS:-7}
CURRENT="$DATA/current"

log() { echo "$(date -u +%FT%TZ) gol-sidecar: $*"; }

current_name() { [ -s "$CURRENT" ] && cat "$CURRENT" || true; }
idle_name() { [ "$(current_name)" = "a.gol" ] && echo "b.gol" || echo "a.gol"; }

publish() {
  echo "$1" > "$CURRENT.tmp"
  mv -f "$CURRENT.tmp" "$CURRENT"
  log "current -> $1"
}

full_build() {
  local idle; idle=$(idle_name)
  log "downloading $EXTRACT_URL"
  curl -fSL --retry 3 -o "$DATA/extract.osm.pbf.tmp" "$EXTRACT_URL"
  mv -f "$DATA/extract.osm.pbf.tmp" "$DATA/extract.osm.pbf"
  rm -f "$DATA/build.gol" "$DATA/build.gol.journal"
  log "building $idle (--updatable keeps waynode ids for gol update)"
  gol build --updatable "$DATA/build.gol" "$DATA/extract.osm.pbf"
  gol check "$DATA/build.gol"
  mv -f "$DATA/build.gol" "$DATA/$idle"
  date +%s > "$DATA/last-build"
  publish "$idle"
}

update() {
  local cur idle; cur=$(current_name); idle=$(idle_name)
  log "updating a copy of $cur as $idle"
  rm -f "$DATA/update.gol" "$DATA/update.gol.journal"
  cp "$DATA/$cur" "$DATA/update.gol"
  if gol update "$DATA/update.gol" && gol check "$DATA/update.gol"; then
    mv -f "$DATA/update.gol" "$DATA/$idle"
    publish "$idle"
  else
    log "update failed; keeping $cur (next full rebuild will recover)"
    rm -f "$DATA/update.gol" "$DATA/update.gol.journal"
  fi
}

mkdir -p "$DATA"
if [ -z "$(current_name)" ] || [ ! -f "$DATA/$(current_name)" ]; then
  full_build
fi

while true; do
  sleep "$UPDATE_INTERVAL"
  last=$(cat "$DATA/last-build" 2>/dev/null || echo 0)
  if [ $(( $(date +%s) - last )) -ge $(( REBUILD_DAYS * 86400 )) ]; then
    full_build || log "rebuild failed; keeping $(current_name)"
  else
    update
  fi
done
