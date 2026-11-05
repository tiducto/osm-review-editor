#!/usr/bin/env bash
set -uo pipefail
GOL=${1:?path to the gol binary (GOL Tool 2.5+)}
SOURCE=${2:?an updatable GOL (built with gol build --updatable), e.g. data/spike/after.gol}
WORK=${3:-data/live-spike}
SEQUENCES=${SEQUENCES:-20}
PORT=${SPIKE_PORT:-8190}
URL="http://127.0.0.1:$PORT/replication"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
FAILED=0
HTTP_PID=""
HOLD_PID=""

pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAILED=1; }
info() { echo "INFO $*"; }
cleanup() {
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  [ -n "$HOLD_PID" ] && kill "$HOLD_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

clipper() { (cd "$ROOT" && ./gradlew -q :server:runClipper --args="$*"); }
header_of() { clipper "--header $1" | tail -n 1; }
revision_of() { header_of "$1" | sed -nE 's/.* revision=([0-9]+).*/\1/p'; }
url_of() { header_of "$1" | sed -nE 's/.* url=(.*)$/\1/p'; }
head_seq() { sed -nE 's/^sequenceNumber=([0-9]+).*/\1/p' "$WORK/replication/state.txt"; }
clean() { rm -rf "$WORK/${1%.gol}-indexes"; rm -f "$WORK/${1%.gol}"-*.gob; }
copy() { cp -c "$1" "$2" 2>/dev/null || cp "$1" "$2"; }

timed_update() {
  local f=$1 start rc
  start=$(date +%s)
  "$GOL" update "$WORK/$f" "$URL"
  rc=$?
  info "gol update $f took $(( $(date +%s) - start ))s (exit $rc)"
  return "$rc"
}

mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd)
"$GOL" --version && pass "gol runs" || { fail "gol does not run"; exit 1; }

rm -rf "$WORK/replication" "$WORK"/*.gol "$WORK"/*-indexes "$WORK"/*.gob
copy "$SOURCE" "$WORK/a.gol" && copy "$SOURCE" "$WORK/b.gol" && pass "copied $SOURCE to a.gol and b.gol" || { fail "copy"; exit 1; }
info "source header: $(header_of "$WORK/a.gol")"
BEFORE_SIZE=$(du -k "$WORK/b.gol" | cut -f1)

echo "== clip $SEQUENCES minutely diffs after the GOL's timestamp"
if clipper "--gol $WORK/a.gol --out $WORK/replication --max $SEQUENCES"; then
  [ -s "$WORK/replication/state.txt" ] && pass "clipper wrote $WORK/replication/state.txt (sequence $(head_seq))" || fail "clipper wrote no state.txt"
else
  fail "clipper run"; exit 1
fi
HEAD1=$(head_seq)

echo "== serve the replication directory"
(cd "$WORK" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1) &
HTTP_PID=$!
sleep 2
curl -fsS "$URL/state.txt" >/dev/null && pass "local replication served at $URL" || { fail "http server"; exit 1; }

echo "== first update of b.gol (new URL, start found by timestamp)"
timed_update b.gol && pass "gol update b.gol" || fail "gol update b.gol"
"$GOL" check "$WORK/b.gol" && pass "gol check b.gol" || fail "gol check b.gol"
R=$(revision_of "$WORK/b.gol"); U=$(url_of "$WORK/b.gol")
[ "$R" = "$HEAD1" ] && pass "b.gol revision $R matches the clipped head" || fail "b.gol revision $R, expected $HEAD1"
[ "$U" = "$URL" ] && pass "b.gol stores the local replication URL" || fail "b.gol url '$U'"
ls "$WORK" | grep -E 'gob|indexes' | sed 's/^/INFO leftover: /'
clean b.gol

echo "== clip 5 more and update b.gol incrementally"
clipper "--gol $WORK/a.gol --out $WORK/replication --max 5" && pass "second clipper run resumed" || fail "second clipper run"
HEAD2=$(head_seq)
timed_update b.gol && pass "incremental gol update b.gol" || fail "incremental gol update b.gol"
R=$(revision_of "$WORK/b.gol")
[ "$R" = "$HEAD2" ] && [ "$HEAD2" -gt "$HEAD1" ] && pass "b.gol advanced to $R" || fail "b.gol revision $R, expected $HEAD2"
"$GOL" check "$WORK/b.gol" && pass "gol check b.gol after the incremental update" || fail "gol check b.gol"
clean b.gol

echo "== a GOL held open by GeoDesk for Java cannot be updated"
(cd "$ROOT" && exec ./gradlew -q :server:runClipper --args="--hold $WORK/a.gol 120") > "$WORK/hold.log" 2>&1 &
HOLD_PID=$!
for _ in $(seq 1 60); do grep -q holding "$WORK/hold.log" && break; sleep 1; done
if grep -q holding "$WORK/hold.log"; then
  if "$GOL" update "$WORK/a.gol" "$URL" 2> "$WORK/locked.log"; then
    fail "gol update succeeded on a file the JVM holds open"
  else
    pass "gol update refused while the JVM holds a.gol ($(tr '\n' ' ' < "$WORK/locked.log" | sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g' | cut -c1-120))"
  fi
else
  info "could not start the holding JVM; skipped the lock check"
fi
kill "$HOLD_PID" 2>/dev/null; wait "$HOLD_PID" 2>/dev/null; HOLD_PID=""
clean a.gol

echo "== update the other file of the pair"
timed_update a.gol && pass "gol update a.gol" || fail "gol update a.gol"
"$GOL" check "$WORK/a.gol" && pass "gol check a.gol" || fail "gol check a.gol"
[ "$(revision_of "$WORK/a.gol")" = "$HEAD2" ] && pass "a.gol and b.gol are at the same sequence" || fail "a.gol revision $(revision_of "$WORK/a.gol")"
clean a.gol

echo "== nothing new"
"$GOL" update "$WORK/b.gol" "$URL" && pass "gol update with no new diffs exits 0" || fail "gol update with no new diffs"
clean b.gol

AFTER_SIZE=$(du -k "$WORK/b.gol" | cut -f1)
info "b.gol disk use ${BEFORE_SIZE} KB -> ${AFTER_SIZE} KB"
[ "$AFTER_SIZE" -lt $(( BEFORE_SIZE * 3 / 2 )) ] && pass "updated file stays near its original size" || fail "b.gol grew to ${AFTER_SIZE} KB"
M0=$(clipper "--missing $SOURCE" | tail -n 1)
M1=$(clipper "--missing $WORK/b.gol" | tail -n 1)
info "before: $M0"
info "after:  $M1"
W0=$(echo "$M0" | sed -nE 's/.*missing_nodes_ways=([0-9]+).*/\1/p')
W1=$(echo "$M1" | sed -nE 's/.*missing_nodes_ways=([0-9]+).*/\1/p')
if [ -n "$W0" ] && [ -n "$W1" ]; then
  [ $(( W1 - W0 )) -le 20 ] && pass "ways with missing nodes $W0 -> $W1" || fail "ways with missing nodes grew from $W0 to $W1"
fi
info "gol info b.gol:"
"$GOL" info "$WORK/b.gol" 2>&1 | sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g' | head -n 20

if [ "$FAILED" -eq 0 ]; then echo "RESULT PASS"; else echo "RESULT FAIL"; fi
exit "$FAILED"
