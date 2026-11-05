#!/usr/bin/env bash
set -euo pipefail
GOL=${1:?path to the gol binary (GOL Tool 2.5+)}
PBF=${2:?a Geofabrik .osm.pbf extract, e.g. czech-republic-latest.osm.pbf}
WORK=${3:-data/spike}
mkdir -p "$WORK"
"$GOL" --version
echo "== build (--updatable implies --waynode-ids)"
time "$GOL" build --updatable "$WORK/before.gol" "$PBF"
"$GOL" check "$WORK/before.gol"
cp "$WORK/before.gol" "$WORK/after.gol"
echo "== update"
time "$GOL" update "$WORK/after.gol"
"$GOL" check "$WORK/after.gol"
echo "== compare from Kotlin"
OSM_REVIEW_TEST_GOL="$WORK/before.gol" OSM_REVIEW_TEST_GOL_AFTER="$WORK/after.gol" \
  ./gradlew :rules:test --tests '*GolUpdateSpikeTest*' -i | grep -E "GolUpdateSpikeTest|changed|added|removed|PASSED|FAILED"
