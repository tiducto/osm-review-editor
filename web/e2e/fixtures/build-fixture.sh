#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <czech-republic-latest.osm.pbf> <gol binary>" >&2
  exit 1
fi

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

osmium extract -b 17.897,49.933,17.913,49.943 --strategy complete_ways "$1" -o "$tmp/opava-centre.osm.pbf"
"$2" build "$tmp/opava-centre.gol" "$tmp/opava-centre.osm.pbf"
cp "$tmp/opava-centre.gol" "$here/opava-centre.gol"
