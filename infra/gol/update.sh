#!/usr/bin/env bash
set -uo pipefail

DATA=${DATA_DIR:-/data}
EXTRACT_URL=${EXTRACT_URL:-}
INTERVAL=${MIRROR_UPDATE_SECONDS:-180}
REBUILD_DAYS=${REBUILD_DAYS:-7}
RETRY_SECONDS=${RETRY_SECONDS:-900}
PORT=${REPLICATION_PORT:-8189}
LOCAL_URL=${REPLICATION_URL:-http://127.0.0.1:$PORT/replication}
RELEASE_TIMEOUT=${RELEASE_TIMEOUT_SECONDS:-600}
ACK_WAIT=${ACK_WAIT_SECONDS:-60}
GOL=${GOL_BIN:-gol}
CURRENT="$DATA/current"
STATUS="$DATA/status.json"
ACK="$DATA/released"
TRIGGER="$DATA/update-now"
REPL="$DATA/replication"
STEP_LOG="$DATA/step.log"

PHASE=idle
KIND=""
RESULT=""
ERROR=""
LAST_RUN_AT=0
CYCLE_START=0
LAST_CYCLE=""
NEXT_AT=0
HTTP_PID=""

log() { echo "$(date -u +%FT%TZ) gol-sidecar: $*"; }
iso() { date -u -d "@$1" +%FT%TZ 2>/dev/null || date -u -r "$1" +%FT%TZ; }
now() { date +%s; }
strip_ansi() { sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g'; }

current_name() { if [ -s "$CURRENT" ]; then tr -d '[:space:]' < "$CURRENT"; fi; }
other_of() { if [ "$1" = "a.gol" ]; then echo "b.gol"; else echo "a.gol"; fi; }

header() {
  H_REV=""; H_TS=""; H_URL=""
  local f=$1 active len
  [ -f "$f" ] || return 1
  active=$(od -An -t u1 -j 17 -N 1 "$f" | tr -d ' ')
  [ -n "$active" ] || return 1
  active=$(( active & 1 ))
  H_REV=$(od -An -t u4 -j $(( 128 + 64 * active )) -N 4 "$f" | tr -d ' ')
  H_TS=$(od -An -t d8 -j $(( 136 + 64 * active )) -N 8 "$f" | tr -d ' ')
  len=$(od -An -t u1 -j 256 -N 1 "$f" | tr -d ' ')
  if [ "${len:-0}" -gt 0 ]; then H_URL=$(dd if="$f" bs=1 skip=257 count="$len" 2>/dev/null); fi
  if [ "${H_TS:-0}" -lt 946684800000 ]; then H_TS=""; fi
  return 0
}

available_sequence() {
  [ -f "$REPL/state.txt" ] && sed -nE 's/^sequenceNumber=([0-9]+).*/\1/p' "$REPL/state.txt" | head -n 1
}

run() {
  local rc what=$1
  [ "$1" = "$GOL" ] && what="gol ${2:-}"
  "$@" 2> "$STEP_LOG"
  rc=$?
  strip_ansi < "$STEP_LOG" >&2
  if [ "$rc" -ne 0 ]; then
    ERROR="$what failed (exit $rc): $(strip_ansi < "$STEP_LOG" | tr '\r' '\n' | grep -v '^[[:space:]]*$' | tail -n 3 | tr '\n' ' ' | sed -e 's/ *$//')"
    log "$ERROR"
    return "$rc"
  fi
}

write_status() {
  local t last cur seq="" ts="" data_ts="" lag="" avail
  t=$(now)
  last=$(cat "$DATA/last-build" 2>/dev/null || echo 0)
  cur=$(current_name)
  if [ -n "$cur" ] && header "$DATA/$cur" && [ -n "$H_TS" ]; then
    seq=$H_REV
    ts=$(iso $(( H_TS / 1000 )))
    data_ts=$ts
    lag=$(( t - H_TS / 1000 ))
  fi
  avail=$(available_sequence || true)
  jq -n \
    --arg lastRunAt "$([ "$LAST_RUN_AT" -gt 0 ] && iso "$LAST_RUN_AT")" \
    --arg lastRunKind "$KIND" \
    --arg result "$RESULT" \
    --arg error "$ERROR" \
    --arg lastFullBuildAt "$([ "$last" -gt 0 ] && iso "$last")" \
    --arg nextFullBuildDue "$([ "$last" -gt 0 ] && iso $(( last + REBUILD_DAYS * 86400 )))" \
    --arg replicationSequence "$seq" \
    --arg replicationTimestamp "$ts" \
    --arg current "$cur" \
    --arg phase "$PHASE" \
    --arg cycleStartedAt "$([ "$CYCLE_START" -gt 0 ] && iso "$CYCLE_START")" \
    --arg lastCycleSeconds "$LAST_CYCLE" \
    --arg nextCycleAt "$([ "$NEXT_AT" -gt 0 ] && iso "$NEXT_AT")" \
    --arg availableSequence "$avail" \
    --arg dataTimestamp "$data_ts" \
    --arg lagSeconds "$lag" \
    --arg intervalSeconds "$INTERVAL" \
    'def opt: if . == "" then null else . end;
     def num: opt | if . == null then null else tonumber end;
     {lastRunAt: ($lastRunAt | opt), lastRunKind: ($lastRunKind | opt), result: ($result | opt), error: ($error | opt),
      lastFullBuildAt: ($lastFullBuildAt | opt), nextFullBuildDue: ($nextFullBuildDue | opt),
      replicationSequence: ($replicationSequence | num), replicationTimestamp: ($replicationTimestamp | opt),
      current: ($current | opt), phase: $phase, cycleStartedAt: ($cycleStartedAt | opt),
      lastCycleSeconds: ($lastCycleSeconds | num), nextCycleAt: ($nextCycleAt | opt),
      availableSequence: ($availableSequence | num), dataTimestamp: ($dataTimestamp | opt),
      lagSeconds: ($lagSeconds | num), intervalSeconds: ($intervalSeconds | num)}' \
    > "$STATUS.tmp" && mv -f "$STATUS.tmp" "$STATUS" || log "could not write $STATUS"
}

restore_status() {
  [ -f "$STATUS" ] || return 0
  KIND=$(jq -r '.lastRunKind // ""' "$STATUS" 2>/dev/null || true)
  RESULT=$(jq -r '.result // ""' "$STATUS" 2>/dev/null || true)
  ERROR=$(jq -r '.error // ""' "$STATUS" 2>/dev/null || true)
  LAST_CYCLE=$(jq -r '.lastCycleSeconds // ""' "$STATUS" 2>/dev/null || true)
  local at
  at=$(jq -r '.lastRunAt // ""' "$STATUS" 2>/dev/null || true)
  if [ -n "$at" ]; then LAST_RUN_AT=$(date -u -d "$at" +%s 2>/dev/null || date -u -j -f %Y-%m-%dT%H:%M:%SZ "$at" +%s 2>/dev/null || echo 0); fi
}

phase() {
  PHASE=$1
  write_status
}

serve() {
  if [ -n "$HTTP_PID" ] && kill -0 "$HTTP_PID" 2>/dev/null; then return 0; fi
  mkdir -p "$REPL"
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/current" 2>/dev/null; then return 0; fi
  if busybox httpd --help >/dev/null 2>&1; then
    busybox httpd -f -p "127.0.0.1:$PORT" -h "$DATA" &
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$DATA" >/dev/null 2>&1 &
  else
    log "no static HTTP server (busybox httpd or python3) found"
    return 1
  fi
  HTTP_PID=$!
  log "serving $DATA on 127.0.0.1:$PORT (pid $HTTP_PID); gol updates from $LOCAL_URL"
}

clean() {
  local base=${1%.gol}
  rm -rf "$DATA/$base-indexes" "$DATA/$base.gol.journal"
  rm -f "$DATA/$base"-*.gob
}

copy_gol() {
  { cp -c "$DATA/$1" "$DATA/$2.tmp" 2>/dev/null || run cp "$DATA/$1" "$DATA/$2.tmp"; } && mv -f "$DATA/$2.tmp" "$DATA/$2"
}

publish() {
  echo "$1" > "$CURRENT.tmp"
  mv -f "$CURRENT.tmp" "$CURRENT"
  now > "$DATA/flipped-at"
  log "current -> $1"
}

server_opened() {
  [ -f "$ACK" ] && [ "$(jq -r '.open // ""' "$ACK" 2>/dev/null)" = "$1" ]
}

released() {
  local cur=$1 flipped
  server_opened "$cur" && return 0
  flipped=$(cat "$DATA/flipped-at" 2>/dev/null || echo 0)
  if [ $(( $(now) - flipped )) -ge "$RELEASE_TIMEOUT" ]; then
    log "no release ack for $(other_of "$cur") after ${RELEASE_TIMEOUT}s; trying anyway (gol refuses a file that is still open)"
    return 0
  fi
  return 1
}

await_ack() {
  local name=$1 waited=0
  while [ "$waited" -lt "$ACK_WAIT" ]; do
    server_opened "$name" && { log "app opened $name and released $(other_of "$name")"; return 0; }
    sleep 1
    waited=$(( waited + 1 ))
  done
  log "app has not opened $name yet; the next cycle waits for its release"
  return 0
}

full_build() {
  local cur target other
  [ -n "$EXTRACT_URL" ] || { ERROR="EXTRACT_URL is not set"; log "$ERROR"; return 1; }
  phase rebuilding
  cur=$(current_name)
  if [ -n "$cur" ] && [ -f "$DATA/$cur" ]; then target=$(other_of "$cur"); else target=a.gol; cur=""; fi
  other=$(other_of "$target")
  clean build.gol
  rm -f "$DATA/build.gol"
  log "downloading $EXTRACT_URL"
  run curl -fSL --retry 3 -o "$DATA/extract.osm.pbf.tmp" "$EXTRACT_URL" || { rm -f "$DATA/extract.osm.pbf.tmp"; return 1; }
  mv -f "$DATA/extract.osm.pbf.tmp" "$DATA/extract.osm.pbf"
  log "building $target (--updatable keeps waynode ids for gol update)"
  if ! { run "$GOL" build --updatable "$DATA/build.gol" "$DATA/extract.osm.pbf" && run "$GOL" check "$DATA/build.gol"; }; then
    clean build.gol
    rm -f "$DATA/build.gol"
    return 1
  fi
  clean build.gol
  rm -f "$DATA/extract.osm.pbf"
  mv -f "$DATA/build.gol" "$DATA/$target"
  date +%s > "$DATA/last-build"
  phase swapping
  publish "$target"
  if [ -n "$cur" ]; then await_ack "$target"; fi
  log "copying $target to $other"
  copy_gol "$target" "$other" || return 1
  clean "$other"
  return 0
}

update() {
  local cur idle avail before
  cur=$(current_name)
  idle=$(other_of "$cur")
  if [ ! -f "$DATA/$idle" ]; then
    log "$idle is missing; copying $cur"
    copy_gol "$cur" "$idle" || return 1
  fi
  avail=$(available_sequence || true)
  if [ -z "$avail" ]; then
    RESULT=waiting
    ERROR="waiting for the app's replication clipper to write $REPL/state.txt"
    return 2
  fi
  if header "$DATA/$cur" && [ "$H_URL" = "$LOCAL_URL" ] && [ "${H_REV:-0}" -ge "$avail" ]; then
    RESULT=current
    return 2
  fi
  if ! released "$cur"; then
    RESULT=waiting
    ERROR="waiting for the app to release $idle"
    return 2
  fi
  header "$DATA/$idle"
  before="$H_REV $H_URL"
  phase updating
  log "updating $idle from $LOCAL_URL (available sequence $avail)"
  if ! run "$GOL" update "$DATA/$idle" "$LOCAL_URL"; then
    clean "$idle"
    return 1
  fi
  phase checking
  if ! run "$GOL" check "$DATA/$idle"; then
    clean "$idle"
    log "$idle failed the check; replacing it with a copy of $cur"
    copy_gol "$cur" "$idle"
    return 1
  fi
  clean "$idle"
  header "$DATA/$idle"
  if [ "$H_REV $H_URL" = "$before" ]; then
    log "$idle had nothing new"
    RESULT=current
    return 2
  fi
  phase swapping
  publish "$idle"
  await_ack "$idle"
  return 0
}

cycle() {
  local kind=$1 rc start
  ERROR=""
  RESULT=""
  start=$(now)
  CYCLE_START=$start
  rm -f "$TRIGGER"
  if [ "$kind" = "build" ]; then full_build; else update; fi
  rc=$?
  KIND=$kind
  LAST_RUN_AT=$(now)
  LAST_CYCLE=$(( LAST_RUN_AT - start ))
  CYCLE_START=0
  case "$rc" in
    0) RESULT=ok ;;
    2) [ -n "$RESULT" ] || RESULT=skipped ;;
    *) RESULT=failed ;;
  esac
  PHASE=idle
  write_status
  [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ] || return 1
  return 0
}

pause() {
  NEXT_AT=$(( $(now) + $1 ))
  phase idle
  while [ "$(now)" -lt "$NEXT_AT" ]; do
    if [ -f "$TRIGGER" ]; then
      log "update requested"
      break
    fi
    serve
    sleep "${POLL_SECONDS:-2}"
  done
  NEXT_AT=0
}

rebuild_due() {
  local last
  last=$(cat "$DATA/last-build" 2>/dev/null || echo 0)
  [ $(( $(now) - last )) -ge $(( REBUILD_DAYS * 86400 )) ]
}

stop() {
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  exit 0
}

trap stop TERM INT
mkdir -p "$DATA" "$REPL"
restore_status
serve
if [ -z "$(current_name)" ] || [ ! -f "$DATA/$(current_name)" ]; then
  until cycle build && [ -f "$DATA/$(current_name)" ]; do
    log "initial build failed; retrying in ${RETRY_SECONDS}s"
    pause "$RETRY_SECONDS"
  done
fi

while true; do
  pause "$INTERVAL"
  serve
  if rebuild_due; then
    cycle build || log "rebuild failed; keeping $(current_name)"
  else
    cycle update || log "update failed; keeping $(current_name)"
  fi
done
