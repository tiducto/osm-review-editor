#!/usr/bin/env bash
set -euo pipefail
GOL=${1:?path to the gol binary (GOL Tool 2.5+)}
SOURCE=${2:?an updatable GOL (built with gol build --updatable)}
WORK=${3:-data/live}
HERE=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd)
if [ ! -s "$WORK/current" ]; then
  echo "seeding $WORK/a.gol and $WORK/b.gol from $SOURCE"
  cp -c "$SOURCE" "$WORK/a.gol" 2>/dev/null || cp "$SOURCE" "$WORK/a.gol"
  cp -c "$SOURCE" "$WORK/b.gol" 2>/dev/null || cp "$SOURCE" "$WORK/b.gol"
  echo a.gol > "$WORK/current"
  date +%s > "$WORK/last-build"
fi

cat <<EOF
Alternating GOL pair in $WORK. Start the app against it in another terminal:

  DATA_DIR=$WORK REPLICATION_ENABLED=true ./gradlew :server:run

The app clips the minutely diffs into $WORK/replication; this loop updates the idle file every
\${MIRROR_UPDATE_SECONDS:-180}s and flips $WORK/current. Ctrl-C stops it.
EOF

export DATA_DIR="$WORK" GOL_BIN="$GOL"
export REBUILD_DAYS=${REBUILD_DAYS:-36500}
export MIRROR_UPDATE_SECONDS=${MIRROR_UPDATE_SECONDS:-180}
exec "$HERE/update.sh"
