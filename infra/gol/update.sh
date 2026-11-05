#!/usr/bin/env bash
set -uo pipefail

DATA=${DATA_DIR:-/data}
EXTRACT_URL=${EXTRACT_URL:?set EXTRACT_URL to a Geofabrik .osm.pbf}
UPDATE_INTERVAL=${UPDATE_INTERVAL:-86400}
REBUILD_DAYS=${REBUILD_DAYS:-7}
RETRY_SECONDS=${RETRY_SECONDS:-900}
CURRENT="$DATA/current"
STATUS="$DATA/status.json"
STEP_LOG="$DATA/step.log"
ERROR=""

log() { echo "$(date -u +%FT%TZ) gol-sidecar: $*"; }
iso() { date -u -d "@$1" +%FT%TZ; }
strip_ansi() { sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g'; }

current_name() { [ -s "$CURRENT" ] && cat "$CURRENT" || true; }
idle_name() { [ "$(current_name)" = "a.gol" ] && echo "b.gol" || echo "a.gol"; }

run() {
  local rc what=$1
  [ "$1" = "gol" ] && what="gol ${2:-}"
  "$@" 2> "$STEP_LOG"
  rc=$?
  strip_ansi < "$STEP_LOG" >&2
  if [ "$rc" -ne 0 ]; then
    ERROR="$what failed (exit $rc): $(strip_ansi < "$STEP_LOG" | tr '\r' '\n' | grep -v '^[[:space:]]*$' | tail -n 3 | tr '\n' ' ' | sed -e 's/ *$//')"
    log "$ERROR"
    return "$rc"
  fi
}

publish() {
  echo "$1" > "$CURRENT.tmp"
  mv -f "$CURRENT.tmp" "$CURRENT"
  log "current -> $1"
}

clean_build() { rm -rf "$DATA/build.gol" "$DATA/build.gol.journal" "$DATA/build-indexes" "$DATA"/build-*.gob; }
clean_update() { rm -rf "$DATA/update.gol" "$DATA/update.gol.journal" "$DATA/update-indexes" "$DATA"/update-*.gob; }

full_build() {
  local idle; idle=$(idle_name)
  clean_build
  log "downloading $EXTRACT_URL"
  run curl -fSL --retry 3 -o "$DATA/extract.osm.pbf.tmp" "$EXTRACT_URL" || { rm -f "$DATA/extract.osm.pbf.tmp"; return 1; }
  mv -f "$DATA/extract.osm.pbf.tmp" "$DATA/extract.osm.pbf"
  log "building $idle (--updatable keeps waynode ids for gol update)"
  if run gol build --updatable "$DATA/build.gol" "$DATA/extract.osm.pbf" && run gol check "$DATA/build.gol"; then
    mv -f "$DATA/build.gol" "$DATA/$idle"
    clean_build
    rm -f "$DATA/extract.osm.pbf"
    date +%s > "$DATA/last-build"
    publish "$idle"
  else
    clean_build
    return 1
  fi
}

update() {
  local cur idle; cur=$(current_name); idle=$(idle_name)
  log "updating a copy of $cur as $idle"
  clean_update
  if run cp "$DATA/$cur" "$DATA/update.gol" && run gol update "$DATA/update.gol" && run gol check "$DATA/update.gol"; then
    mv -f "$DATA/update.gol" "$DATA/$idle"
    clean_update
    publish "$idle"
  else
    log "update failed; keeping $cur (next full rebuild will recover)"
    clean_update
    return 1
  fi
}

revision() {
  local cur; cur=$(current_name)
  [ -n "$cur" ] && [ -f "$DATA/$cur" ] || return 0
  gol info "$DATA/$cur" 2>&1 | strip_ansi \
    | sed -nE 's/^Revision:[[:space:]]*([0-9]+)[^0-9]+([0-9]{4}-[0-9]{2}-[0-9]{2})[ T]([0-9]{2}:[0-9]{2}:[0-9]{2}).*/\1 \2T\3Z/p' \
    | head -n 1
}

write_status() {
  local kind=$1 result=$2 now last next seq="" ts="" rev
  now=$(date +%s)
  last=$(cat "$DATA/last-build" 2>/dev/null || echo 0)
  rev=$(revision || true)
  if [ -n "$rev" ]; then seq=${rev%% *}; ts=${rev#* }; fi
  jq -n \
    --arg lastRunAt "$(iso "$now")" \
    --arg lastRunKind "$kind" \
    --arg result "$result" \
    --arg error "$ERROR" \
    --arg lastFullBuildAt "$([ "$last" -gt 0 ] && iso "$last")" \
    --arg nextFullBuildDue "$([ "$last" -gt 0 ] && iso $(( last + REBUILD_DAYS * 86400 )))" \
    --arg replicationSequence "$seq" \
    --arg replicationTimestamp "$ts" \
    --arg current "$(current_name)" \
    'def opt: if . == "" then null else . end;
     {lastRunAt: $lastRunAt, lastRunKind: $lastRunKind, result: $result, error: ($error | opt),
      lastFullBuildAt: ($lastFullBuildAt | opt), nextFullBuildDue: ($nextFullBuildDue | opt),
      replicationSequence: ($replicationSequence | opt | if . == null then null else tonumber end),
      replicationTimestamp: ($replicationTimestamp | opt), current: ($current | opt)}' \
    > "$STATUS.tmp" && mv -f "$STATUS.tmp" "$STATUS" || log "could not write $STATUS"
}

cycle() {
  local kind=$1 rc
  ERROR=""
  if [ "$kind" = "build" ]; then full_build; else update; fi
  rc=$?
  if [ "$rc" -eq 0 ]; then write_status "$kind" ok; else write_status "$kind" failed; fi
  return "$rc"
}

mkdir -p "$DATA"
if [ -z "$(current_name)" ] || [ ! -f "$DATA/$(current_name)" ]; then
  until cycle build; do
    log "initial build failed; retrying in ${RETRY_SECONDS}s"
    sleep "$RETRY_SECONDS"
  done
fi

while true; do
  sleep "$UPDATE_INTERVAL"
  last=$(cat "$DATA/last-build" 2>/dev/null || echo 0)
  if [ $(( $(date +%s) - last )) -ge $(( REBUILD_DAYS * 86400 )) ]; then
    cycle build || log "rebuild failed; keeping $(current_name)"
  else
    cycle update || true
  fi
done
